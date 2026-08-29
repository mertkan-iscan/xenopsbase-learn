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

    /**
     * The claim carrying the tenant. Mapped in the realm onto each client rather than through a
     * shared client scope — declaring a {@code clientScopes} block in a realm import replaces
     * Keycloak's built-in scopes instead of adding to them, which strips {@code sub},
     * {@code preferred_username} and {@code realm_access.roles} from every token issued, with no
     * error anywhere. That is not hypothetical; it happened on the first import of this realm.
     */
    public static final String TENANT_CLAIM = "tenant_id";

    /** {@code PLATFORM} or {@code TENANT}. Decides which side's permissions may even be considered. */
    public static final String SIDE_CLAIM = "side";

    /** The value {@link #SIDE_CLAIM} carries for platform staff, who belong to no customer. */
    public static final String PLATFORM = "PLATFORM";

    /**
     * The tenant platform staff are bound to (T-1.5).
     *
     * <p>This reverses the narrower rule T-1.1 set, and the reason it is safe now is the reason
     * it was unsafe then. T-1.1 bound nothing for platform tokens because a sentinel with no
     * rows behind it is a filter matching nothing — a boundary that silently returns empty. The
     * reserved tenant now exists and platform staff have {@code app_user} rows in it (ADR-0104
     * applies to them too: "who provisioned this company" needs a durable answer), so binding it
     * matches exactly the platform's own data.
     *
     * <p>It is not a skeleton key. A platform caller bound here sees platform rows and no
     * customer's; reaching into a customer's data is a deliberate, audited act (tenant
     * provisioning, and impersonation in T-2.8), never a side effect of being staff.
     */
    public static final String PLATFORM_TENANT = "__platform";

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
        String tenant = jwt.getClaimAsString(TENANT_CLAIM);
        if (PLATFORM.equals(jwt.getClaimAsString(SIDE_CLAIM))) {
            // Platform staff belong to no customer, so they are bound to the platform's own
            // reserved tenant (T-1.5) rather than to whatever a token claims. A tenant_id claim
            // on a platform token is still ignored -- that is the same forgery this filter
            // exists to refuse, and it is worth a line because the realm's first draft really
            // did put tenant_id: "PLATFORM" on platform-admin.
            if (tenant != null && !tenant.isBlank() && !PLATFORM_TENANT.equals(tenant)) {
                LOG.warn("Platform-side subject {} carries a {} claim ({}); ignored, bound to {}",
                    jwt.getSubject(), TENANT_CLAIM, tenant, PLATFORM_TENANT);
            }
            return PLATFORM_TENANT;
        }
        if (tenant == null || tenant.isBlank()) {
            // Authenticated, tenant-side, but carrying no tenant. Almost always a realm mapper
            // that stopped applying rather than a legitimate request, so it is worth a line --
            // the alternative is a 500 from require() with nothing explaining where the claim
            // went.
            LOG.warn("Authenticated subject {} has no {} claim; no tenant bound", jwt.getSubject(), TENANT_CLAIM);
            return null;
        }
        return tenant;
    }
}
