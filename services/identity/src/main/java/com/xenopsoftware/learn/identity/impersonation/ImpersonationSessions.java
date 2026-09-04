package com.xenopsoftware.learn.identity.impersonation;

import com.xenopsoftware.learn.common.tenancy.AccountStatus;
import com.xenopsoftware.learn.common.tenancy.TenantContext;
import com.xenopsoftware.learn.common.tenancy.TenantFilter;
import com.xenopsoftware.learn.identity.audit.AuditLogger;
import com.xenopsoftware.learn.identity.audit.CurrentUser;
import com.xenopsoftware.learn.identity.tenant.EffectiveStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Starting, ending and reading impersonation sessions (T-2.8).
 *
 * <p>Plain SQL naming its tenant, for the reason {@code EffectiveStatus} gives: every read here
 * crosses the boundary on purpose. A support engineer is bound to the platform tenant when they
 * start a session, and the row they create belongs to the customer — a session-scoped
 * discriminator cannot express that, and making it try would either fail or write the row where
 * the customer can never see it.
 *
 * <p><b>Nothing here is a shortcut into a customer's data.</b> Starting a session is one insert
 * and one audit entry; the reach it grants is decided per request by {@link ImpersonationFilter},
 * re-checked every time, and bounded by an expiry stored at the start.
 */
@Service
public class ImpersonationSessions {

    /** What ended a session, in {@code ended_reason}. Short codes, because a UI reads them. */
    public static final String ENDED_BY_ACTOR = "ENDED_BY_ACTOR";
    public static final String ENDED_EXPIRED = "EXPIRED";
    public static final String ENDED_ACCOUNT_UNAVAILABLE = "ACCOUNT_UNAVAILABLE";

    private final JdbcTemplate jdbc;
    private final EffectiveStatus effective;
    private final AuditLogger audit;
    private final CurrentUser currentUser;
    private final ImpersonationProperties properties;

