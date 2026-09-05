package com.xenopsoftware.learn.catalog.assign;

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
 * Keeps {@code learner_profile} current from identity's events (T-5.6).
 *
 * <p>The timezone half of it is what makes T-5.6's last criterion true rather than aspirational: a
 * deadline expires when the day ends where the LEARNER is, and catalog can only know that if
 * identity tells it.
 *
 * <p><b>Idempotent twice over.</b> The message carries the person's whole profile, so applying it
 * is "make the row equal this" and doing that twice is doing it once. And the upsert refuses to go
 * backwards in time, so a duplicate of an older message that overtakes a newer one — which
 * at-least-once delivery permits — cannot revert a timezone somebody just corrected.
 */
@Component
public class LearnerProfileHandler implements MessageHandler {

    private static final Logger LOG = LoggerFactory.getLogger(LearnerProfileHandler.class);

    /** What identity publishes when a person is erased rather than changed. */
    static final String FORGOTTEN = "user.forgotten";

    private final LearnerProfiles profiles;
    private final JsonMapper json = JsonMapper.builder().build();

    public LearnerProfileHandler(LearnerProfiles profiles) {
        this.profiles = profiles;
    }

    @Override
    public String subject() {
        return "identity.user.profile";
    }

    @Override
    public void handle(OutboxMessage message) {
        JsonNode body = json.readTree(message.payload());
        String tenantId = body.get("tenantId").asString();
        UUID learnerId = UUID.fromString(body.get("userId").asString());

        if (FORGOTTEN.equals(message.type())) {
            profiles.remove(tenantId, learnerId);
            LOG.debug("Forgot profile for {} in {}", learnerId, tenantId);
            return;
        }
        profiles.put(tenantId, learnerId, text(body, "timeZone"), text(body, "email"),
            text(body, "displayName"), Instant.parse(body.get("updatedAt").asString()));
        LOG.debug("Profile for {} in {} updated", learnerId, tenantId);
    }

    /** Null rather than the string "null" — an absent timezone is a state, not a value. */
    private static String text(JsonNode body, String field) {
        JsonNode value = body.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }
}
