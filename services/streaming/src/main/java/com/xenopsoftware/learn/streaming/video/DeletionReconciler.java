package com.xenopsoftware.learn.streaming.video;

import com.xenopsoftware.learn.streaming.media.MediaAssetState;
import com.xenopsoftware.learn.streaming.media.MediaProvider;
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
 * Deletion is retried until the provider confirms (T-3.8).
 *
 * <p>The row is the queue: {@link VideoDeletionService} writes DELETING and returns, and this
 * drains it. Nothing writes DELETED except this class, and it writes it only after a provider has
 * said the asset is gone.
 *
 * <p>Plain JDBC and cross-tenant, like {@code UploadReaper} and for the same structural reason:
 * this is infrastructure work on a thread that binds no tenant, which the T-1.1 resolver rightly
 * refuses a Hibernate session for. A sweep that spans every customer should say so and use the
 * tool that cannot pretend otherwise.
 *
 * <h2>Two ways an attempt can succeed</h2>
 *
 * <p>{@link MediaProvider#delete} returning normally is one. The other is {@link
 * MediaProvider#status} answering GONE, which matters after a partial failure: a delete that
 * reached the provider and whose response was lost leaves a row that will retry forever against an
 * asset that no longer exists. Asking first turns that into one extra call and a confirmation.
 *
 * <p>A failure is <b>left DELETING</b> with the error recorded and the attempt counted. Never
 * DELETED, never dropped: this is a queue whose items are compliance obligations, and the only
 * acceptable way for one to leave it is confirmation.
 *
 * <h2>A ref this provider did not mint</h2>
 *
 * <p>A database that switched providers holds refs the configured adapter cannot address. The
 * reaper logs and moves on because an abandoned upload is nobody's obligation; here it is, so the
 * row stays DELETING with that stated as its error. It will be listed by every run and stay in
 * front of an operator, which is the correct amount of noise for "the bytes of a video a customer
 * asked us to delete are somewhere we can no longer reach".
 */
@Component
public class DeletionReconciler {

    private static final Logger LOG = LoggerFactory.getLogger(DeletionReconciler.class);

    private final JdbcTemplate jdbc;
    private final MediaProvider mediaProvider;
    private final int batchSize;

    public DeletionReconciler(DataSource dataSource, MediaProvider mediaProvider,
            @Value("${streaming.deletion.batch-size:100}") int batchSize) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.mediaProvider = mediaProvider;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${streaming.deletion.reconcile-interval:PT1M}")
    public void reconcileOnSchedule() {
        reconcile();
    }

    /** @return how many assets this run confirmed deleted */
    public int reconcile() {
        List<Map<String, Object>> pending = jdbc.queryForList("""
            SELECT id, provider, provider_ref
              FROM video_asset
             WHERE state = 'DELETING'
             ORDER BY updated_at
             LIMIT ?
            """, batchSize);

        int confirmed = 0;
        for (Map<String, Object> row : pending) {
            Object id = row.get("id");
            String provider = (String) row.get("provider");
            String providerRef = (String) row.get("provider_ref");

            if (!mediaProvider.providerId().equals(provider)) {
                record(id, "Its provider '" + provider + "' is not the configured '"
                    + mediaProvider.providerId() + "', so these bytes cannot be deleted from here.");
                continue;
            }

            try {
                if (mediaProvider.status(providerRef).state() != MediaAssetState.GONE) {
                    mediaProvider.delete(providerRef);
                }
                jdbc.update("""
                    UPDATE video_asset
                       SET state = 'DELETED', deleted_at = now(), deletion_error = NULL,
                           updated_at = now()
                     WHERE id = ?
                    """, id);
                confirmed++;
            } catch (RuntimeException notYet) {
                // Left DELETING on purpose. The video is already unplayable, so the cost of another
                // attempt is a minute; the cost of writing DELETED here would be a claim about
                // somebody else's storage that we have no evidence for.
                record(id, notYet.getClass().getSimpleName() + ": " + notYet.getMessage());
                LOG.warn("Could not confirm deletion of video {}; will retry", id, notYet);
            }
        }

        if (confirmed > 0) {
            LOG.info("Confirmed {} video deletion(s) with the provider", confirmed);
        }
        return confirmed;
    }

    /**
     * Count the attempt and keep the reason, without moving {@code updated_at}.
     *
     * <p>The ordering of the queue is by {@code updated_at}, so touching it here would push a row
     * that keeps failing to the back every time and let it starve behind newer requests — which is
     * the opposite of what a deletion nobody can complete deserves.
     */
    private void record(Object id, String error) {
        jdbc.update("""
            UPDATE video_asset
               SET deletion_attempts = deletion_attempts + 1, deletion_error = ?
             WHERE id = ?
            """, error, id);
    }
}
