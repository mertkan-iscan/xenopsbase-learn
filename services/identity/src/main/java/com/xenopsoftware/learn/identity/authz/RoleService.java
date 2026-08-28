package com.xenopsoftware.learn.identity.authz;

import com.xenopsoftware.learn.identity.audit.AuditLogger;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Building and editing roles at runtime (T-2.2).
 *
 * <p>Every method that changes what a role grants does three things in one transaction: the
 * change, the {@link AuthzVersion} bump that invalidates cached permission sets, and the audit
 * entry carrying the before and after. One transaction is not tidiness — a version that moved
 * for a change that rolled back sends every cache in the tenant to refetch the old answer, and
 * an audit entry for a change that never happened is worse than none.
 */
@Service
public class RoleService {

    private final RoleRepository roles;
    private final RolePermissionRepository rolePermissions;
    private final RoleUsageCounter usage;
    private final AuthzVersion authzVersion;
    private final AuditLogger audit;

    public RoleService(RoleRepository roles, RolePermissionRepository rolePermissions,
            RoleUsageCounter usage, AuthzVersion authzVersion, AuditLogger audit) {
        this.roles = roles;
        this.rolePermissions = rolePermissions;
        this.usage = usage;
        this.authzVersion = authzVersion;
        this.audit = audit;
    }

    @Transactional
    public Role create(String name, String description, PermissionSide side) {
        if (side == PermissionSide.PLATFORM) {
            // Modelled, not editable: a platform-side row cannot be read back at all until the
            // root-tenant opt-in lands (T-1.5), and platform roles are seeded by T-2.7 rather
            // than built at runtime. Refusing loudly beats writing a row nothing can fetch.
            throw new RoleException("Platform-side roles are seeded (T-2.7), not created at runtime");
        }
        Role role = roles.save(new Role(name, description, side));
        audit.record("role.create", "role", role.getId(),
            Map.of("name", name, "side", side.name(), "permissions", List.of()));
        authzVersion.bump();
        return role;
    }

    /**
     * Renaming changes what a human reads and nothing else — assignments, audit entries and
     * every other reference point at the id, so there is no identity to update and deliberately
     * no version bump: nobody's effective permissions moved.
     */
    @Transactional
    public Role rename(UUID roleId, String newName, String newDescription) {
        Role role = editable(roleId);
        String previousName = role.getName();
        role.rename(newName, newDescription);
        roles.save(role);
        audit.record("role.rename", "role", roleId,
            Map.of("before", Map.of("name", previousName), "after", Map.of("name", newName)));
        return role;
    }

    /**
     * The permission set of a role, replaced wholesale in one transaction (T-2.2's second
     * criterion). Wholesale rather than add/remove endpoints because a role's grant is the set:
     * two callers each adding one permission to a stale view would otherwise produce a role
     * neither of them chose.
     */
    @Transactional
    public Role setPermissions(UUID roleId, Set<Permission> permissions) {
        Role role = editable(roleId);
        for (Permission permission : permissions) {
            if (permission.side() != role.getSide()) {
                // The criterion this issue leads with. Enforced at write time, because the
                // alternative -- a tenant role quietly holding tenant:suspend and the evaluator
                // refusing it later on the side pre-filter -- is a grant that reads as real in
                // every screen and works nowhere.
                throw new RoleException("Role is " + role.getSide() + " but " + permission.code()
                    + " is a " + permission.side() + " permission");
            }
        }
        Set<String> before = currentCodes(roleId);
        rolePermissions.deleteByRoleId(roleId);
        // Flush the deletes before the inserts, or the unique constraint on (role_id, code)
        // fires against rows Hibernate has not removed yet for a permission being kept.
        rolePermissions.flush();
        for (Permission permission : permissions) {
            rolePermissions.save(new RolePermission(roleId, permission));
        }
        Set<String> after = new TreeSet<>(permissions.stream().map(Permission::code).toList());

        audit.record("role.permissions", "role", roleId, Map.of("before", before, "after", after));
        authzVersion.bump();
        return role;
    }

    /**
     * Deleting a role in use is refused with the count (T-2.2's third criterion). No silent
     * cascade: revoking a role from an unknown number of people is a decision with a blast
     * radius, and the caller is the one who should see the number before making it.
     */
    @Transactional
    public void delete(UUID roleId) {
        Role role = editable(roleId);
        long assignments = usage.assignmentsOf(roleId);
        if (assignments > 0) {
            throw new RoleException("Role is assigned " + assignments
                + " time(s); revoke those assignments first, or delete with cascade=true");
        }
        Set<String> permissions = currentCodes(roleId);
        rolePermissions.deleteByRoleId(roleId);
        roles.delete(role);
        audit.record("role.delete", "role", roleId,
            Map.of("name", role.getName(), "permissions", permissions, "assignments", 0));
        authzVersion.bump();
    }

    /**
     * The explicit alternative: the assignments go too, and the audit entry says how many —
     * which is what makes this a cascade somebody chose rather than one that happened.
     */
    @Transactional
    public void deleteCascading(UUID roleId) {
        Role role = editable(roleId);
        long assignments = usage.assignmentsOf(roleId);
        Set<String> permissions = currentCodes(roleId);
        rolePermissions.deleteByRoleId(roleId);
        roles.delete(role);
        audit.record("role.delete.cascade", "role", roleId,
            Map.of("name", role.getName(), "permissions", permissions, "assignments", assignments));
        authzVersion.bump();
    }

    public List<Role> all() {
        return roles.findAll();
    }

    public Role get(UUID roleId) {
        return require(roleId);
    }

    /** The codes a role currently holds, sorted so an audit payload diffs cleanly by eye. */
    public Set<String> currentCodes(UUID roleId) {
        Set<String> codes = new TreeSet<>();
        for (RolePermission held : rolePermissions.findByRoleId(roleId)) {
            codes.add(held.getPermissionCode());
        }
        return codes;
    }

    private Role editable(UUID roleId) {
        Role role = require(roleId);
        if (role.isSystem()) {
            // T-2.7 owns the seeding and the clone-but-do-not-edit rule; the guard lives here
            // so no edit path can reach a system role even before that task exists.
            throw new RoleException("System roles cannot be edited; clone it and edit the copy");
        }
        return role;
    }

    private Role require(UUID roleId) {
        // Tenant-filtered by the persistence layer: another tenant role is not found here.
        return roles.findById(roleId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
