package com.xenopsoftware.learn.identity.user;

import com.xenopsoftware.learn.common.messaging.Outbox;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Announces who somebody is, for the services that have to reach them (T-5.6, T-9.8).
 *
 * <p><b>What catalog needs and cannot ask for.</b> A deadline expires when the day ends where the
 * learner is, and a reminder has to arrive at an address — both facts belong to identity
 * (ADR-0104), and catalog must not read this schema to find them (ADR-0109). So they travel as an
 * event, like group reach beside it.
 *
 * <p><b>The whole profile, not a delta.</b> Every message carries the person's current timezone,
 * address and name, so applying it is "make the row equal this" and applying it twice is applying
 * it once — the idempotency an at-least-once bus requires, achieved by the shape of the message
 * rather than by every consumer being careful. It also carries {@code updatedAt}, so a consumer can
 * refuse to go backwards when a duplicate of an older message overtakes a newer one.
 *
 * <p><b>Erasure is its own type.</b> {@link #FORGOTTEN} means remove the row, which is a different
 * instruction from "here is their profile" and must not be expressed as a profile full of nulls —
 * a consumer would happily store that and keep the person forever.
 *
 * <p>Written to the outbox inside the caller's transaction, so a change that rolls back announces
 * nothing.
 */
@Component
public class UserProfilePublisher {

    /** What a consumer switches on. Stable forever: it outlives any class name. */
    public static final String CHANGED = "user.profile.changed";

    /** The person is gone; forget them. */
    public static final String FORGOTTEN = "user.forgotten";

    /** Under identity's stream (see {@code Streams}). */
    public static final String SUBJECT = "identity.user.profile";

    private final Outbox outbox;
    private final JsonMapper json = JsonMapper.builder().build();

    public UserProfilePublisher(Outbox outbox) {
        this.outbox = outbox;
    }

    /** Announces this person's current profile. Call it inside the transaction that changed it. */
    public void announce(AppUser user) {
        Map<String, Object> body = new HashMap<>();
        body.put("tenantId", user.getTenantId());
        body.put("userId", user.getId().toString());
        body.put("email", user.getEmail());
        body.put("displayName", user.getDisplayName());
        body.put("timeZone", user.getTimeZone());
        /*
         * LANGUAGE TRAVELS AND THEME DOES NOT, and the line between them is whether anything
         * outside a browser has to act on it (T-10.9).
         *
         * A reminder is written by a scheduled job in another service and arrives in somebody's
         * inbox; sending it in a language they do not read is the same failure as sending it at
         * the wrong hour, which is why `timeZone` is already here. Null still means "has not told
         * us" on the far side, so a consumer picks its own fallback rather than being handed one.
         *
         * A palette is rendered by a browser that has already asked /api/v1/me. Putting it on the
         * bus would be a fact nothing consumes, copied into tables that would then be stale --
         * exactly what ADR-0109 keeps out of these messages.
         */
        body.put("language", user.getLanguage());
        body.put("updatedAt", Instant.now().toString());
        outbox.publish(SUBJECT, CHANGED, json.writeValueAsString(body));
    }

    /** Announces that a person should be forgotten everywhere. */
    public void announceForgotten(String tenantId, UUID userId) {
        outbox.publish(tenantId, SUBJECT, FORGOTTEN, json.writeValueAsString(Map.of(
            "tenantId", tenantId,
            "userId", userId.toString(),
            "updatedAt", Instant.now().toString())));
    }
}
