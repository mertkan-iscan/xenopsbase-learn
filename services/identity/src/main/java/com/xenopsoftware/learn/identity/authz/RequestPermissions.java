package com.xenopsoftware.learn.identity.authz;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

/**
 * The caller's permission set, resolved once per request (T-2.4's second criterion).
 *
 * <p>Request-scoped so the lifetime is exactly right by construction: a handler that triggers
 * five checks costs one resolution, and nothing can carry a set beyond the request that resolved
 * it.
 *
 * <p>Which leaves the second question, and T-2.5 answers it: <b>across</b> requests the set is
 * cached in Valkey under a key carrying the tenant's {@link AuthzVersion}, so a revocation is
 * effective on the very next request without anything having to find and delete an entry. The
 * two layers are deliberately different mechanisms — this one is a field with a request's
 * lifetime, that one is a key that a version bump makes unreachable — and neither can serve a
 * set the other has invalidated, because neither outlives what invalidates it.
 */
@Component
@RequestScope
public class RequestPermissions {

    private final PermissionsResolver resolver;
    private final CachedPermissions cache;
    private GrantedPermissions resolved;

    public RequestPermissions(PermissionsResolver resolver, CachedPermissions cache) {
        this.resolver = resolver;
        this.cache = cache;
    }

    public GrantedPermissions forCaller(Jwt caller) {
        if (resolved == null) {
            resolved = impersonating()
                ? resolver.resolveFor(caller)
                : cache.resolve(caller, () -> resolver.resolveFor(caller));
        }
        return resolved;
    }

    /**
     * An impersonated request skips the cache entirely (T-2.8), and the reason is the cache key.
     * It is {@code tenant:sub:version}, and under a session the tenant is the customer's while
     * the sub is still the engineer's — so one engineer impersonating two people in the same
     * company inside one TTL would compute one key for two different answers, and serve the
     * first person's permissions as the second's. Narrowing the key would work; not caching a
     * rare, human-paced, minutes-long session costs one resolution per request and leaves
     * nothing to reason about.
     */
    private static boolean impersonating() {
        return com.xenopsoftware.learn.identity.impersonation.ImpersonationContext.current().isPresent();
    }
}
