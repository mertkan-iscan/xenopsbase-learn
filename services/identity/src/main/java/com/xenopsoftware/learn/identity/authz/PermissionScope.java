package com.xenopsoftware.learn.identity.authz;

/**
 * The narrowest scope at which a permission is meaningful, ordered from narrowest to widest.
 * An assignment (T-2.3) may hold a permission at this scope or wider, never narrower — a
 * {@code role:manage} scoped to one group would be a role editable by someone who cannot see
 * everyone the role affects.
 *
 * <p>T-2.3 owns the runtime semantics (what a GROUP-scoped assignment means against the group
 * tree); this enum owns only the catalog's floor per permission.
 */
public enum PermissionScope {
    SELF,
    GROUP,
    TENANT,
    PLATFORM
}
