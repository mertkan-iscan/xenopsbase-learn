package com.xenopsoftware.learn.identity.web.rest;

import com.xenopsoftware.learn.common.tenancy.TenantContext;
import com.xenopsoftware.learn.identity.impersonation.ImpersonationSessions;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the customer can see about us (T-2.8's fifth criterion).
 *
 * <p>Every time our staff entered this company's account: when, by whom, why, whether that
 * session could write, and when it ended. Same table and same view as our own screen reads —
 * a customer-facing summary that showed less would be a summary somebody chose the contents of.
 *
 * <p>Its own controller rather than a route under {@code /platform}, because a URL is a claim
 * about whose data this is. These rows are the customer's; they are filtered by the tenant
 * discriminator like everything else they own, and gated by a TENANT permission their own
 * administrator holds by default.
 */
@RestController
@RequestMapping("/api/v1/impersonations")
public class TenantImpersonationResource {

    private final ImpersonationSessions sessions;

    public TenantImpersonationResource(ImpersonationSessions sessions) {
        this.sessions = sessions;
    }

    @GetMapping
    @PreAuthorize("hasPermission('impersonation', 'read')")
    public List<ImpersonationSessions.SessionView> all() {
        return sessions.forTenant(TenantContext.require());
    }
}
