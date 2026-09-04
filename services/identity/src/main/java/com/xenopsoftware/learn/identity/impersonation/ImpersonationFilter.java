package com.xenopsoftware.learn.identity.impersonation;

import com.xenopsoftware.learn.common.tenancy.AccountStatus;
import com.xenopsoftware.learn.common.tenancy.TenantContext;
import com.xenopsoftware.learn.common.tenancy.TenantFilter;
import com.xenopsoftware.learn.identity.audit.AuditLogger;
import com.xenopsoftware.learn.identity.tenant.EffectiveStatus;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Turns a session id on a request into acting as somebody else — or refuses (T-2.8).
 *
 * <p><b>The header is a reference, not a claim.</b> It carries a session id and nothing else: no
 * tenant, no user, no capability. Everything that decides what the request may do is read from
 * the row behind that id, which only this service writes. That is the same rule
 * {@code TenantContext} states for the tenant, and it has to be, because a header saying
 * {@code X-Impersonate-Tenant: acme} would be a cross-tenant read with extra steps.
 *
 * <p><b>Every condition is re-checked here, per request, not trusted from the start.</b> A
 * session is minutes long and the account it entered can be suspended, the person deactivated,
 * the engineer sacked. Checking once at the start would make the session a key that outlives its
 * own justification.
 *
 * <p>Ordered immediately after {@link TenantFilter} and before everything else, because the
 * rebound tenant is what the rest of the chain must see: the shared status gate then judges the
 * customer's account rather than the platform's, and every read below this point is filtered by
 * the customer's discriminator.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 99)
public class ImpersonationFilter extends OncePerRequestFilter {

    /** The session id, as issued by {@code POST /api/v1/platform/impersonations}. */
    public static final String HEADER = "X-Impersonate-Session";

    private static final Logger LOG = LoggerFactory.getLogger(ImpersonationFilter.class);

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
        new com.fasterxml.jackson.databind.ObjectMapper();

    private final ImpersonationSessions sessions;
    private final EffectiveStatus effective;
    private final AuditLogger audit;
    private final JdbcTemplate jdbc;

    public ImpersonationFilter(ImpersonationSessions sessions, EffectiveStatus effective,
            AuditLogger audit, DataSource dataSource) {
        this.sessions = sessions;
        this.effective = effective;
        this.audit = audit;
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HEADER);
        if (header == null || header.isBlank()) {
            chain.doFilter(request, response);
            return;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            // A session id on an unauthenticated request. Ignored rather than refused: the
            // security chain is about to answer 401, which is the true and more useful answer.
            chain.doFilter(request, response);
            return;
        }

        Optional<Impersonation> session = resolve(token.getToken(), header);
        if (session.isEmpty()) {
            refuse(response, "IMPERSONATION_INVALID",
                "This impersonation session is not open. Start a new one.");
            return;
        }
        Impersonation active = session.get();

        AccountStatus status = accountStatus(active);
        if (!status.permitsReads()) {
            // The account went away underneath a live session. Closed rather than merely
            // refused, so the record shows it stopped and why, and so the next request does not
            // repeat the check.
            sessions.close(active.sessionId(), ImpersonationSessions.ENDED_ACCOUNT_UNAVAILABLE);
            refuse(response, "IMPERSONATION_ACCOUNT_UNAVAILABLE",
                "The account this session entered is " + status + "; the session has been closed.");
            return;
        }
        if (!active.permits(request.getMethod()) || (!status.permitsWrites()
                && !Impersonation.isRead(request.getMethod()))) {
            // Read-only is the default, and a refused write is worth a row: an attempt to change
            // a customer's data from a session that may not is exactly what the customer should
            // be able to see afterwards.
            recordRefusedWrite(active, request);
            refuse(response, "IMPERSONATION_READ_ONLY",
                "This impersonation session may only read. Writing requires a session started "
                + "with support:impersonate_write.");
            return;
        }

        // The tenant moves for the length of this request, through TenantContext's own binding
        // helper -- nothing outside common.tenancy sets the tenant directly, and the ArchUnit
        // rule that says so is what keeps a "just for support" header from becoming the way
        // anybody selects a tenant.
        try {
            TenantContext.callWithUnchecked(active.tenantId(), () ->
                ImpersonationContext.callWith(active, () -> {
                    try {
                        chain.doFilter(request, response);
                        return null;
                    } catch (IOException | ServletException e) {
                        throw new DispatchFailure(e);
                    }
                }));
        } catch (DispatchFailure e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw (ServletException) e.getCause();
        }
    }

    /** The live session for this caller, if the header names one and it is theirs. */
    private Optional<Impersonation> resolve(Jwt caller, String header) {
        UUID sessionId;
        try {
            sessionId = UUID.fromString(header.strip());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (!TenantFilter.PLATFORM.equals(caller.getClaimAsString(TenantFilter.SIDE_CLAIM))) {
            // Only platform staff impersonate. A tenant-side caller presenting a session id is
            // either confused or probing, and both deserve the same nothing.
            LOG.warn("Tenant-side subject {} presented an impersonation session id", caller.getSubject());
            return Optional.empty();
        }
        List<UUID> actor = jdbc.queryForList(
            "SELECT id FROM app_user WHERE tenant_id = ? AND idp_sub = ?", UUID.class,
            TenantFilter.PLATFORM_TENANT, caller.getSubject());
        if (actor.isEmpty()) {
            return Optional.empty();
        }
        // The session must belong to THIS caller. Otherwise a leaked id would be a bearer token
        // for somebody else's session, and the audit trail would name the wrong engineer.
        return sessions.live(sessionId, actor.getFirst());
    }

    /**
     * The whole status chain for the impersonated person, read from the database rather than the
     * published copy. The shared gate in front of this is the fast path for ordinary traffic; a
     * session that borrows a customer's identity is rare enough to pay for the authoritative
     * answer, and stale-by-a-request is not a property worth having here.
     */
    private AccountStatus accountStatus(Impersonation session) {
        List<String> sub = jdbc.queryForList("SELECT idp_sub FROM app_user WHERE tenant_id = ? AND id = ?",
            String.class, session.tenantId(), session.impersonatedUserId());
        if (sub.isEmpty() || sub.getFirst() == null) {
            return AccountStatus.SUSPENDED;
        }
        return effective.ofUser(session.tenantId(), sub.getFirst());
    }

    private void recordRefusedWrite(Impersonation session, HttpServletRequest request) {
        TenantContext.callWithUnchecked(session.tenantId(), () ->
            ImpersonationContext.callWith(session, () -> {
                audit.recordRefusal("impersonation.write.refused", "user",
                    session.impersonatedUserId(), Map.of(
                        "method", request.getMethod(),
                        "path", request.getRequestURI()));
                return null;
            }));
    }

    private static void refuse(HttpServletResponse response, String reason, String message)
            throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        // A machine-readable reason beside the sentence, the shape DeactivatedUserFilter set:
        // a console can say something true about why it is stuck instead of showing a 403.
        response.getWriter().write(JSON.writeValueAsString(Map.of("reason", reason, "message", message)));
    }

    /**
     * Never on the error dispatch, for {@code DeactivatedUserFilter}'s reason: a refusal written
     * here re-enters the chain through {@code /error}, and filtering that dispatch too would
     * replace every error body with this one.
     */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return true;
    }

    /** Carries a checked servlet failure out through the tenant-binding helper and no further. */
    private static final class DispatchFailure extends RuntimeException {
        DispatchFailure(Exception cause) {
            super(cause);
        }
    }
}
