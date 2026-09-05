package com.xenopsoftware.learn.catalog.home;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * How far a learner has got, copied from streaming's derivation (T-5.8, T-3.7).
 *
 * <p><b>A copy, and never a second calculation.</b> {@code streaming} owns watched intervals and
 * the completion derived from them (ADR-0107); this module may not read its tables (ADR-0109) and
 * must not work the number out again from anything else, because two answers to "how far are they"
 * is one that disagrees with the compliance report. The number arrives as an event.
 *
 * <p>Read in one query for the whole screen rather than one per node, which is the difference
 * between a home screen that is fast for everybody and one that is fast for the person who has
 * been assigned three things.
 */
@Component
public class NodeProgressProjection {

    private final JdbcTemplate jdbc;

    public NodeProgressProjection(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /**
     * What a screen draws for one node.
     *
     * @param percent      coverage of the item's extent, as streaming derived it
     * @param resumeSecond the furthest second reached — where playback picks up
     */
    public record Progress(int percent, int resumeSecond, boolean completed, Instant updatedAt) {}

    /** Everything known about this learner, in one query however many nodes they can see. */
    public Map<UUID, Progress> of(String tenantId, UUID learnerId) {
        Map<UUID, Progress> byNode = new HashMap<>();
        jdbc.query("""
            SELECT node_id, percent, resume_second, completed, updated_at
              FROM node_progress WHERE tenant_id = ? AND learner_id = ?
            """, rows -> {
                byNode.put(rows.getObject("node_id", UUID.class),
                    new Progress(rows.getInt("percent"), rows.getInt("resume_second"),
                        rows.getBoolean("completed"), rows.getTimestamp("updated_at").toInstant()));
            }, tenantId, learnerId);
        return byNode;
    }

    /** The same, narrowed to a set of nodes. Used by the screens that show one course. */
    public Map<UUID, Progress> of(String tenantId, UUID learnerId, Collection<UUID> nodeIds) {
        if (nodeIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Progress> all = of(tenantId, learnerId);
        all.keySet().retainAll(nodeIds);
        return all;
    }

    /**
     * Applies what streaming said.
     *
     * <p>Idempotent by the shape of the message — it carries the learner's current position rather
     * than a delta — and it refuses to go backwards on {@code updated_at}, because an at-least-once
     * bus says nothing about order and a redelivered older event would drag a progress bar back.
     */
    public void put(String tenantId, UUID learnerId, UUID nodeId, int percent, int resumeSecond,
            boolean completed, Instant updatedAt) {
        jdbc.update("""
            INSERT INTO node_progress (tenant_id, learner_id, node_id, percent, resume_second,
                                       completed, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (tenant_id, learner_id, node_id) DO UPDATE
               SET percent = EXCLUDED.percent, resume_second = EXCLUDED.resume_second,
                   completed = EXCLUDED.completed, updated_at = EXCLUDED.updated_at
             WHERE node_progress.updated_at <= EXCLUDED.updated_at
            """, tenantId, learnerId, nodeId, percent, resumeSecond, completed,
            java.sql.Timestamp.from(updatedAt));
    }
}
