package com.xenopsoftware.learn.streaming.video;

import com.xenopsoftware.learn.streaming.media.MediaAssetState;
import com.xenopsoftware.learn.streaming.media.ProviderEvent;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Where an asset's encode state actually changes (T-3.3), whichever of the two paths brought the
 * news.
 *
 * <p>Plain SQL and no tenant bound: this is called from a webhook, which is a provider talking to
 * us about an asset before anything of ours has decided whose it is. The asset row carries the
 * tenant; the event does not have one to be filtered by. Which is also why the transaction is
 * an explicit JDBC one ({@code TenantlessTransactionConfiguration}) rather than {@code @Transactional} —
 * that would open a Hibernate session, and a session needs a tenant this caller does not have.
 *
 * <h2>Idempotent, and out-of-order safe, are two different guarantees</h2>
 *
 * The same event five times produces one transition because the event id is a primary key: four
 * of them lose the insert and stop. That does nothing about a <i>different</i> event arriving
 * late, so terminality is enforced separately — an asset that is READY or ERRORED never moves
 * again on a provider's say-so. A PROCESSING notification delayed behind the READY it precedes
 * is the ordinary case, not the exotic one.
 */
@Service
public class EncodeStateService {

    private static final Logger LOG = LoggerFactory.getLogger(EncodeStateService.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public EncodeStateService(DataSource dataSource, TransactionTemplate tenantlessTransactions) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.transactions = tenantlessTransactions;
    }

    /** What happened to the asset, for the caller that wants to log or count it. */
    public enum Outcome { APPLIED, DUPLICATE, TERMINAL, UNKNOWN_ASSET, NO_CHANGE }

    /**
     * Applies a webhook event exactly once. The event is recorded and the state moved in one
     * transaction: an event marked seen whose transition rolled back would be an update nobody
     * can replay.
     */
    public Outcome apply(String provider, ProviderEvent event) {
        return transactions.execute(status -> applyOnce(provider, event));
    }

    private Outcome applyOnce(String provider, ProviderEvent event) {
        try {
            jdbc.update("""
                INSERT INTO provider_event (provider, event_id, provider_ref)
                VALUES (?, ?, ?)
                """, provider, event.eventId(), event.providerRef());
        } catch (DuplicateKeyException seenBefore) {
            return Outcome.DUPLICATE;
        }
        return applyState(provider, event.providerRef(), event.state(), event.durationSeconds(),
            event.error());
    }

    /**
     * Applies a state the poll observed. No event id, because a poll is not an event: asking
     * twice and getting the same answer must not be recorded as two of anything, and the
     * transition itself is what has to be repeatable.
     */
    public Outcome reconcile(String provider, String providerRef, MediaAssetState state,
            Double durationSeconds, String error) {
        return transactions.execute(status ->
            applyState(provider, providerRef, state, durationSeconds, error));
    }

    private Outcome applyState(String provider, String providerRef, MediaAssetState state,
            Double durationSeconds, String error) {
        String current = jdbc.query("""
            SELECT state FROM video_asset WHERE provider = ? AND provider_ref = ?
            """, rows -> rows.next() ? rows.getString(1) : null, provider, providerRef);
        if (current == null) {
            // An event for something we never created, or created and deleted. Logged rather
            // than thrown: the provider is not wrong to have told us, and there is nothing to
            // do about it.
            LOG.info("Provider {} sent state {} for unknown ref {}", provider, state, providerRef);
            return Outcome.UNKNOWN_ASSET;
        }
        if (isTerminal(current)) {
            // The out-of-order guard. Nothing a provider says moves a finished asset, which is
            // what stops a late PROCESSING from un-readying a video somebody is watching.
            return Outcome.TERMINAL;
        }
        VideoAssetState next = map(state);
        if (next == null) {
            return Outcome.NO_CHANGE;
        }
        if (next.name().equals(current)) {
            // Still encoding, told again. Not a transition, and updated_at deliberately does
            // not move: it is what the reconciler measures staleness with, and refreshing it
            // on every no-op would hide an asset that is genuinely stuck.
            return Outcome.NO_CHANGE;
        }
        jdbc.update("""
            UPDATE video_asset
               SET state = ?, duration_seconds = coalesce(?, duration_seconds),
                   error_reason = ?, updated_at = now()
             WHERE provider = ? AND provider_ref = ?
            """, next.name(), durationSeconds, truncate(error), provider, providerRef);
        LOG.info("Asset {} moved {} -> {}", providerRef, current, next);
        return Outcome.APPLIED;
    }

    private static boolean isTerminal(String state) {
        return VideoAssetState.READY.name().equals(state)
            || VideoAssetState.ERRORED.name().equals(state)
            || VideoAssetState.ABANDONED.name().equals(state);
    }

    /** The provider's vocabulary onto ours. GONE is not a transition anything here makes. */
    private static VideoAssetState map(MediaAssetState state) {
        return switch (state) {
            case PENDING_UPLOAD -> VideoAssetState.PENDING_UPLOAD;
            case PROCESSING -> VideoAssetState.PROCESSING;
            case READY -> VideoAssetState.READY;
            case ERRORED -> VideoAssetState.ERRORED;
            case GONE -> null;
        };
    }

    /** Somebody else's string, in our column. Truncated rather than dropped. */
    private static String truncate(String error) {
        if (error == null || error.isBlank()) {
            return null;
        }
        return error.length() <= 512 ? error : error.substring(0, 512);
    }
}
