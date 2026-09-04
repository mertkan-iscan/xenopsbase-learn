package com.xenopsoftware.learn.streaming.video;

import static org.assertj.core.api.Assertions.assertThat;

import com.xenopsoftware.learn.common.tenancy.TenantContext;
import com.xenopsoftware.learn.streaming.PostgresTestHarness;
import com.xenopsoftware.learn.streaming.media.FakeMediaProvider;
import com.xenopsoftware.learn.streaming.media.MediaAssetState;
import com.xenopsoftware.learn.streaming.media.ProviderEvent;
import java.sql.Timestamp;
import java.time.Instant;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The encode state machine (T-3.3): one transition per event however many times it arrives, no
 * way backwards out of a terminal state, and a poll that gets there anyway when the webhooks
 * never come.
 */
@SpringBootTest(properties = "streaming.encode.stuck-after=PT0S")
class EncodeStateTest extends PostgresTestHarness {

    @Autowired
    private EncodeStateService encodeState;

    @Autowired
    private EncodeReconciler reconciler;

    @Autowired
    private VideoUploadService uploads;

    @Autowired
    private FakeMediaProvider provider;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;
    private String ref;

    @BeforeEach
    void anAssetWaitingToEncode() throws Exception {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM provider_event");
        jdbc.update("DELETE FROM video_asset");
        ref = TenantContext.callWith("acme",
            () -> uploads.createVideo(3600, 900).target().providerRef());
    }

    @Test
    void theSameEventFiveTimesProducesOneTransition() {
        ProviderEvent ready = new ProviderEvent("evt-1", ref, MediaAssetState.READY, 542.5, null);

        assertThat(encodeState.apply("fake", ready)).isEqualTo(EncodeStateService.Outcome.APPLIED);
        for (int delivery = 2; delivery <= 5; delivery++) {
            assertThat(encodeState.apply("fake", ready))
                .as("delivery %d", delivery)
                .isEqualTo(EncodeStateService.Outcome.DUPLICATE);
        }

        assertThat(state()).isEqualTo("READY");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM provider_event", Long.class)).isEqualTo(1);
    }

    @Test
    void aLateProcessingEventCannotUnreadyAVideoSomebodyIsWatching() {
        encodeState.apply("fake", new ProviderEvent("evt-ready", ref, MediaAssetState.READY, 542.5, null));

        // A different event, genuinely new, arriving out of order behind the one it precedes.
        // Idempotency does nothing about this; terminality does.
        EncodeStateService.Outcome outcome = encodeState.apply("fake",
            new ProviderEvent("evt-processing", ref, MediaAssetState.PROCESSING, null, null));

        assertThat(outcome).isEqualTo(EncodeStateService.Outcome.TERMINAL);
        assertThat(state()).isEqualTo("READY");
    }

    @Test
    void anEncodeFailureReachesTheAuthorWithTheProvidersOwnWords() {
        encodeState.apply("fake", new ProviderEvent("evt-failed", ref, MediaAssetState.ERRORED,
            null, "audio codec not supported"));

        assertThat(state()).isEqualTo("ERRORED");
        // Not "encoding failed": one of these gets a support ticket, the other gets a re-export.
        assertThat(jdbc.queryForObject(
            "SELECT error_reason FROM video_asset WHERE provider_ref = ?", String.class, ref))
            .isEqualTo("audio codec not supported");
    }

    @Test
    void everyWebhookDroppedAndThePollStillReachesReady() {
        // The cluster was down for the night (ADR-0002), so not one notification arrived. The
        // provider finished anyway.
        provider.completeUpload(ref, 542.5);
        assertThat(state()).isEqualTo("PENDING_UPLOAD");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM provider_event", Long.class)).isZero();

        int moved = reconciler.reconcile();

        assertThat(moved).isEqualTo(1);
        assertThat(state()).isEqualTo("READY");
        assertThat(jdbc.queryForObject(
            "SELECT duration_seconds FROM video_asset WHERE provider_ref = ?", Double.class, ref))
            .isEqualTo(542.5);
    }

    @Test
    void thePollIsIdempotentToo() {
        provider.completeUpload(ref, 542.5);
        reconciler.reconcile();

        // Asking twice and getting the same answer must not be recorded as two of anything --
        // and nothing is recorded at all, because a poll is not an event.
        assertThat(reconciler.reconcile()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM provider_event", Long.class)).isZero();
        assertThat(state()).isEqualTo("READY");
    }

    @Test
    void thePollLeavesAnAssetThatIsStillEncodingAlone() {
        Timestamp before = jdbc.queryForObject(
            "SELECT updated_at FROM video_asset WHERE provider_ref = ?", Timestamp.class, ref);

        assertThat(reconciler.reconcile()).isZero();

        // updated_at must not move on a no-op: it is what staleness is measured with, and
        // refreshing it every sweep would hide an asset that is genuinely stuck.
        assertThat(jdbc.queryForObject(
            "SELECT updated_at FROM video_asset WHERE provider_ref = ?", Timestamp.class, ref))
            .isEqualTo(before);
    }

    @Test
    void anEventForAnAssetWeNeverHadIsNotAnError() {
        assertThat(encodeState.apply("fake", new ProviderEvent("evt-x", "fake-nobody",
            MediaAssetState.READY, 1.0, null)))
            .isEqualTo(EncodeStateService.Outcome.UNKNOWN_ASSET);
    }

    @Test
    void aWebhookAndThePollAgreeingOnTheSameStateChangeNothingTwice() {
        provider.completeUpload(ref, 542.5);
        encodeState.apply("fake", new ProviderEvent("evt-ready", ref, MediaAssetState.READY, 542.5, null));

        // The race the two paths exist to survive: both arrive, one wins, nothing breaks.
        assertThat(reconciler.reconcile()).isZero();
        assertThat(state()).isEqualTo("READY");
    }

    private String state() {
        return jdbc.queryForObject(
            "SELECT state FROM video_asset WHERE provider_ref = ?", String.class, ref);
    }
}
