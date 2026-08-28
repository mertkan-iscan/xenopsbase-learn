package com.xenopsoftware.learn.identity.authz;

import java.util.Map;

/**
 * A caller's resolved permission set: each held permission with the widest scope it is held at
 * (overlapping assignments union, widest wins — T-2.3's rule, represented here so the evaluator
 * never has to re-derive it).
 *
 * <p>Immutable and resolved once per request ({@link RequestPermissions}); anything downstream
 * asks this object, never the database.
 */
public record GrantedPermissions(Map<Permission, PermissionScope> grants) {

    private static final GrantedPermissions NONE = new GrantedPermissions(Map.of());

    public GrantedPermissions {
        grants = Map.copyOf(grants);
    }

    public static GrantedPermissions none() {
        return NONE;
    }

    public boolean holds(Permission permission) {
        return grants.containsKey(permission);
    }
}
