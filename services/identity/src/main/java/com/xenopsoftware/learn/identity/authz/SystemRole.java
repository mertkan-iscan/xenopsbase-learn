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
        // Empty against today's catalog, and that is a statement rather than an oversight: a
        // learner's abilities are content-side, and those permissions arrive with E3/E5/E6.
        // The re-projection is how this role acquires them, which is exactly the mechanism
        // this task exists to make predictable.
        Set.of()),

    AUTHOR("author", "Author", PermissionSide.TENANT,
        "Builds courses, uploads video and writes questions, without administering people.",
        // Also content-side, for the same reason.
        Set.of()),

    GROUP_MANAGER("group-manager", "Group manager", PermissionSide.TENANT,
        "Runs one department: sees and manages the people inside it, and nobody outside it.",
        Set.of(Permission.USER_READ, Permission.USER_MANAGE, Permission.GROUP_READ)),

    TENANT_ADMIN("tenant-admin", "Company administrator", PermissionSide.TENANT,
        "Runs the whole company account: people, departments, roles and who holds them.",
        Set.of(Permission.USER_READ, Permission.USER_MANAGE, Permission.GROUP_READ,
            Permission.GROUP_MANAGE, Permission.ROLE_READ, Permission.ROLE_MANAGE,
            Permission.ROLE_ASSIGN)),

    SUPPORT("support", "Support", PermissionSide.PLATFORM,
        "Our support staff, who can act as a customer's user to reproduce a problem — always visibly, afterwards.",
        Set.of(Permission.SUPPORT_IMPERSONATE)),

    SYS_ADMIN("sys-admin", "System administrator", PermissionSide.PLATFORM,
        "Our own staff, who create customer accounts and can suspend one.",
        Set.of(Permission.TENANT_PROVISION, Permission.TENANT_SUSPEND, Permission.SUPPORT_IMPERSONATE));

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
