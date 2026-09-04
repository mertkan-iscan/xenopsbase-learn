package com.xenopsoftware.learn.identity.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xenopsoftware.learn.common.tenancy.TenantContext;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The audit mechanism (T-2.2).
 *
 * <p>T-2.2 was written expecting "the existing mechanism"; there was none, so this is it, and
 * E7's audited reads (T-7.10) and T-2.8's visible impersonation extend this rather than
 * inventing a second log.
 *
 * <p>Two properties it must keep. It writes in the caller's transaction — an audit entry that
 * survives a rolled-back change describes something that never happened, and one that rolls back
 * while the change commits is worse. And it records {@code app_user.id}, never a username: an
 * audit trail a profile edit can rewrite is not an audit trail.
 */
@Component
public class AuditLogger {

    private static final Logger LOG = LoggerFactory.getLogger(AuditLogger.class);

    private final JdbcTemplate jdbc;
    private final CurrentUser currentUser;
    private final ObjectMapper json;

    public AuditLogger(DataSource dataSource, CurrentUser currentUser) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.currentUser = currentUser;
        // Its own mapper rather than the application one, deliberately: an audit payload is a
        // record of what happened, and it should not change shape because somebody tuned the
        // API serialisation. Payloads here are plain maps of strings and collections.
        this.json = new ObjectMapper();
    }

    /**
     * Records something that was REFUSED, in its own transaction (T-2.6).
     *
     * <p>A refusal rolls its caller's transaction back, and an audit entry written inside that
     * transaction would roll back with it — so the one record of an attempted escalation would
     * be destroyed by the very refusal that makes it worth keeping. REQUIRES_NEW is what keeps
     * it.
     *
     * <p>And it is best-effort on purpose: if writing the entry fails, the refusal still
     * happens. An audit problem must never turn a denied escalation into an allowed one, so the
     * failure is logged loudly and the original exception is what the caller sees.
     */
    @org.springframework.transaction.annotation.Transactional(
        propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void recordRefusal(String action, String targetType, UUID targetId, Map<String, ?> payload) {
        try {
            record(action, targetType, targetId, payload);
        } catch (RuntimeException e) {
            LOG.error("Could not audit refused {} on {} {} -- the refusal still stands",
                action, targetType, targetId, e);
        }
    }

    /**
     * The same, for a caller that already knows who is acting (T-2.8).
     *
     * <p>Impersonation refuses <em>before</em> a session exists, while the tenant is already the
     * customer's — and asking {@link CurrentUser} there would provision our support engineer into
     * the customer's company as a side effect of refusing them. The actor is therefore passed in,
     * resolved where the caller actually belongs.
     */
    @org.springframework.transaction.annotation.Transactional(
        propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void recordRefusalAs(UUID actorUserId, String action, String targetType, UUID targetId,
            Map<String, ?> payload) {
        try {
            write(actorUserId, action, targetType, targetId, payload);
        } catch (RuntimeException e) {
            LOG.error("Could not audit refused {} on {} {} -- the refusal still stands",
                action, targetType, targetId, e);
        }
    }

    public void record(String action, String targetType, UUID targetId, Map<String, ?> payload) {
        write(currentUser.requireId(), action, targetType, targetId, payload);
    }

    /**
     * One insert, and the two identities an impersonated action has (T-2.8).
     *
     * <p>{@code actor_user_id} keeps its meaning under a session: the support engineer, because
     * they are who caused this. {@code impersonated_user_id} says whose face they were wearing,
     * and the session id ties every entry back to the reason recorded when it opened. A customer
     * asking "did I do that?" gets an answer, and so does the engineer.
     */
    private void write(UUID actorUserId, String action, String targetType, UUID targetId,
            Map<String, ?> payload) {
        String body;
        try {
            body = json.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            // Never silently drop the entry: an unserialisable payload is a bug in the caller,
            // and losing the audit record would hide both it and the action.
            throw new IllegalArgumentException("Audit payload for " + action + " is not serialisable", e);
        }
        java.util.Optional<com.xenopsoftware.learn.identity.impersonation.Impersonation> session =
            com.xenopsoftware.learn.identity.impersonation.ImpersonationContext.current();
        jdbc.update("""
            INSERT INTO audit_log (id, tenant_id, actor_user_id, action, target_type, target_id,
                                   payload, created_at, impersonated_user_id, impersonation_session_id)
            VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, now(), ?, ?)
            """, UUID.randomUUID(), TenantContext.require(), actorUserId,
            action, targetType, targetId, body,
            session.map(s -> s.impersonatedUserId()).orElse(null),
            session.map(s -> s.sessionId()).orElse(null));
    }
}
