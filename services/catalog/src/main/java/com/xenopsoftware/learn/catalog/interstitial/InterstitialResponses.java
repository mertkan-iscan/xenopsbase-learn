package com.xenopsoftware.learn.catalog.interstitial;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * What a learner has answered, and the frontier that follows from it (T-5.4).
 *
 * <p><b>The frontier is the whole point of this class.</b> {@link #frontierOf} answers one
 * question — "how far into this node may this learner be credited right now" — and it is the
 * answer streaming enforces (T-3.7). Everything else here exists to keep that query honest.
 *
 * <p><b>Plain SQL and no entity, for the reason {@code NodeCompletionRepository} gives.</b> This is
 * a projection of somebody else's evidence, read in bulk and never edited. An entity would invite
 * a write through it, and the one writer this table may have is the event handler.
 */
@Component
public class InterstitialResponses {

    /** What {@code viewing} holds for an answer that counts in every viewing. */
    static final String ANY_VIEWING = "";

    private final JdbcTemplate jdbc;

    public InterstitialResponses(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /**
     * How far into this node this learner may be credited, or null when nothing blocks them.
     *
     * <p>The earliest blocking interstitial they have not satisfied. Null when there are none, when
     * they are all answered, or when the node has none at all — three different situations with one
     * correct answer, which is why the caller gets a nullable second rather than a list to reason
     * about.
     *
     * <p><b>{@code ask_again} is what the viewing argument is for.</b> An ordinary interstitial is
     * satisfied by any answer ever recorded: a learner who answered it and came back to re-watch
     * has answered it, and re-asking would punish exactly the behaviour the product wants (T-5.4's
     * fifth criterion, and the answer to the fourth's "seeking backwards over an answered one").
     * One that asks again is satisfied only within the viewing the answer arrived under.
     *
     * @param viewing the playback session being asked about, or null to ask as though this were a
     *                fresh viewing — which is what an author previewing the node wants, and what
     *                {@code ask_again} is measured against when nobody said
     */
    public Integer frontierOf(String tenantId, UUID nodeId, UUID learnerId, String viewing) {
        return jdbc.queryForObject("""
            SELECT min(i.position_seconds)
              FROM node_interstitial i
             WHERE i.tenant_id = ? AND i.node_id = ? AND i.blocking
               AND NOT EXISTS (
                   SELECT 1 FROM interstitial_response r
                    WHERE r.interstitial_id = i.id
                      AND r.learner_id = ?
                      -- An answer recorded without a viewing counts in every viewing, which
                      -- is what the empty string means. Otherwise an ask-again marker whose
                      -- answer arrived unattributed would hold the learner for ever.
                      AND (NOT i.ask_again OR r.viewing = ? OR r.viewing = ''))
            """, Integer.class, tenantId, nodeId, learnerId, viewing == null ? ANY_VIEWING : viewing);
    }

    /** Which of a node's interstitials this learner has satisfied, for the screen that shows them. */
    public List<UUID> answeredOn(String tenantId, UUID nodeId, UUID learnerId, String viewing) {
        return jdbc.queryForList("""
            SELECT i.id
              FROM node_interstitial i
              JOIN interstitial_response r ON r.interstitial_id = i.id
             WHERE i.tenant_id = ? AND i.node_id = ? AND r.learner_id = ?
               AND (NOT i.ask_again OR r.viewing = ? OR r.viewing = '')
             ORDER BY i.position_seconds
            """, UUID.class, tenantId, nodeId, learnerId, viewing == null ? ANY_VIEWING : viewing);
    }

    /**
     * Record an answer, once, however many times the bus delivers it.
     *
     * <p>Conditional on the unique key rather than guarded by a read, for the reason
     * {@code NodeCompletionHandler} spells out: checking "have I recorded this" and then recording
     * it has a window where two deliveries both find nothing and both write, and the database is
     * the only thing that can arbitrate that without one.
     *
     * <p>An answer for an interstitial this tenant does not have inserts nothing and is not an
     * error. The interstitial was removed between the learner answering it and this arriving, which
     * is ordinary; throwing would put a poison message at the head of the queue and hold up every
     * other learner's answer behind it.
     *
     * @return whether a row was written
     */
    public boolean record(String tenantId, UUID interstitialId, UUID learnerId, UUID attemptId,
            String viewing, Instant answeredAt) {
        return jdbc.update("""
            INSERT INTO interstitial_response (id, tenant_id, interstitial_id, learner_id,
                    attempt_id, viewing, answered_at)
            -- Every parameter is cast, because in a SELECT list there is no column to infer a
            -- type from: an untyped null attempt id is "could not determine data type of
            -- parameter $5" rather than a null.
            SELECT ?::uuid, ?::varchar, ?::uuid, ?::uuid, ?::uuid,
                   -- An answer to an interstitial that does not ask again counts in every
                   -- viewing, so it is stored under the empty viewing whatever the player said
                   -- it was watching. Storing the session id would make the same answer look
                   -- unsatisfied the next time somebody pressed play.
                   CASE WHEN i.ask_again THEN ?::varchar ELSE ?::varchar END,
                   ?::timestamptz
              FROM node_interstitial i
             WHERE i.id = ? AND i.tenant_id = ?
            ON CONFLICT ON CONSTRAINT uq_interstitial_response DO NOTHING
            """, UUID.randomUUID(), tenantId, interstitialId, learnerId, attemptId,
            viewing == null ? ANY_VIEWING : viewing, ANY_VIEWING,
            Timestamp.from(answeredAt), interstitialId, tenantId) > 0;
    }
}
