package com.xenopsoftware.learn.catalog.gate;

import com.xenopsoftware.learn.common.messaging.MessageHandler;
import com.xenopsoftware.learn.common.messaging.OutboxMessage;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Fills {@code node_completion} from what streaming DERIVED (T-3.7, T-9.8, T-5.3).
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
 * <p><b>The row shape lives in {@link NodeCompletions}, because there are two of these now.</b>
 * {@code PackageCompletionHandler} is the other, and the two differ in exactly one field — the
 * source — which is the field ADR-0107 says every compliance view has to carry. Keeping the insert
 * in one place is what stops that difference becoming a difference in the {@code ON CONFLICT}
 * clause as well.
 */
@Component
public class NodeCompletionHandler implements MessageHandler {

    /** The one value streaming writes. It measured the coverage itself (ADR-0107). */
    private static final String DERIVED = "DERIVED";

    private final NodeCompletions completions;
    private final JsonMapper json = JsonMapper.builder().build();

    public NodeCompletionHandler(NodeCompletions completions) {
        this.completions = completions;
    }

    @Override
    public String subject() {
        return "streaming.node.completed";
    }

    @Override
    public void handle(OutboxMessage message) {
        JsonNode body = json.readTree(message.payload());
        completions.record(
            body.get("tenantId").asString(),
            UUID.fromString(body.get("learnerId").asString()),
            UUID.fromString(body.get("nodeId").asString()),
            Instant.parse(body.get("completedAt").asString()),
            /*
             * NOT READ FROM THE MESSAGE, even though streaming puts a `source` in it.
             *
             * The subject is the guarantee. Anything arriving on `streaming.node.completed` was
             * measured by streaming against an encoded duration, and that is what makes it
             * DERIVED -- a field in a payload is a claim, and reading provenance out of a claim is
             * how a self-reported completion would eventually arrive labelled as measured.
             */
            DERIVED);
    }
}
