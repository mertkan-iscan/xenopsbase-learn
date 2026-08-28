package com.xenopsoftware.learn.identity.authz;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

/**
 * The caller's permission set, resolved once per request (T-2.4's second criterion).
 *
 * <p>Request-scoped so the lifetime is exactly right by construction: a handler that triggers
 * five checks costs one resolution, and nothing can cache a set beyond the request that
 * resolved it — which is what keeps T-2.5's invalidation story simple: a version bump takes
 * effect on the very next request, because no request reuses another's set.
 */
@Component
@RequestScope
public class RequestPermissions {

    private final PermissionsResolver resolver;
    private GrantedPermissions resolved;

    public RequestPermissions(PermissionsResolver resolver) {
        this.resolver = resolver;
    }

    public GrantedPermissions forCaller(Jwt caller) {
        if (resolved == null) {
            resolved = resolver.resolveFor(caller);
        }
        return resolved;
    }
}
