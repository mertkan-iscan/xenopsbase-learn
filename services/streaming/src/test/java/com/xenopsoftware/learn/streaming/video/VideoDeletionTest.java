package com.xenopsoftware.learn.streaming.video;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xenopsoftware.learn.common.tenancy.TenantContext;
import com.xenopsoftware.learn.streaming.PostgresTestHarness;
import com.xenopsoftware.learn.streaming.media.FakeMediaProvider;
import com.xenopsoftware.learn.streaming.media.MediaAssetState;
import com.xenopsoftware.learn.streaming.media.UploadRequest;
import com.xenopsoftware.learn.streaming.playback.PlaybackTestBeans;
import com.xenopsoftware.learn.streaming.playback.StubViewerDirectory;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Deleting a video deletes the bytes, and the row is never allowed to say so first (T-3.8).
 *
 * <p>The ordering is the whole guarantee, and both directions of getting it wrong are tested here:
 * a row claiming a deletion the provider never performed is a leak wearing a tombstone, and bytes
 * gone while the row still says READY is the failure nothing can detect afterwards.
 */
@SpringBootTest
@Import(PlaybackTestBeans.class)
class VideoDeletionTest extends PostgresTestHarness {

    private static final UUID ADMIN = UUID.fromString("00000000-0000-4000-8000-00000000ad11");

    @Autowired
    private VideoUploadService uploads;

    @Autowired
    private VideoDeletionService deletions;

    @Autowired
    private DeletionReconciler reconciler;

    @Autowired
    private OrphanReconciler orphans;

    @Autowired
    private FakeMediaProvider provider;

    @Autowired
    private StubViewerDirectory viewers;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    @BeforeEach
    void emptyTheTables() {
        jdbc = new JdbcTemplate(dataSource);
        // The module's Postgres is shared across test classes: start from a known table rather
        // than from whatever another class left.
        jdbc.update("DELETE FROM video_asset");
        jdbc.update("DELETE FROM provider_orphan");
        viewers.resolvesTo(ADMIN);
    }

    // ------------------------------------------------------------ the request

    @Test
    void theRequestStopsPlaybackAndAsksTheProviderNothingYet() throws Exception {
        Video video = readyVideo();

        VideoAsset asked = TenantContext.callWith("acme",
            () -> deletions.requestDeletion(video.id(), "the customer asked us to"));

        assertThat(asked.getState()).isEqualTo(VideoAssetState.DELETING);
        assertThat(provider.status(video.ref()).state())
            .as("nothing was deleted inside the request: a provider call that succeeded and then "
                + "rolled back would leave the bytes gone and the row saying READY")
            .isNotEqualTo(MediaAssetState.GONE);
    }

    @Test
    void aDeletionWithNoReasonIsRefused() {
        assertThatThrownBy(() -> TenantContext.callWith("acme",
            () -> deletions.requestDeletion(readyVideo().id(), "  ")))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("needs a reason");
    }

    /** The record of who asked is the audit record, and a second press must not overwrite it. */
    @Test
    void pressingDeleteTwiceKeepsWhoAskedFirstAndWhy() throws Exception {
        Video video = readyVideo();
        TenantContext.callWith("acme", () -> deletions.requestDeletion(video.id(), "GDPR erasure"));

        viewers.resolvesTo(UUID.randomUUID());
        VideoAsset again = TenantContext.callWith("acme",
            () -> deletions.requestDeletion(video.id(), "clicked again"));

        assertThat(again.getDeletionReason()).isEqualTo("GDPR erasure");
        assertThat(again.getDeletionRequestedBy()).isEqualTo(ADMIN);
    }

    // ------------------------------------------------------------ the reconciler

    @Test
    void theBytesGoAndOnlyThenDoesTheRowSayDeleted() throws Exception {
        Video video = readyVideo();
        TenantContext.callWith("acme", () -> deletions.requestDeletion(video.id(), "no longer used"));

        assertThat(reconciler.reconcile()).isEqualTo(1);

        assertThat(provider.status(video.ref()).state()).isEqualTo(MediaAssetState.GONE);
        Map<String, Object> row = row(video.id());
        assertThat(row.get("state")).isEqualTo("DELETED");
        assertThat(row.get("deleted_at")).as("the claim is only made after the provider confirmed")
            .isNotNull();
        assertThat(row.get("deletion_error")).isNull();
    }

