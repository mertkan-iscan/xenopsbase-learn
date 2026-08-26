package com.xenopsoftware.learn.identity.web.rest;

import com.xenopsoftware.learn.common.tenancy.TenantContext;
import com.xenopsoftware.learn.common.tenancy.TenantFilter;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the platform believes about the caller.
 *
 * <p>Small, and worth having from the first commit: it is the endpoint that answers "is the tenant
 * claim actually arriving" without reading a log, and every wiring failure in the identity chain
 * shows up here first.
 */
@RestController
@RequestMapping("/api/v1")
public class AuthInfoResource {

    @GetMapping("/auth-info")
    public Map<String, Object> authInfo(@AuthenticationPrincipal Jwt jwt) {
        return Map.of(
            "sub", jwt.getSubject(),
            "username", String.valueOf(jwt.getClaimAsString("preferred_username")),
            "tenant", String.valueOf(TenantContext.get()),
            "side", String.valueOf(jwt.getClaimAsString(TenantFilter.SIDE_CLAIM)),
            "realmRoles", realmRoles(jwt)
        );
    }

    @SuppressWarnings("unchecked")
    private List<String> realmRoles(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess == null) {
            // Empty rather than null, and worth noticing: a token with no realm_access is what a
            // realm import that replaced the built-in client scopes produces.
            return List.of();
        }
        return (List<String>) realmAccess.getOrDefault("roles", List.of());
    }
}
