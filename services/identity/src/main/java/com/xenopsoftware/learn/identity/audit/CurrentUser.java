package com.xenopsoftware.learn.identity.audit;

import com.xenopsoftware.learn.identity.user.AppUser;
import com.xenopsoftware.learn.identity.user.UserProvisioningService;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Who is acting, as an {@code app_user.id} (ADR-0104).
 *
 * <p>Identity can answer this locally because it owns the mapping; every other service will have
 * to ask it (T-9.11), which is why {@code streaming} records no actor yet rather than inventing
 * a nullable column for one.
 */
@Component
public class CurrentUser {

    private final UserProvisioningService provisioning;

    public CurrentUser(UserProvisioningService provisioning) {
        this.provisioning = provisioning;
    }

    /**
     * The acting user, provisioning on first sight exactly as {@code /me} does — the same
     * idempotent path, so an audited action performed by someone whose first request this
     * happens to be still records a real actor instead of failing.
     */
    public UUID requireId() {
        java.util.Optional<UUID> actor = com.xenopsoftware.learn.identity.impersonation
            .ImpersonationContext.current()
            .map(com.xenopsoftware.learn.identity.impersonation.Impersonation::actorUserId);
        if (actor.isPresent()) {
            // Under a session the actor is the support engineer, resolved when the session
            // opened, in the tenant they actually belong to. Resolving it from the token here
            // would provision them into the CUSTOMER's tenant -- the request is bound there --
            // creating an account the customer never invited, as a side effect of being audited.
            return actor.get();
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            throw new IllegalStateException(
                "No authenticated caller to attribute this action to. Audited work runs inside a "
                + "request; a scheduled job that needs to write audit entries has to carry an "
                + "explicit actor rather than borrowing one.");
        }
        AppUser user = provisioning.provision(token.getToken());
        return user.getId();
    }
}
