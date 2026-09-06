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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Binds the tenant from the verified token, and unbinds it afterwards (T-1.1).
 *
 * <p>Runs after Spring Security, deliberately: the claim is only trustworthy once the token's
 * signature, issuer and audience have been checked. Reading it earlier would read whatever the
 * caller sent.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 100)
public class TenantFilter extends OncePerRequestFilter {

    // The claim names this filter reads live in TenantClaims, in platform-common, because the
    // gateway binds the same tenant from the same claim on a reactive stack and cannot see a
    // servlet filter (ADR-0111). They were fields here until then.

    private static final Logger LOG = LoggerFactory.getLogger(TenantFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        String tenant = resolve();
        if (tenant != null) {
            TenantContext.set(tenant);
        }
        try {
            chain.doFilter(request, response);
        } finally {
            // In a finally, and that is load-bearing rather than tidy. Threads are pooled: a
            // tenant left bound leaks into the next request that reuses the thread, and shows up
            // as another tenant's data appearing intermittently under load.
            TenantContext.clear();
        }
    }

    private String resolve() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            return null;
        }
        Jwt jwt = token.getToken();
        String tenant = jwt.getClaimAsString(TenantClaims.TENANT_CLAIM);
        if (TenantClaims.PLATFORM.equals(jwt.getClaimAsString(TenantClaims.SIDE_CLAIM))) {
            // Platform staff belong to no customer, so they are bound to the platform's own
            // reserved tenant (T-1.5) rather than to whatever a token claims. A tenant_id claim
            // on a platform token is still ignored -- that is the same forgery this filter
            // exists to refuse, and it is worth a line because the realm's first draft really
            // did put tenant_id: "PLATFORM" on platform-admin.
            if (tenant != null && !tenant.isBlank() && !TenantClaims.PLATFORM_TENANT.equals(tenant)) {
                LOG.warn("Platform-side subject {} carries a {} claim ({}); ignored, bound to {}",
                    jwt.getSubject(), TenantClaims.TENANT_CLAIM, tenant, TenantClaims.PLATFORM_TENANT);
            }
            return TenantClaims.PLATFORM_TENANT;
        }
        if (tenant == null || tenant.isBlank()) {
            // Authenticated, tenant-side, but carrying no tenant. Almost always a realm mapper
            // that stopped applying rather than a legitimate request, so it is worth a line --
            // the alternative is a 500 from require() with nothing explaining where the claim
            // went.
            LOG.warn("Authenticated subject {} has no {} claim; no tenant bound", jwt.getSubject(), TenantClaims.TENANT_CLAIM);
            return null;
        }
        return tenant;
    }
}
