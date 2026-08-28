package com.xenopsoftware.learn.streaming.video;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xenopsoftware.learn.common.tenancy.TenantContext;
import com.xenopsoftware.learn.streaming.PostgresTestHarness;
import com.xenopsoftware.learn.streaming.media.FakeMediaProvider;
import com.xenopsoftware.learn.streaming.media.MediaAssetState;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The upload rules against a real Postgres and the fake provider (T-3.2): limits before
 * issuance, re-issue replacing the provider asset, and the reaper.
 */
@SpringBootTest(properties = {
    "streaming.upload.max-size-bytes=1000",
    "streaming.upload.tenant-quota-bytes=2500",
    "streaming.upload.abandon-after=PT1H"
})
class VideoUploadServiceTest extends PostgresTestHarness {

    @Autowired
    private VideoUploadService service;

    @Autowired
    private VideoAssetRepository repository;

    @Autowired
    private FakeMediaProvider fakeProvider;

    @Autowired
    private UploadReaper reaper;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void emptyTheTable() {
        new JdbcTemplate(dataSource).update("DELETE FROM video_asset");
    }

    @Test
    void anIssuedTargetIsBoundToItsAssetRow() throws Exception {
        VideoUploadService.IssuedUpload issued = TenantContext.callWith("acme",
            () -> service.createVideo(3600, 900));

        assertThat(issued.asset().getState()).isEqualTo(VideoAssetState.PENDING_UPLOAD);
        assertThat(issued.asset().getProviderRef()).isEqualTo(issued.target().providerRef());
        assertThat(issued.asset().getTenantId()).isEqualTo("acme");
        assertThat(fakeProvider.status(issued.target().providerRef()).state())
            .isEqualTo(MediaAssetState.PENDING_UPLOAD);
    }

    @Test
    void theSizeCeilingRefusesBeforeAnyTargetExists() throws Exception {
        assertThatThrownBy(() -> TenantContext.callWith("acme", () -> service.createVideo(3600, 1001)))
            .isInstanceOf(UploadLimitException.class);
        assertThat(TenantContext.callWith("acme", () -> repository.count())).isZero();
    }

    @Test
    void quotaCountsWhatWasDeclaredNotWhatArrived() throws Exception {
        // Three pending uploads of 900 declared bytes against a 2500 quota: the third must be
        // refused even though not one byte has actually arrived anywhere -- otherwise parallel
        // target requests oversubscribe the quota arbitrarily.
        TenantContext.callWith("acme", () -> service.createVideo(3600, 900));
        TenantContext.callWith("acme", () -> service.createVideo(3600, 900));

        assertThatThrownBy(() -> TenantContext.callWith("acme", () -> service.createVideo(3600, 900)))
            .isInstanceOf(QuotaExceededException.class);

        // Another tenant's library is not this tenant's problem: globex has full room.
        assertThat(TenantContext.callWith("globex",
            () -> service.createVideo(3600, 900).asset().getId())).isNotNull();
    }

    @Test
    void reissueMintsAFreshTargetAndDeletesTheOldProviderAsset() throws Exception {
        VideoUploadService.IssuedUpload first = TenantContext.callWith("acme",
            () -> service.createVideo(3600, 900));
        String oldRef = first.target().providerRef();
        UUID assetId = first.asset().getId();

        VideoUploadService.IssuedUpload second = TenantContext.callWith("acme",
            () -> service.reissueTarget(assetId));

        assertThat(second.asset().getId()).isEqualTo(assetId);
        assertThat(second.target().providerRef()).isNotEqualTo(oldRef);
        // The old target is dead at the provider: exactly one live (provider, ref) per asset.
        assertThat(fakeProvider.status(oldRef).state()).isEqualTo(MediaAssetState.GONE);
        assertThat(fakeProvider.status(second.target().providerRef()).state())
            .isEqualTo(MediaAssetState.PENDING_UPLOAD);
    }

    @Test
    void aFinishedUploadDoesNotGetANewTarget() throws Exception {
        VideoUploadService.IssuedUpload issued = TenantContext.callWith("acme",
            () -> service.createVideo(3600, 900));
        new JdbcTemplate(dataSource).update(
            "UPDATE video_asset SET state = 'READY' WHERE id = ?", issued.asset().getId());

        assertThatThrownBy(() -> TenantContext.callWith("acme",
            () -> service.reissueTarget(issued.asset().getId())))
            .hasMessageContaining("409");
    }

    @Test
    void theReaperTakesStaleUploadsAndDeletesTheirProviderAssets() throws Exception {
        VideoUploadService.IssuedUpload stale = TenantContext.callWith("acme",
            () -> service.createVideo(3600, 900));
        VideoUploadService.IssuedUpload fresh = TenantContext.callWith("acme",
            () -> service.createVideo(3600, 900));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("UPDATE video_asset SET updated_at = ? WHERE id = ?",
            Timestamp.from(Instant.now().minusSeconds(7200)), stale.asset().getId());

        int reaped = reaper.reap();

        assertThat(reaped).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT state FROM video_asset WHERE id = ?",
            String.class, stale.asset().getId())).isEqualTo("ABANDONED");
        assertThat(fakeProvider.status(stale.target().providerRef()).state())
            .isEqualTo(MediaAssetState.GONE);
        // The fresh upload is untouched, provider asset included.
        assertThat(jdbc.queryForObject("SELECT state FROM video_asset WHERE id = ?",
            String.class, fresh.asset().getId())).isEqualTo("PENDING_UPLOAD");
        assertThat(fakeProvider.status(fresh.target().providerRef()).state())
            .isEqualTo(MediaAssetState.PENDING_UPLOAD);
    }

    @Test
    void reapedBytesLeaveTheQuota() throws Exception {
        VideoUploadService.IssuedUpload stale = TenantContext.callWith("acme",
            () -> service.createVideo(3600, 900));
        TenantContext.callWith("acme", () -> service.createVideo(3600, 900));
        new JdbcTemplate(dataSource).update("UPDATE video_asset SET updated_at = ? WHERE id = ?",
            Timestamp.from(Instant.now().minusSeconds(7200)), stale.asset().getId());
        reaper.reap();

        // 900 abandoned + 900 pending: a third 900 fits again because ABANDONED is not
        // accountable -- the reaper is also what un-sticks a tenant's quota.
        assertThat(TenantContext.callWith("acme",
            () -> service.createVideo(3600, 900).asset().getId())).isNotNull();
    }
}
