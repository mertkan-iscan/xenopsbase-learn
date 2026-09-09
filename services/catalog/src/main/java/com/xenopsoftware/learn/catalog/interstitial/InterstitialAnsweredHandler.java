package com.xenopsoftware.learn.catalog.interstitial;

import com.xenopsoftware.learn.catalog.home.HomeVersions;
import com.xenopsoftware.learn.common.messaging.MessageHandler;
import com.xenopsoftware.learn.common.messaging.OutboxMessage;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The only thing that moves a frontier (T-5.4).
 *
 * <p><b>Catalog does not decide that an answer happened and cannot.</b> Assessment observes the
 * attempt and owns the record of it (ADR-0109) — the same division {@code NodeCompletionHandler}
 * has with streaming's completions. What crosses the boundary is an event, and
 * {@code interstitial_response} is the projection of it.
 *
 * <p><b>There is deliberately no endpoint that does this job.</b> A learner who could post "I
 * answered it" would have a one-request path past every blocking interstitial in the product, which
 * is the same hole ADR-0107 closed for completions. The absence is the design; this handler is
 * where the gap it leaves is filled by evidence.
 *
 * <p><b>Written before the event has a publisher</b>, which is worth stating plainly: attempts are
 * T-6.6's (#65), so nothing emits {@code assessment.interstitial.answered} yet. The frontier is the
 * half T-5.4 owes and it is complete and tested — by delivering this event, which is exactly what
 * assessment will do. What waits for the attempt is {@code attempt_id} being non-null, and with it
 * T-5.4's last criterion, that an interstitial's result reaches reporting as an ordinary attempt.
 */
@Component
public class InterstitialAnsweredHandler implements MessageHandler {

    private static final Logger LOG = LoggerFactory.getLogger(InterstitialAnsweredHandler.class);

    private final InterstitialResponses responses;
    private final HomeVersions versions;
    private final JsonMapper json = JsonMapper.builder().build();

    public InterstitialAnsweredHandler(InterstitialResponses responses, HomeVersions versions) {
        this.responses = responses;
        this.versions = versions;
    }

    @Override
    public String subject() {
        return "assessment.interstitial.answered";
    }

    @Override
    public void handle(OutboxMessage message) {
        JsonNode body = json.readTree(message.payload());
        String tenantId = body.get("tenantId").asString();
        UUID interstitialId = UUID.fromString(body.get("interstitialId").asString());
        UUID learnerId = UUID.fromString(body.get("learnerId").asString());
        UUID attemptId = optionalId(body, "attemptId");
        String viewing = body.has("viewing") && !body.get("viewing").isNull()
            ? body.get("viewing").asString() : null;
        // When the learner answered, not when the bus got round to telling us. A blocking
        // interstitial answered before an outage did not become unanswered during it.
        Instant answeredAt = Instant.parse(body.get("answeredAt").asString());

        boolean written = responses.record(tenantId, interstitialId, learnerId, attemptId, viewing,
            answeredAt);

        if (written) {
            // Their frontier moved, so the screen that draws where they are is stale (T-5.8).
            versions.bumpLearner(tenantId, learnerId);
        } else {
            // Either already recorded (a redelivery, which is expected) or the interstitial was
            // removed between the learner answering it and this arriving. Neither is a fault, and
            // a warning for the ordinary case is how a log stops being read.
            LOG.debug("Answer to interstitial {} by {} in {} changed nothing here", interstitialId,
                learnerId, tenantId);
        }
    }

    private static UUID optionalId(JsonNode body, String field) {
        JsonNode value = body.get(field);
        return value == null || value.isNull() ? null : UUID.fromString(value.asString());
    }
}
