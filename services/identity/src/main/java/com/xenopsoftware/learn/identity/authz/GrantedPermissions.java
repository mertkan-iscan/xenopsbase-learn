package com.xenopsoftware.learn.identity.authz;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A caller's resolved grants: for each permission, every place they hold it (T-2.3).
 *
 * <p>Overlapping assignments <b>union</b> rather than conflict — being a group admin of
 * Engineering and separately a company admin makes someone a company admin, and nothing in this
 * type can express "denied", because a deny that silently loses to a union is worse than no deny
 * at all. Where a single answer is needed, {@link #widest} gives the widest scope held.
 *
 * <p>Resolved once per request ({@link RequestPermissions}); everything downstream asks this
 * object rather than the database.
 */
public record GrantedPermissions(Map<Permission, Set<ScopeGrant>> grants) {

    private static final GrantedPermissions NONE = new GrantedPermissions(Map.of());

    public GrantedPermissions {
        Map<Permission, Set<ScopeGrant>> copy = new LinkedHashMap<>();
        grants.forEach((permission, scopes) -> copy.put(permission, Set.copyOf(scopes)));
        grants = Map.copyOf(copy);
    }

    public static GrantedPermissions none() {
        return NONE;
    }

    /** Held anywhere at all. The question {@code hasPermission} asks (T-2.4). */
    public boolean holds(Permission permission) {
        return !scopesFor(permission).isEmpty();
    }

    public Set<ScopeGrant> scopesFor(Permission permission) {
        return grants.getOrDefault(permission, Set.of());
    }

    /** The widest scope this permission is held at, if it is held at all. */
    public Optional<ScopeGrant> widest(Permission permission) {
        return scopesFor(permission).stream()
            .max(Comparator.comparingInt(grant -> grant.type().width()));
    }
}