    public ImpersonationSessions(DataSource dataSource, EffectiveStatus effective,
            AuditLogger audit, CurrentUser currentUser, ImpersonationProperties properties) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.effective = effective;
        this.audit = audit;
        this.currentUser = currentUser;
        this.properties = properties;
    }

    /**
     * What a session looks like to whoever is allowed to read it — the customer's administrators
     * and our own staff see the same fields, deliberately. A record the customer can only see
     * half of is a record they cannot check.
     */
    public record SessionView(UUID id, String tenantId, UUID actorUserId, String actorName,
                              String actorEmail, UUID impersonatedUserId, String impersonatedName,
                              String reason, boolean writable, Instant startedAt, Instant expiresAt,
                              Instant endedAt, String endedReason) {}

    /**
     * Opens a session. The caller is platform staff bound to their own tenant; everything this
     * touches names the customer's tenant explicitly.
     *
     * <p>The refusals matter as much as the success, and each one is audited <em>into the
     * customer's tenant</em>: an attempt to enter a suspended account is exactly the event a
     * customer is entitled to see, and recording it only in our own tenant would leave the
     * customer-visible log describing successes only.
     */
    @Transactional
    public Impersonation start(String tenantId, UUID userId, String reason, boolean writable) {
        ImpersonationContext.current().ifPresent(active -> {
            // Nesting would make "who is acting" a stack, and an audit trail cannot answer a
            // stack. It is also never necessary: end the session and start another.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Already impersonating under session " + active.sessionId());
        });
        if (TenantFilter.PLATFORM_TENANT.equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "The platform tenant is not a customer; impersonating inside it would only "
                + "disguise one of our own staff as another");
        }
        String trimmed = reason == null ? "" : reason.strip();
        if (trimmed.length() < properties.minReason()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "A reason of at least " + properties.minReason() + " characters is required, and "
                + "the customer whose account you are entering is the one who reads it");
        }

        UUID actorUserId = currentUser.requireId();
        TargetUser subject = target(tenantId, userId);
        if (subject == null) {
            // 404 rather than 403: the id is either not in this tenant or not a user at all, and
            // saying which would make this endpoint an id oracle across every customer.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such user in " + tenantId);
        }
        if (actorUserId.equals(subject.id())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That is you");
        }
        if (subject.identityLink() == null) {
            // Nobody has ever signed in as this row: an open invitation (T-1.9). There is no
            // view to reproduce, and a session would produce audit entries attributed to an
            // identity that does not exist yet.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "This person has never signed in, so there is no session to reproduce");
        }

        AccountStatus status = effective.ofUser(tenantId, subject.identityLink());
        if (!status.permitsReads()) {
            refuse(tenantId, actorUserId, subject.id(), "suspended", status, trimmed, writable);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                status.reasonCode() + ": impersonation cannot enter a " + status + " account");
        }
        if (writable && !status.permitsWrites()) {
            refuse(tenantId, actorUserId, subject.id(), "read-only account", status, trimmed, true);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                status.reasonCode() + ": this account is " + status + ", so no session in it may write");
        }

        Instant startedAt = Instant.now();
        Impersonation session = new Impersonation(UUID.randomUUID(), tenantId, actorUserId,
            subject.id(), writable, startedAt.plus(properties.maxDuration()));
        jdbc.update("""
            INSERT INTO impersonation_session (id, tenant_id, actor_user_id, impersonated_user_id,
                                               reason, writable, started_at, expires_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """, session.sessionId(), tenantId, actorUserId, subject.id(), trimmed, writable,
            java.sql.Timestamp.from(startedAt), java.sql.Timestamp.from(session.expiresAt()));

        // Written under the session, in the customer's tenant: the first entry a customer sees
        // when they ask what happened, carrying both identities like every entry after it.
        TenantContext.callWithUnchecked(tenantId, () -> ImpersonationContext.callWith(session, () -> {
            audit.record("impersonation.start", "user", subject.id(), Map.of(
                "reason", trimmed,
                "writable", String.valueOf(writable),
                "expiresAt", session.expiresAt().toString()));
            return null;
        }));
        return session;
    }

    /** Ends a session early. Idempotent: ending an ended session changes nothing. */
    @Transactional
    public void end(UUID sessionId, UUID actorUserId, String endedReason) {
        Map<String, Object> row = row(sessionId);
        if (row == null || !actorUserId.equals(row.get("actor_user_id"))) {
            // Another engineer's session is not yours to end, and not yours to learn about.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such session");
        }
        if (row.get("ended_at") != null) {
            return;
        }
        String tenantId = (String) row.get("tenant_id");
        UUID impersonated = (UUID) row.get("impersonated_user_id");
        close(sessionId, endedReason);
        Impersonation session = new Impersonation(sessionId, tenantId, actorUserId, impersonated,
            false, Instant.now());
        TenantContext.callWithUnchecked(tenantId, () -> ImpersonationContext.callWith(session, () -> {
            audit.record("impersonation.end", "user", impersonated,
                Map.of("endedReason", endedReason));
            return null;
        }));
    }

    /** Marks a session closed. Its own statement, because three call sites end a session. */
    public void close(UUID sessionId, String endedReason) {
        jdbc.update("""
            UPDATE impersonation_session SET ended_at = now(), ended_reason = ?
             WHERE id = ? AND ended_at IS NULL
            """, endedReason, sessionId);
    }

    /**
     * The session this request may act under, or empty — checked on every request rather than
     * trusted from the start, because a session outlives the conditions it was opened under.
     */
    public Optional<Impersonation> live(UUID sessionId, UUID actorUserId) {
        Map<String, Object> row = row(sessionId);
        if (row == null || row.get("ended_at") != null || !actorUserId.equals(row.get("actor_user_id"))) {
            return Optional.empty();
        }
        Instant expiresAt = ((java.sql.Timestamp) row.get("expires_at")).toInstant();
        if (!expiresAt.isAfter(Instant.now())) {
            // Closed by the first request that notices, so the row says when it really stopped
            // being usable instead of reading as open forever. Correctness needs no sweeper:
            // the expiry is checked here, on the path that would have used it.
            close(sessionId, ENDED_EXPIRED);
            return Optional.empty();
        }
        return Optional.of(new Impersonation(sessionId, (String) row.get("tenant_id"), actorUserId,
            (UUID) row.get("impersonated_user_id"), (Boolean) row.get("writable"), expiresAt));
    }

    /** Every session in one company, newest first — what a tenant admin's screen reads. */
    public List<SessionView> forTenant(String tenantId) {
        return jdbc.query(SELECT_VIEW + " WHERE s.tenant_id = ? ORDER BY s.started_at DESC",
            VIEW, tenantId);
    }

    /** Every session one support engineer has opened, newest first. */
    public List<SessionView> byActor(UUID actorUserId) {
        return jdbc.query(SELECT_VIEW + " WHERE s.actor_user_id = ? ORDER BY s.started_at DESC",
            VIEW, actorUserId);
    }

    private void refuse(String tenantId, UUID actorUserId, UUID userId, String because,
            AccountStatus status, String reason, boolean writable) {
        // recordRefusalAs, not record: the refusal throws, which rolls this transaction back,
        // and an entry written inside it would be destroyed by the very refusal worth keeping
        // (T-2.6 met this first). The actor is passed explicitly because the tenant is about to
        // be the customer's, and resolving "who is calling" there would provision our support
        // engineer into the customer's company as a side effect of being refused.
        TenantContext.callWithUnchecked(tenantId, () -> {
            audit.recordRefusalAs(actorUserId, "impersonation.refused", "user", userId, Map.of(
                "because", because,
                "status", status.name(),
                "reason", reason,
                "writable", String.valueOf(writable)));
            return null;
        });
    }

    /**
     * The person about to be impersonated. {@code identityLink} is their {@code app_user.idp_sub},
     * read in passing to ask the status chain about them and never stored anywhere — named for
     * what it is rather than after the column, because a field shaped like a sub outside
     * {@code identity.user} is the copy ADR-0104 forbids, and the ArchUnit rule cannot tell a
     * held reference from a borrowed one.
     */
    private record TargetUser(UUID id, String identityLink) {}

    private TargetUser target(String tenantId, UUID userId) {
        return jdbc.query("""
            SELECT id, idp_sub FROM app_user WHERE tenant_id = ? AND id = ?
            """, rows -> rows.next()
                ? new TargetUser(rows.getObject(1, UUID.class), rows.getString(2))
                : null,
            tenantId, userId);
    }

    private Map<String, Object> row(UUID sessionId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT tenant_id, actor_user_id, impersonated_user_id, writable, expires_at, ended_at
              FROM impersonation_session WHERE id = ?
            """, sessionId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /**
     * The actor's name and email are joined in on purpose. "By whom" is one of the four things
     * T-2.8 promises a customer can see, and the actor lives in OUR tenant — a bare id the
     * customer cannot resolve would satisfy the column and not the promise.
     */
    private static final String SELECT_VIEW = """
        SELECT s.id, s.tenant_id, s.actor_user_id, a.display_name AS actor_name, a.email AS actor_email,
               s.impersonated_user_id, u.display_name AS impersonated_name, s.reason, s.writable,
               s.started_at, s.expires_at, s.ended_at, s.ended_reason
          FROM impersonation_session s
          JOIN app_user a ON a.id = s.actor_user_id
          JOIN app_user u ON u.id = s.impersonated_user_id
        """;

    private static final RowMapper<SessionView> VIEW = (rows, index) -> new SessionView(
        rows.getObject("id", UUID.class),
        rows.getString("tenant_id"),
        rows.getObject("actor_user_id", UUID.class),
        rows.getString("actor_name"),
        rows.getString("actor_email"),
        rows.getObject("impersonated_user_id", UUID.class),
        rows.getString("impersonated_name"),
        rows.getString("reason"),
        rows.getBoolean("writable"),
        rows.getTimestamp("started_at").toInstant(),
        rows.getTimestamp("expires_at").toInstant(),
        rows.getTimestamp("ended_at") == null ? null : rows.getTimestamp("ended_at").toInstant(),
        rows.getString("ended_reason"));
}
