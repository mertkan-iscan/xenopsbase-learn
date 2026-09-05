package com.xenopsoftware.learn.catalog.home;

import com.xenopsoftware.learn.common.messaging.MessageHandler;
import com.xenopsoftware.learn.common.messaging.OutboxMessage;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keeps {@code node_progress} current from streaming's events (T-5.8, T-3.7).
 *
 * <p><b>The percentage and the resume point on the learner home screen come from here</b>, which is
 * to say they come from the same derivation reporting will use — not from a second calculation over
 * completions, which is what a screen would otherwise be tempted into. The module that observes the
 * evidence owns the record of it (ADR-0109); this is the copy that lets a screen be drawn without
 * asking it.
 *
 * <p><b>The event is throttled at the source</b> (one per ten per cent, or one per five minutes),
 * because progress moves every ten seconds per learner and an event per heartbeat would be ~500
 * outbox rows a second at 5,000 concurrent learners. So this table can be a few minutes behind the
 * truth, deliberately, and the player never reads it: it asks {@code streaming} for the row.
 *
 * <p><b>Idempotent by the shape of the message and by the clock on it.</b> Each event carries the
 * learner's current position rather than a delta, so applying it twice leaves the same row; and the
 * upsert refuses to go backwards on {@code updatedAt}, because an at-least-once bus says nothing
 * about the order two deliveries arrive in.
 */
@Component
public class NodeProgressHandler implements MessageHandler {

    private final NodeProgressProjection progress;
    private final HomeVersions versions;
    private final JsonMapper json = JsonMapper.builder().build();

    public NodeProgressHandler(NodeProgressProjection progress, HomeVersions versions) {
        this.progress = progress;
        this.versions = versions;
    }

    @Override
    public String subject() {
        return "streaming.node.progress";
    }

    @Override
    public void handle(OutboxMessage message) {
        JsonNode body = json.readTree(message.payload());
        String tenantId = body.get("tenantId").asString();
        UUID learnerId = UUID.fromString(body.get("learnerId").asString());

        progress.put(tenantId, learnerId, UUID.fromString(body.get("nodeId").asString()),
            body.get("percent").asInt(), body.get("resumeSecond").asInt(),
            body.get("completed").asBoolean(),
            Instant.parse(body.get("updatedAt").asString()));

        // In the same transaction as the row it describes: a cached home screen keyed on the old
        // version stops being addressed the moment this commits, and a rollback leaves both the
        // row and the key as they were.
        versions.bumpLearner(tenantId, learnerId);
    }
}
