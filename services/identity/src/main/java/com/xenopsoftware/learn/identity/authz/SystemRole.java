package com.xenopsoftware.learn.identity.authz;

import java.util.Set;

/**
 * The platform's role templates (T-2.7). This enum IS the definition; the {@code app_role} rows
 * marked {@code system} are its projection into each tenant, written by
 * {@link SystemRoleSeeder} and by nothing else.
 *
 * <p><b>A customer clones a template and edits the clone.</b> If customers could edit templates
 * in place, adding a permission to a template would have an undefined effect on every customer
 * who had touched theirs, and there would be no correct answer for what a new customer's
 * tenant-admin should contain. So the projection is exact and repeatable: a system role always
 * matches this code, and a clone never changes again unless its owner changes it.
 *
 * <p>Each constant carries one sentence a salesperson could read, and that sentence is what
 * lands in the role's description and in the API docs — one place to keep true.
 */
public enum SystemRole {

    LEARNER("learner", "Learner", PermissionSide.TENANT,
        "Can take the courses assigned to them and see their own progress, and nothing about anyone else.",
        // The first content-side permission arrives here, exactly as T-2.7 said it would: a
        // learner's abilities come from E3/E5/E6 and the re-projection is how the role acquires
        // them. content:view is what makes them a person who watches things; WHICH things is an
        // assignment (T-5.5), not a wider grant.
        Set.of(Permission.CONTENT_VIEW)),

    AUTHOR("author", "Author", PermissionSide.TENANT,
        "Builds courses, uploads video and writes questions, without administering people.",
        // An author who cannot watch content cannot check their own course, so this one is
        // shared with the learner. The rest of authoring is still E4/E5/E6.
        Set.of(Permission.CONTENT_VIEW)),

    GROUP_MANAGER("group-manager", "Group manager", PermissionSide.TENANT,
        "Runs one department: sees and manages the people inside it, and nobody outside it.",
        Set.of(Permission.USER_READ, Permission.USER_MANAGE, Permission.GROUP_READ)),

    TENANT_ADMIN("tenant-admin", "Company administrator", PermissionSide.TENANT,
        "Runs the whole company account: people, departments, roles and who holds them.",
        // impersonation:read is seeded here from the start (T-2.8). The record of our staff
        // entering this account is the customer's to read, and a visibility that has to be
        // switched on is a visibility nobody has when it matters.
        Set.of(Permission.USER_READ, Permission.USER_MANAGE, Permission.GROUP_READ,
            Permission.GROUP_MANAGE, Permission.ROLE_READ, Permission.ROLE_MANAGE,
            Permission.ROLE_ASSIGN, Permission.IMPERSONATION_READ, Permission.SSO_MANAGE)),

    SUPPORT("support", "Support", PermissionSide.PLATFORM,
        "Our support staff, who can look at a customer account through one of its users — read-only, "
            + "time-boxed, and visible to that customer afterwards.",
        Set.of(Permission.SUPPORT_IMPERSONATE)),

    /**
     * Impersonation that may change a customer's data. Its own template, deliberately: T-2.8
     * asks for a separate permission AND a separate decision, and a second permission inside the
     * same role would only be the first half. Somebody has to be given this instead of
     * {@code support}, which is a moment where a name and a reason exist.
     */
    SUPPORT_WRITE("support-write", "Support (write)", PermissionSide.PLATFORM,
        "Support staff who may also make changes inside a customer account while impersonating — "
            + "rarer, and recorded the same way.",
        Set.of(Permission.SUPPORT_IMPERSONATE, Permission.SUPPORT_IMPERSONATE_WRITE)),

    SYS_ADMIN("sys-admin", "System administrator", PermissionSide.PLATFORM,
        "Our own staff, who create customer accounts and can suspend one.",
        // NO impersonation here, and its removal is T-2.8's first acceptance criterion rather
        // than a tidy-up. PlatformBootstrap grants this role to every configured administrator
        // at startup (T-1.5), so carrying support:impersonate would mean every operator of this
        // installation silently held a key into every customer account, granted by a
        // configuration line about something else. Entering a customer account is a role
        // somebody is deliberately given.
        Set.of(Permission.TENANT_PROVISION, Permission.TENANT_SUSPEND));

    private final String code;
    private final String displayName;
    private final PermissionSide side;
    private final String reach;
    private final Set<Permission> permissions;

    SystemRole(String code, String displayName, PermissionSide side, String reach,
            Set<Permission> permissions) {
        for (Permission permission : permissions) {
            if (permission.side() != side) {
                // Fails enum initialization, which fails startup: a template that cannot be
                // granted is worse than one that does not exist, because it looks granted.
                throw new IllegalArgumentException(
                    code + " is a " + side + " role but carries " + permission.code());
            }
        }
        this.code = code;
        this.displayName = displayName;
        this.side = side;
        this.reach = reach;
        this.permissions = Set.copyOf(permissions);
    }

    public String code() {
        return code;
    }

    public String displayName() {
        return displayName;
    }

    public PermissionSide side() {
        return side;
    }

    /** One sentence, for a customer to read. */
    public String reach() {
        return reach;
    }

    public Set<Permission> permissions() {
        return permissions;
    }
}
