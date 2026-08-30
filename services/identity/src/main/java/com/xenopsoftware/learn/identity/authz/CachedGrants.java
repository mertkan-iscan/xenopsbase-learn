package com.xenopsoftware.learn.identity.authz;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * What a cached permission set looks like on the wire (T-2.5).
 *
 * <p>A flat list of codes and scopes rather than the {@link GrantedPermissions} object itself,
 * because the entry outlives the code that wrote it: a deploy is a rolling one, and for a few
 * minutes the version that reads an entry is not the version that wrote it. So nothing here is
 * an enum constant or a class name — a retired {@link Permission} code or an unknown scope is
 * <b>skipped</b> on the way in, exactly as {@code AssignmentPermissionsResolver} skips an
 * orphaned code coming out of the database. A single retired code must not make a whole set
 * unreadable and cost every request a resolution.
 *
 * <p>{@code schema} is the shape's own version, distinct from the tenant's {@code authz_version}
 * in the key. The tenant's number says <i>whose grants changed</i>; this one says <i>what the
 * JSON means</i>, and it is carried inside the key too so a shape change orphans old entries
 * rather than reinterpreting them.
 */
record CachedGrants(int schema, List<Entry> grants) {

    /** One permission at one place. {@code target} is null for TENANT and PLATFORM scopes. */
    record Entry(String permission, String scope, String target) {}

    static CachedGrants of(GrantedPermissions permissions) {
        List<Entry> entries = new ArrayList<>();
        permissions.grants().forEach((permission, scopes) -> scopes.forEach(grant ->
            entries.add(new Entry(permission.code(), grant.type().name(),
                grant.targetId() == null ? null : grant.targetId().toString()))));
        return new CachedGrants(ValkeyPermissions.SCHEMA, entries);
    }

    GrantedPermissions toGranted() {
        Map<Permission, Set<ScopeGrant>> resolved = new EnumMap<>(Permission.class);
        for (Entry entry : grants == null ? List.<Entry>of() : grants) {
            Optional<Permission> permission = Permission.byCode(entry.permission());
            Optional<AssignmentScopeType> scope = scopeType(entry.scope());
            if (permission.isEmpty() || scope.isEmpty()) {
                continue;
            }
            resolved.computeIfAbsent(permission.get(), any -> new LinkedHashSet<>())
                .add(new ScopeGrant(scope.get(),
                    entry.target() == null ? null : UUID.fromString(entry.target())));
        }
        return new GrantedPermissions(resolved);
    }

    private static Optional<AssignmentScopeType> scopeType(String name) {
        for (AssignmentScopeType type : AssignmentScopeType.values()) {
            if (type.name().equals(name)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