    /** The row outlives the bytes, on purpose: a dangling content_item id needs an answer. */
    @Test
    void theRowStaysAfterTheBytesAreGone() throws Exception {
        Video video = readyVideo();
        TenantContext.callWith("acme", () -> deletions.requestDeletion(video.id(), "no longer used"));
        reconciler.reconcile();

        assertThat(row(video.id()))
            .containsEntry("deletion_reason", "no longer used")
            .containsEntry("deletion_requested_by", ADMIN);
    }

    /**
     * A ref the configured adapter cannot address stays in the queue and says why.
     *
     * <p>The reaper logs this and moves on, because an abandoned upload is nobody's obligation.
     * Here it is one, so the row keeps coming back until a person deals with it.
     */
    @Test
    void anAssetTheProviderCannotBeAskedAboutStaysInTheQueue() throws Exception {
        Video video = readyVideo();
        TenantContext.callWith("acme", () -> deletions.requestDeletion(video.id(), "policy"));
        jdbc.update("UPDATE video_asset SET provider = 'a-provider-we-no-longer-run' WHERE id = ?",
            video.id());

        assertThat(reconciler.reconcile()).isZero();

        Map<String, Object> stuck = row(video.id());
        assertThat(stuck.get("state")).isEqualTo("DELETING");
        assertThat((String) stuck.get("deletion_error")).contains("cannot be deleted from here");
        assertThat(stuck.get("deletion_attempts")).isEqualTo(1);

        // And it is retried rather than dropped: the queue is compliance obligations, and the only
        // way out of it is confirmation.
        jdbc.update("UPDATE video_asset SET provider = 'fake' WHERE id = ?", video.id());
        assertThat(reconciler.reconcile()).isEqualTo(1);
        assertThat(row(video.id()).get("state")).isEqualTo("DELETED");
    }

    /**
     * A delete whose response was lost must not retry forever against an asset that is already
     * gone: the reconciler asks the provider where the asset is before deleting it.
     */
    @Test
    void anAssetAlreadyGoneAtTheProviderConfirmsRatherThanRetries() throws Exception {
        Video video = readyVideo();
        TenantContext.callWith("acme", () -> deletions.requestDeletion(video.id(), "policy"));
        provider.delete(video.ref()); // the call that reached the provider and whose answer was lost

        assertThat(reconciler.reconcile()).isEqualTo(1);
        assertThat(row(video.id()).get("state")).isEqualTo("DELETED");
    }

    // ------------------------------------------------------------ the other direction

    @Test
    void anAssetWithNoRowIsReportedAndNotDeleted() {
        String orphan = provider.createUploadTarget(new UploadRequest(60, 100)).providerRef();

        orphans.sweep();

        assertThat(orphanRefs()).contains(orphan);
        assertThat(provider.status(orphan).state())
            .as("reported, never deleted automatically: this service's view of the provider "
                + "account is not authoritative")
            .isNotEqualTo(MediaAssetState.GONE);
    }

    @Test
    void seeingTheSameOrphanTwiceIsOneRowWithAMovingLastSeen() {
        String orphan = provider.createUploadTarget(new UploadRequest(60, 100)).providerRef();

        orphans.sweep();
        orphans.sweep();

        Integer rows = jdbc.queryForObject(
            "SELECT count(*) FROM provider_orphan WHERE provider_ref = ?", Integer.class, orphan);
        assertThat(rows).as("the report is a list of leaks, not a list of sightings").isEqualTo(1);
    }

    /** A deletion in flight is not an orphan; reporting it would send somebody to do it twice. */
    @Test
    void aDeletionInFlightIsNotAnOrphan() throws Exception {
        Video video = readyVideo();
        TenantContext.callWith("acme", () -> deletions.requestDeletion(video.id(), "policy"));

        orphans.sweep();

        assertThat(orphanRefs()).doesNotContain(video.ref());
    }

    // ------------------------------------------------------------ fixtures

    private record Video(UUID id, String ref) {}

    private Video readyVideo() {
        try {
            VideoUploadService.IssuedUpload issued =
                TenantContext.callWith("acme", () -> uploads.createVideo(3600, 900));
            jdbc.update("UPDATE video_asset SET state = 'READY' WHERE id = ?", issued.asset().getId());
            return new Video(issued.asset().getId(), issued.target().providerRef());
        } catch (Exception cannotHappen) {
            throw new IllegalStateException(cannotHappen);
        }
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("SELECT * FROM video_asset WHERE id = ?", id);
    }

    private List<String> orphanRefs() {
        return jdbc.queryForList("SELECT provider_ref FROM provider_orphan", String.class);
    }
}
