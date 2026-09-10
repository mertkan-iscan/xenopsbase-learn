package com.xenopsoftware.learn.catalog.gate;

import com.xenopsoftware.learn.catalog.home.HomeVersions;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Writing {@code node_completion}, whoever observed the completion (T-5.3, T-4.4).
 *
 * <p><b>Extracted when there were two writers, not before.</b> Streaming derives completions from
 * measured coverage; packaging relays what a SCORM package asserted about itself. Two handlers,
 * two subjects, two sources — and one row shape, one uniqueness rule and one cache invalidation,
 * which is exactly the part that must not be written twice. A second copy of this insert is a
 * second place for the {@code ON CONFLICT} clause to be got subtly wrong.
 *
 * <p><b>Catalog does not decide completion and cannot.</b> The module that observes the evidence
 * owns the record of it (ADR-0109). What crosses the boundary is an event; this is where it lands.
 */
@Component
public class NodeCompletions {

    private static final Logger LOG = LoggerFactory.getLogger(NodeCompletions.class);

    private final JdbcTemplate jdbc;
    private final HomeVersions versions;

    public NodeCompletions(DataSource dataSource, HomeVersions versions) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.versions = versions;
    }

    /**
     * Records that a learner finished a node, if the node is still this tenant's.
     *
     * <p><b>Idempotent by the shape of the row, not by care.</b> The bus is at-least-once, so the
     * same completion will arrive twice sooner or later. The insert is conditional on the unique
     * key rather than guarded by a read: checking "have I recorded this" and then recording it has
     * a window where two deliveries both find nothing and both write, and the database is the only
     * thing that can arbitrate that without one.
     *
     * <p><b>A completion for a node this tenant does not have is dropped, not retried.</b> The node
     * was deleted between the learner finishing it and this arriving, which is ordinary; a failing
     * insert would put a poison message at the head of the queue and stop every other learner's
     * completion behind it.
     *
     * <p><b>The first source to arrive wins, and a later one does not overwrite it.</b>
     * {@code DO NOTHING} rather than an upsert: a measured completion and a self-reported one for
     * the same node are the same fact evidenced twice, and re-stamping the row would move a
     * compliance record's timestamp and its provenance for no reason anybody asked for. Which one
     * arrives first is not something to design around — it is one row either way.
     *
     * @param source {@code DERIVED}, {@code SELF_REPORTED} or {@code MANUAL}. Never inferred here:
     *               only the module that saw the evidence knows what kind it was (ADR-0107)
     * @param completedAt when the learner finished, <b>not</b> when this was delivered. A
     *                    completion that sat in a backlog for an hour did not happen an hour late,
     *                    and a compliance report that said so would be wrong about the only thing
     *                    it is for
     */
    public void record(String tenantId, UUID learnerId, UUID nodeId, Instant completedAt,
            String source) {
        int written = jdbc.update("""
            INSERT INTO node_completion (id, tenant_id, learner_id, node_id, state, source, recorded_at)
            SELECT ?, ?, ?, ?, 'COMPLETED', ?, ?
             WHERE EXISTS (SELECT 1 FROM course_node WHERE id = ? AND tenant_id = ?)
            ON CONFLICT ON CONSTRAINT uq_node_completion DO NOTHING
            """, UUID.randomUUID(), tenantId, learnerId, nodeId, source,
            Timestamp.from(completedAt), nodeId, tenantId);

        // Their home screen said this was still to do; it is not any more (T-5.8). In the same
        // transaction as the row, so a cached screen keyed on the old version stops being
        // addressed the moment this commits.
        versions.bumpLearner(tenantId, learnerId);

        if (written == 0) {
            // Either already recorded (a redelivery, which is expected) or the node is gone.
            // Logged at debug rather than warn for that reason: neither is a fault, and a warning
            // for the ordinary case is how a log stops being read.
            LOG.debug("Completion of node {} by {} in {} ({}) changed nothing here", nodeId,
                learnerId, tenantId, source);
        }
    }
}
