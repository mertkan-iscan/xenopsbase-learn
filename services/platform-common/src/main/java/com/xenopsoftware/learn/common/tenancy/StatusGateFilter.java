package com.xenopsoftware.learn.common.tenancy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * A suspended company stops here (T-1.4).
 *
 * <p>Runs after the tenant is bound, so it knows whose status to ask about, and before any
 * handler, so a suspended caller never reaches one. What it refuses depends on the status: a
 * READ_ONLY account keeps its reads and loses its writes, a SUSPENDED one loses both.
 *
 * <h2>The timing property, which is the whole point of the issue</h2>
 *
 * Playback tokens are short-lived and minted per request (ADR-0101), so the bound on how long a
 * suspended company keeps watching is <b>the token TTL and nothing else</b> — an issued token
 * cannot be recalled from Cloudflare's edge. This filter is what makes the next mint fail;
 * T-3.4's ceiling of 30 minutes is what bounds the one already out there.
 *
 * <p>This is a fast path and not the boundary. It can be stale in the permissive direction for
 * the length of one request, which is why the module owning the rows re-checks inside every
 * write transaction.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 95)
public class StatusGateFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(StatusGateFilter.class);

    private final TenantStatusLookup lookup;

    public StatusGateFilter(TenantStatusLookup lookup) {
        this.lookup = lookup;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Health, docs and anything outside the API. A suspended tenant must still be able to
        // reach a status page, and an operator must still be able to reach the probes.
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String tenant = TenantContext.get();
        if (tenant == null) {
            // Unauthenticated, or a token with no tenant: the security chain answers that, and
            // it is not this filter's question.
            chain.doFilter(request, response);
            return;
        }
        AccountStatus status = lookup.statusOf(tenant, subjectOf());
        boolean write = isWrite(request);
        if (status.permitsReads() && (!write || status.permitsWrites())) {
            chain.doFilter(request, response);
            return;
        }

        LOG.info("Refusing {} {} for tenant {}: account is {}",
            request.getMethod(), request.getRequestURI(), tenant, status);
        response.setStatus(403);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        // Machine-readable, because a UI has to say something true. A message alone would make
        // "suspended" and "read only" indistinguishable without parsing prose.
        response.getWriter().write("{\"error\":{\"code\":\"" + status.reasonCode()
            + "\",\"message\":\"" + message(status, write) + "\"}}");
    }

    private static String message(AccountStatus status, boolean write) {
        if (status == AccountStatus.SUSPENDED) {
            return "This account is suspended. Contact your administrator.";
        }
        return write
            ? "This account is read only. You can view and export, but not change anything."
            : "This account is read only.";
    }

    private static boolean isWrite(HttpServletRequest request) {
        return switch (request.getMethod().toUpperCase()) {
            case "GET", "HEAD", "OPTIONS" -> false;
            default -> true;
        };
    }

    private static String subjectOf() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication instanceof JwtAuthenticationToken token
            ? token.getToken().getSubject() : null;
    }
}
