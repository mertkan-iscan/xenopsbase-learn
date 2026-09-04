package com.xenopsoftware.learn.streaming.video;

import com.xenopsoftware.learn.streaming.media.MediaAssetStatus;
import com.xenopsoftware.learn.streaming.media.MediaProvider;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The half that makes the state converge when the webhook never arrives (T-3.3).
 *
 * <p>A webhook is a message from someone else system over a network that fails, and ADR-0002
 * means our endpoint is sometimes not there at all: a cluster torn down for the night misses
 * every notification sent while it was gone. Without this, those assets sit in PROCESSING
 * forever, which reads to an author as a broken platform and to us as silence.
 *
 * <p>It asks the provider rather than waiting to be told, and it is deliberately dull: find what
 * has not moved, ask, apply through the same code the webhook uses. Two paths into one
 * transition is the only way both can be idempotent.
 */
@Component
public class EncodeReconciler {

    private static final Logger LOG = LoggerFactory.getLogger(EncodeReconciler.class);

    private final JdbcTemplate jdbc;
    private final MediaProvider mediaProvider;
    private final EncodeStateService encodeState;
    private final Duration stuckAfter;

    public EncodeReconciler(DataSource dataSource, MediaProvider mediaProvider,
            EncodeStateService encodeState,
            @Value("${streaming.encode.stuck-after:PT10M}") Duration stuckAfter) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.mediaProvider = mediaProvider;
        this.encodeState = encodeState;
        this.stuckAfter = stuckAfter;
    }

    @Scheduled(fixedDelayString = "${streaming.encode.reconcile-interval:PT5M}")
    public void reconcileOnSchedule() {
        reconcile();
    }

    /** Asks the provider about everything that has not moved recently. Returns how many moved. */
    public int reconcile() {
        Timestamp cutoff = Timestamp.from(Instant.now().minus(stuckAfter));
        List<Map<String, Object>> unsettled = jdbc.queryForList("""
            SELECT provider, provider_ref
              FROM video_asset
             WHERE state IN ('PENDING_UPLOAD', 'PROCESSING')
               AND updated_at < ?
             ORDER BY updated_at
             LIMIT 200
            """, cutoff);

        int moved = 0;
        for (Map<String, Object> asset : unsettled) {
            String provider = (String) asset.get("provider");
            String ref = (String) asset.get("provider_ref");
            if (!mediaProvider.providerId().equals(provider)) {
                // A ref minted by a provider no longer configured. Nothing to ask.
                continue;
            }
            try {
                MediaAssetStatus status = mediaProvider.status(ref);
                if (encodeState.reconcile(provider, ref, status.state(), status.durationSeconds(),
                        status.error()) == EncodeStateService.Outcome.APPLIED) {
                    moved++;
                }
            } catch (RuntimeException providerUnreachable) {
                // One asset failing must not end the sweep: the next run tries again, and
                // keeping on trying is the entire point of this job.
                LOG.warn("Could not reconcile {} with its provider; will retry", ref, providerUnreachable);
            }
        }
        if (moved > 0) {
            LOG.info("Reconciled {} asset(s) the webhook never reported", moved);
        }
        return moved;
    }
}
