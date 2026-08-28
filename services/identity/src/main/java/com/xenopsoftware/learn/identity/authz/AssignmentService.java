package com.xenopsoftware.learn.identity.authz;

import com.xenopsoftware.learn.identity.audit.AuditLogger;
import com.xenopsoftware.learn.identity.audit.CurrentUser;
import com.xenopsoftware.learn.identity.group.UserGroupRepository;
import com.xenopsoftware.learn.identity.user.AppUserRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Granting and revoking roles, at a scope (T-2.3).
 *
 * <p>Every grant is checked against three things before it exists: the subject is real, the
 * scope target is real where this module can tell, and the scope is wide enough for every
 * permission the role carries. That last one is where T-2.1's {@code minScope} stops being
 * documentation — {@code role:manage} at GROUP scope would be a role edit performed by someone
 * who cannot see everyone the role affects.
 *
 * <p>What is deliberately NOT here: the no-escalation rule (nobody grants what they do not
 * hold). That is T-2.6, it needs the caller's own grants rather than the target's, and putting
 * half of it here would leave a check that looks complete and is not.
 */
@Service
public class AssignmentService {

    private final RoleAssignmentRepository assignments;
    private final RoleRepository roles;
    private final RolePermissionRepository rolePermissions;
    private final AppUserRepository users;
    private final UserGroupRepository groups;
    private final AuthzVersion authzVersion;
    private final AuditLogger audit;
    private final CurrentUser currentUser;

    public AssignmentService(RoleAssignmentRepository assignments, RoleRepository roles,
            RolePermissionRepository rolePermissions, AppUserRepository users,
            UserGroupRepository groups, AuthzVersion authzVersion, AuditLogger audit,
            CurrentUser currentUser) {
        this.assignments = assignments;
        this.roles = roles;
        this.rolePermissions = rolePermissions;
        this.users = users;
        this.groups = groups;
        this.authzVersion = authzVersion;
        this.audit = audit;
        this.currentUser = currentUser;
    }

    @Transactional
    public RoleAssignment assignToUser(UUID roleId, UUID userId, ScopeGrant scope) {
        Role role = requireRole(roleId);
        if (users.findById(userId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such user in this tenant");
        }
        validateScope(role, scope);
        RoleAssignment saved = assignments.save(
            RoleAssignment.toUser(roleId, userId, scope, currentUser.requireId()));
        audited("assignment.grant", saved, Map.of("subjectType", "USER", "subjectId", userId.toString()));
        return saved;
    }

    @Transactional
    public RoleAssignment assignToGroup(UUID roleId, UUID groupId, ScopeGrant scope) {
        Role role = requireRole(roleId);
        if (groups.findById(groupId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such group in this tenant");
        }
        validateScope(role, scope);
        RoleAssignment saved = assignments.save(
            RoleAssignment.toGroup(roleId, groupId, scope, currentUser.requireId()));
        audited("assignment.grant", saved, Map.of("subjectType", "GROUP", "subjectId", groupId.toString()));
        return saved;
    }

    @Transactional
    public void revoke(UUID assignmentId) {
        RoleAssignment assignment = assignments.findById(assignmentId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        assignments.delete(assignment);
        audited("assignment.revoke", assignment, Map.of(
            "subjectType", assignment.getUserId() != null ? "USER" : "GROUP",
            "subjectId", String.valueOf(assignment.getUserId() != null
                ? assignment.getUserId() : assignment.getGroupId())));
    }

    public List<RoleAssignment> all() {
        return assignments.findAll();
    }

    public List<RoleAssignment> ofRole(UUID roleId) {
        requireRole(roleId);
        return assignments.findByRoleId(roleId);
    }

    /**
     * The scope must be wide enough for every permission the role carries, and it must be a
     * scope this side can express. Checked at grant time rather than at use time: a grant that
     * silently never works is the failure T-2.2 refused for cross-side permissions, and it is
     * the same failure here one level down.
     */
    private void validateScope(Role role, ScopeGrant scope) {
        if (scope.type() == AssignmentScopeType.PLATFORM) {
            throw new RoleException("Platform-scoped assignments are seeded (T-2.7), not granted at runtime");
        }
        if (scope.type() == AssignmentScopeType.GROUP && groups.findById(scope.targetId()).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such group in this tenant");
        }
        // A COURSE scope id is not validated, and cannot be: courses live in catalog (T-5.2),
        // another module and another database. Recorded rather than silently trusted.
        for (RolePermission held : rolePermissions.findByRoleId(role.getId())) {
            held.permission().ifPresent(permission -> {
                if (!scope.type().covers(permission.minScope())) {
                    throw new RoleException(permission.code() + " needs at least "
                        + permission.minScope() + " scope; this assignment is " + scope.type());
                }
            });
        }
    }

    private Role requireRole(UUID roleId) {
        return roles.findById(roleId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private void audited(String action, RoleAssignment assignment, Map<String, String> subject) {
        Map<String, Object> payload = new LinkedHashMap<>(subject);
        payload.put("roleId", assignment.getRoleId().toString());
        payload.put("scopeType", assignment.getScopeType().name());
        payload.put("scopeId", String.valueOf(assignment.getScopeId()));
        // grantedBy is on the row as well; recording it here too means the log answers "who
        // granted this" without joining to a row that a later revoke will delete.
        payload.put("grantedBy", assignment.getGrantedBy().toString());
        audit.record(action, "assignment", assignment.getId(), payload);
        authzVersion.bump();
    }
}
