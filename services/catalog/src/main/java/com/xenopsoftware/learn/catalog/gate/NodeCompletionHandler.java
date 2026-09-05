package com.xenopsoftware.learn.catalog.gate;

import com.xenopsoftware.learn.common.messaging.MessageHandler;
import com.xenopsoftware.learn.common.messaging.OutboxMessage;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Fills {@code node_completion} from what streaming derived (T-3.7, T-9.8, T-5.3).
 *
 * <p><b>This is the writer the gates shipped without.</b> Until now a gate requiring "complete
 * Module 1" was unreachable for everybody, which was the correct answer for a platform where
 * nobody had finished anything and is the wrong one the moment somebody has.
 *
 * <p><b>Catalog does not decide completion and cannot.</b> The module that observes the evidence
 * owns the record of it (ADR-0109): streaming sees playback and derives coverage (ADR-0107),
 * assessment will do the same from a submitted attempt. What crosses the boundary is an event, so
 * a gate evaluation stays one query against catalog's own table rather than three calls that fail
 * whenever any of them is slow — on the screen a learner looks at most.
 *
 * <p><b>Idempotent by the shape of the row, not by care.</b> The bus is at-least-once, so the same
 * completion will arrive twice sooner or later. The insert is conditional on the unique key rather
 * than guarded by a read: checking "have I recorded this" and then recording it has a window where
 * two deliveries both find nothing and both write, and the database is the only thing that can
 * arbitrate that without one.
 *
 * <p><b>A completion for a node this tenant does not have is dropped, not retried.</b> The node was
 * deleted between the learner finishing it and this arriving, which is ordinary; a failing insert
 * would put a poison message at the head of the queue and stop every other learner's completion
 * behind it.
 */
@Component
public class NodeCompletionHandler implements MessageHandler {

    private static final Logger LOG = LoggerFactory.getLogger(NodeCompletionHandler.class);

    private final JdbcTemplate jdbc;
    private final JsonMapper json = JsonMapper.builder().build();

    public NodeCompletionHandler(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public String subject() {
        return "streaming.node.completed";
    }

    @Override
    public void handle(OutboxMessage message) {
        JsonNode body = json.readTree(message.payload());
        String tenantId = body.get("tenantId").asString();
        UUID learnerId = UUID.fromString(body.get("learnerId").asString());
        UUID nodeId = UUID.fromString(body.get("nodeId").asString());
        Instant completedAt = Instant.parse(body.get("completedAt").asString());

        // The recorded time is when the learner crossed the threshold, not when this was
        // delivered. A completion that sat in a backlog for an hour did not happen an hour late,
        // and a compliance report that said so would be wrong about the only thing it is for.
        int written = jdbc.update("""
            INSERT INTO node_completion (id, tenant_id, learner_id, node_id, state, recorded_at)
            SELECT ?, ?, ?, ?, 'COMPLETED', ?
             WHERE EXISTS (SELECT 1 FROM course_node WHERE id = ? AND tenant_id = ?)
            ON CONFLICT ON CONSTRAINT uq_node_completion DO NOTHING
            """, UUID.randomUUID(), tenantId, learnerId, nodeId, Timestamp.from(completedAt),
            nodeId, tenantId);

        if (written == 0) {
            // Either already recorded (a redelivery, which is expected) or the node is gone.
            // Logged at debug rather than warn for that reason: neither is a fault, and a warning
            // for the ordinary case is how a log stops being read.
            LOG.debug("Completion of node {} by {} in {} changed nothing here", nodeId, learnerId,
                tenantId);
        }
    }
}
