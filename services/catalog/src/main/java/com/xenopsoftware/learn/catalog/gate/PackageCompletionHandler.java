package com.xenopsoftware.learn.catalog.gate;

import com.xenopsoftware.learn.common.messaging.MessageHandler;
import com.xenopsoftware.learn.common.messaging.OutboxMessage;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Fills {@code node_completion} from what a SCORM or cmi5 package SAID ABOUT ITSELF (T-4.4,
 * ADR-0107).
 *
 * <p><b>A separate handler from streaming's, and the difference is one word in one column.</b>
 * That word is why the two are not one class with a switch: {@code SELF_REPORTED} is not a variant
 * of {@code DERIVED}, it is a weaker kind of evidence, and the subject a message arrives on is the
 * only thing that can be trusted to say which. Reading a {@code source} field out of the payload
 * would make provenance a claim the sender makes, which is the one thing it must not be.
 *
 * <h2>What this is trusting, said plainly</h2>
 *
 * <p>A SCORM package reports {@code cmi.completion_status} and the standard's contract is that an
 * LMS believes it. The package's interior is an opaque iframe on another origin (ADR-0105) and
 * there is nothing to measure coverage of, so there is no derivation available here — this is the
 * exception ADR-0107 carves out, not a corner cut.
 *
 * <p>The relay runs in the learner's own browser, so a learner with developer tools can assert
 * their own completion. That is the same bar ADR-0107 sets everywhere ("a bored learner with
 * developer tools, not a paid attacker") and the same one the standard sets: any conformant LMS
 * believes the package, and the package runs on the learner's machine. <b>What the platform owes
 * in return is that every report says which kind of evidence this was</b>, which is what the
 * {@code source} column is for, and what {@code V10__completion_source.sql} exists to make
 * possible.
 *
 * <p><b>What is still missing, and is missing everywhere.</b> Nothing here checks that the learner
 * was assigned the node — the same open gap as {@code UnassignedContent} in streaming and the
 * {@code NotEnforcedYet} banner in the console (T-5.5, T-9.11). The node has to exist and belong
 * to the tenant, which {@link NodeCompletions} enforces; whether it was theirs to do is a question
 * this platform does not yet ask anywhere.
 */
@Component
public class PackageCompletionHandler implements MessageHandler {

    /** A package or a cmi5 statement said so. Never measured here (ADR-0107). */
    private static final String SELF_REPORTED = "SELF_REPORTED";

    private final NodeCompletions completions;
    private final JsonMapper json = JsonMapper.builder().build();

    public PackageCompletionHandler(NodeCompletions completions) {
        this.completions = completions;
    }

    @Override
    public String subject() {
        return "packaging.node.completed";
    }

    @Override
    public void handle(OutboxMessage message) {
        JsonNode body = json.readTree(message.payload());
        completions.record(
            body.get("tenantId").asString(),
            UUID.fromString(body.get("learnerId").asString()),
            UUID.fromString(body.get("nodeId").asString()),
            Instant.parse(body.get("completedAt").asString()),
            SELF_REPORTED);
    }
}
