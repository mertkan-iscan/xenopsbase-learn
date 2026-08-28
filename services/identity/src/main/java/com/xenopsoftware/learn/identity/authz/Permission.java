package com.xenopsoftware.learn.identity.authz;

/**
 * The permission catalog (T-2.1, ADR-0103). This enum IS the catalog; the {@code permission}
 * table is its projection, written by {@link PermissionCatalogSeeder} and nobody else.
 *
 * <p>Code, not data, because a permission is only real if some code path checks it. If the
 * catalog were an admin screen, someone could create {@code report:export}, grant it, and be
 * certain they had restricted something — while every request still passed. Here a permission
 * exists exactly when a constant exists, and {@code CatalogCoverageTest} holds both directions
 * of that honest: every secured endpoint names a catalog entry, and every catalog entry is
 * either checked somewhere or explicitly listed as not-yet-enforced with the task that will
 * enforce it.
 *
 * <p>Codes are {@code resource:action}, stable forever once shipped — roles reference them as
 * rows. Retiring a constant orphans its row (visible, grantable-no-longer, never deleted);
 * renaming one is a retirement plus a new code, and the migration of existing roles that
 * implies. Add constants accordingly.
 */
public enum Permission {

    /** Resolve another member's display details — the read behind every people-picker. */
    USER_READ("user:read", PermissionSide.TENANT, PermissionScope.GROUP),

    /** Invite, deactivate, and edit members (T-1.9). */
    USER_MANAGE("user:manage", PermissionSide.TENANT, PermissionScope.GROUP),

    /** See the group tree, as far as scope allows (T-1.3). */
    GROUP_READ("group:read", PermissionSide.TENANT, PermissionScope.GROUP),

    /** Create, move and delete groups within scope (T-1.3). */
    GROUP_MANAGE("group:manage", PermissionSide.TENANT, PermissionScope.GROUP),

    /** See the tenant's roles and what they contain (T-2.2). */
    ROLE_READ("role:read", PermissionSide.TENANT, PermissionScope.TENANT),

    /** Build and edit roles by selecting permissions (T-2.2). TENANT floor: a role's edit
     * affects everyone holding it, so no narrower scope can own the edit. */
    ROLE_MANAGE("role:manage", PermissionSide.TENANT, PermissionScope.TENANT),

    /** Assign and revoke roles within scope (T-2.3), gated by the no-escalation rule (T-2.6). */
    ROLE_ASSIGN("role:assign", PermissionSide.TENANT, PermissionScope.GROUP),

    /** Create a company (T-1.5). */
    TENANT_PROVISION("tenant:provision", PermissionSide.PLATFORM, PermissionScope.PLATFORM),

    /** Suspend and reinstate a company (T-1.4). */
    TENANT_SUSPEND("tenant:suspend", PermissionSide.PLATFORM, PermissionScope.PLATFORM),

    /** Act as a tenant user, always visibly afterwards (T-2.8). */
    SUPPORT_IMPERSONATE("support:impersonate", PermissionSide.PLATFORM, PermissionScope.PLATFORM);

    private static final java.util.Map<String, Permission> BY_CODE;

    static {
        java.util.Map<String, Permission> byCode = new java.util.HashMap<>();
        for (Permission permission : values()) {
            byCode.put(permission.code, permission);
        }
        BY_CODE = java.util.Map.copyOf(byCode);
    }

    /** The catalog entry for a code, or empty — the caller decides how loudly to fail. */
    public static java.util.Optional<Permission> byCode(String code) {
        return java.util.Optional.ofNullable(BY_CODE.get(code));
    }

    private final String code;
    private final String resource;
    private final String action;
    private final PermissionSide side;
    private final PermissionScope minScope;

    Permission(String code, PermissionSide side, PermissionScope minScope) {
        int colon = code.indexOf(':');
        if (colon <= 0 || colon == code.length() - 1) {
            // Fails enum initialization, which fails everything -- the loudest available way
            // to reject a malformed code before it becomes a row roles reference.
            throw new IllegalArgumentException("Permission code must be resource:action, got " + code);
        }
        this.code = code;
        this.resource = code.substring(0, colon);
        this.action = code.substring(colon + 1);
        this.side = side;
        this.minScope = minScope;
    }

    public String code() {
        return code;
    }

    public String resource() {
        return resource;
    }

    public String action() {
        return action;
    }

    public PermissionSide side() {
        return side;
    }

    public PermissionScope minScope() {
        return minScope;
    }
}
