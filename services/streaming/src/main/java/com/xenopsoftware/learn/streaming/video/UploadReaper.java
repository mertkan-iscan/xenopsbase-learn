package com.xenopsoftware.learn.streaming.video;

import com.xenopsoftware.learn.streaming.media.MediaProvider;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Abandoned uploads are reaped on a schedule (T-3.2): a target somebody requested and never
 * finished must not accumulate storage cost forever — the declared bytes also sit inside the
 * tenant's quota until this runs.
 *
 * <p>Plain JDBC, like the catalog seeder, and for the same structural reason: this is
 * infrastructure work that spans every tenant on a thread that binds none, which the T-1.1
 * resolver rightly refuses a Hibernate session for. The discriminator protects request-scoped
 * domain code; a cross-tenant sweep says what it is and uses the tool that cannot pretend
 * otherwise.
 *
 * <p>The clock runs on {@code updated_at}, not on the target's expiry: a re-issued target moves
 * {@code updated_at} forward and buys the upload more time, which is exactly the behavior an
 * author retrying a flaky connection deserves.
 */
@Component
public class UploadReaper {

    private static final Logger LOG = LoggerFactory.getLogger(UploadReaper.class);

    private final JdbcTemplate jdbc;
    private final MediaProvider mediaProvider;
    private final UploadProperties properties;

    public UploadReaper(DataSource dataSource, MediaProvider mediaProvider, UploadProperties properties) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.mediaProvider = mediaProvider;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${streaming.upload.reap-interval:PT15M}")
    public void reapOnSchedule() {
        reap();
    }

    public int reap() {
        Timestamp cutoff = Timestamp.from(Instant.now().minus(properties.abandonAfter()));
        List<Map<String, Object>> stale = jdbc.queryForList("""
            SELECT id, tenant_id, provider, provider_ref
              FROM video_asset
             WHERE state = 'PENDING_UPLOAD'
               AND updated_at < ?
            """, cutoff);

        int reaped = 0;
        for (Map<String, Object> row : stale) {
            String provider = (String) row.get("provider");
            String providerRef = (String) row.get("provider_ref");
            if (mediaProvider.providerId().equals(provider)) {
                try {
                    mediaProvider.delete(providerRef);
                } catch (RuntimeException e) {
                    // Leave the row for the next run rather than marking a provider asset
                    // deleted that is not: ABANDONED must mean the bytes are gone.
                    LOG.warn("Could not delete provider asset {} while reaping; will retry", providerRef, e);
                    continue;
                }
            } else {
                // A ref minted by a provider that is no longer configured -- a dev database
                // that switched providers. Nothing we can delete remotely; say so and move on.
                LOG.warn("Reaping asset {} whose provider '{}' is not the active '{}'; "
                    + "provider-side object, if any, is not deleted",
                    row.get("id"), provider, mediaProvider.providerId());
            }
            jdbc.update("UPDATE video_asset SET state = 'ABANDONED', updated_at = now() WHERE id = ?",
                row.get("id"));
            reaped++;
        }
        if (reaped > 0) {
            LOG.info("Reaped {} abandoned upload(s) older than {}", reaped, properties.abandonAfter());
        }
        return reaped;
    }
}
