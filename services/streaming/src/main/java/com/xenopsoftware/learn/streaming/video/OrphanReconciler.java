package com.xenopsoftware.learn.streaming.video;

import com.xenopsoftware.learn.streaming.media.MediaProvider;
import com.xenopsoftware.learn.streaming.media.ProviderAssetPage;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Bytes we are paying for and no longer know about (T-3.8).
 *
 * <p>The mirror of {@link DeletionReconciler}. That one makes sure a row marked deleted is not
 * lying; this one asks the opposite question — is the provider holding an asset no row here
 * accounts for — and it is the only way to find out, because an orphan is by definition invisible
 * from this side.
 *
 * <p>It happens for ordinary reasons: an upload target created in a transaction that rolled back,
 * a restore from a backup taken before an upload finished, a database that changed providers.
 *
 * <h2>Reported, never deleted automatically</h2>
 *
 * <p>A decision rather than caution. This service's view of the provider account is <b>not
 * authoritative</b>: the same account may hold assets another environment or another tool created,
 * and a job that deleted everything it did not recognise would eventually recognise nothing — a
 * listing that fails halfway, a provider id that changed, a bug in the join. The blast radius of
 * being wrong once is every video a company owns, and it is not recoverable. Reporting is wrong in
 * the direction of a bill; deleting is wrong in the direction of a customer's content.
 *
 * <p>So the output is rows in {@code provider_orphan} and a log line with a count. Deleting one is
 * a person's decision, taken against a list they can read.
 *
 * <h2>What is compared, and why the row is not filtered by state</h2>
 *
 * <p>A ref is an orphan when {@code video_asset} has NO row for it at all — including rows in
 * DELETING and DELETED, which are the interesting near-misses. A DELETING row whose asset is still
 * listed is a deletion in flight, not an orphan, and reporting it would send an operator to delete
 * bytes the reconciler is about to delete anyway. A DELETED row whose asset is still listed is a
 * genuine problem, and it is one this sweep must not mistake for an unknown asset: the fix there is
 * to reopen the deletion, not to hand somebody a bare ref.
 */
@Component
public class OrphanReconciler {

    private static final Logger LOG = LoggerFactory.getLogger(OrphanReconciler.class);

    /** A ceiling on one run, so a sweep cannot become an unbounded walk of somebody else's API. */
    private static final int MAX_PAGES = 100;

    private final JdbcTemplate jdbc;
    private final MediaProvider mediaProvider;
    private final boolean enabled;

    public OrphanReconciler(DataSource dataSource, MediaProvider mediaProvider,
            @Value("${streaming.orphan-sweep.enabled:true}") boolean enabled) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.mediaProvider = mediaProvider;
        this.enabled = enabled;
    }

    /**
     * Daily by default.
     *
     * <p>Not hourly: nothing acts on the result automatically, a person reads it, and a sweep that
     * runs more often than anybody looks is just requests against a rate limit.
     */
    @Scheduled(fixedDelayString = "${streaming.orphan-sweep.interval:PT24H}",
        initialDelayString = "${streaming.orphan-sweep.initial-delay:PT5M}")
    public void sweepOnSchedule() {
        if (enabled) {
            sweep();
        }
    }

    /** @return how many refs the provider holds that no row here accounts for */
    public int sweep() {
        Instant seenAt = Instant.now();
        List<String> orphans = new ArrayList<>();

        String cursor = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            ProviderAssetPage listed = mediaProvider.list(cursor);
            for (String ref : listed.refs()) {
                if (!known(ref)) {
                    orphans.add(ref);
                }
            }
            cursor = listed.cursor();
            if (cursor == null) {
                break;
            }
            if (page == MAX_PAGES - 1) {
                // Said out loud rather than silently truncated: a partial sweep that looks complete
                // is worse than no sweep, because the empty tail reads as "nothing found there".
                LOG.warn("Orphan sweep stopped after {} pages with more to list; the report is "
                    + "incomplete", MAX_PAGES);
            }
        }

        for (String ref : orphans) {
            record(ref, seenAt);
        }

        if (!orphans.isEmpty()) {
            LOG.warn("{} provider asset(s) have no row in video_asset. Reported in provider_orphan, "
                + "and deleted by nothing: this service's view of the account is not authoritative "
                + "(T-3.8).", orphans.size());
        }
        return orphans.size();
    }

    private boolean known(String providerRef) {
        Integer rows = jdbc.queryForObject("""
            SELECT count(*) FROM video_asset WHERE provider = ? AND provider_ref = ?
            """, Integer.class, mediaProvider.providerId(), providerRef);
        return rows != null && rows > 0;
    }

    /**
     * Upsert, so a ref seen on ten runs is one row with a moving {@code last_seen_at} rather than
     * ten rows. {@code first_seen_at} is what dates the leak and is never overwritten.
     */
    private void record(String providerRef, Instant seenAt) {
        jdbc.update("""
            INSERT INTO provider_orphan (id, provider, provider_ref, first_seen_at, last_seen_at)
                 VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (provider, provider_ref)
              DO UPDATE SET last_seen_at = EXCLUDED.last_seen_at
            """, UUID.randomUUID(), mediaProvider.providerId(), providerRef,
            Timestamp.from(seenAt), Timestamp.from(seenAt));
    }
}
