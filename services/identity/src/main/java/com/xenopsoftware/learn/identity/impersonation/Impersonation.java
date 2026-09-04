package com.xenopsoftware.learn.identity.impersonation;

import java.time.Instant;
import java.util.UUID;

/**
 * One live impersonation session, as the request needs it (T-2.8).
 *
 * <p>Two identities, and keeping them apart is the entire point. The <b>actor</b> is the support
 * engineer: they caused everything that happens, so they are what {@code audit_log.actor_user_id}
 * records, and nothing done here is ever attributed to the customer. The <b>impersonated</b> user
 * is whose view is being reproduced: their grants decide what is permitted, because a session
 * that could do more than the person it is imitating is not reproducing anything.
 *
 * <p>{@code writable} travels on the session rather than being re-derived per request, so what
 * this session was allowed to do stays answerable after the grant that allowed it is revoked.
 */
public record Impersonation(UUID sessionId, String tenantId, UUID actorUserId,
                            UUID impersonatedUserId, boolean writable, Instant expiresAt) {

    /** Whether the request method may change anything, given what this session was granted. */
    public boolean permits(String httpMethod) {
        return writable || isRead(httpMethod);
    }

    /**
     * Safe methods, by the HTTP definition rather than by a list of our own endpoints. An
     * endpoint added later is covered without anybody remembering this class exists — and the
     * failure direction of getting it wrong is a refusal, not an unrecorded write.
     */
    public static boolean isRead(String httpMethod) {
        return "GET".equals(httpMethod) || "HEAD".equals(httpMethod) || "OPTIONS".equals(httpMethod);
    }
}
