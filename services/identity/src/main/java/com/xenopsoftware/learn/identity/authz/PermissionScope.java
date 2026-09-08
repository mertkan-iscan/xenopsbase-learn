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

    /**
     * One named object of whatever kind the permission is about — a bank, a course (T-6.1).
     *
     * <p>Narrower than GROUP and deliberately not named after any one of those kinds. The
     * assignment side has a constant per kind ({@link AssignmentScopeType#BANK},
     * {@link AssignmentScopeType#COURSE}) because a grant has to point at a particular row; the
     * catalog side only needs to say "this permission is meaningful about a single object", and
     * having it name the kind would mean a new floor per module.
     *
     * <p>Because it is kind-agnostic, it cannot be the whole check. A COURSE-scoped grant and a
     * BANK-scoped grant both clear a RESOURCE floor, so whatever evaluates a grant must also match
     * the target's kind — see {@link AssignmentScopeType#covers}, which answers width and says so.
     */
    RESOURCE,

    GROUP,
    TENANT,
    PLATFORM
}
