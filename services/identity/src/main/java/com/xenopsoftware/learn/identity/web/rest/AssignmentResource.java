package com.xenopsoftware.learn.identity.web.rest;

import com.xenopsoftware.learn.identity.authz.AssignmentScopeType;
import com.xenopsoftware.learn.identity.authz.AssignmentService;
import com.xenopsoftware.learn.identity.authz.Permission;
import com.xenopsoftware.learn.identity.authz.Reach;
import com.xenopsoftware.learn.identity.authz.RoleAssignment;
import com.xenopsoftware.learn.identity.authz.RoleException;
import com.xenopsoftware.learn.identity.authz.ScopeGrant;
import com.xenopsoftware.learn.identity.authz.ScopeResolver;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Granting and revoking roles at a scope (T-2.3), and the endpoint that shows a caller their own
 * reach — the thing a UI needs to render "which departments can I administer" without
 * reimplementing scope resolution in the browser.
 *
 * <p>Still authentication-only, and now for exactly one remaining reason: {@code role:assign}
 * can finally be held, but nobody holds anything yet — the first grant has no one to grant it
 * (T-2.7 seeds it) and the no-escalation rule that must guard this path is T-2.6.
 */
@RestController
@RequestMapping("/api/v1")
public class AssignmentResource {

    private final AssignmentService assignments;
    private final ScopeResolver scopes;

    public AssignmentResource(AssignmentService assignments, ScopeResolver scopes) {
        this.assignments = assignments;
        this.scopes = scopes;
    }

    public record GrantRequest(UUID roleId, UUID userId, UUID groupId, String scopeType, UUID scopeId) {}

    public record AssignmentView(UUID id, UUID roleId, UUID userId, UUID groupId,
                                 String scopeType, UUID scopeId, UUID grantedBy) {

        static AssignmentView of(RoleAssignment assignment) {
            return new AssignmentView(assignment.getId(), assignment.getRoleId(),
                assignment.getUserId(), assignment.getGroupId(),
                assignment.getScopeType().name(), assignment.getScopeId(), assignment.getGrantedBy());
        }
    }

    public record ReachView(String permission, boolean wholeTenant, Set<UUID> groupIds,
                            Set<UUID> courseIds) {}

    @GetMapping("/assignments")
    public List<AssignmentView> all() {
        return assignments.all().stream().map(AssignmentView::of).toList();
    }

    @GetMapping("/roles/{roleId}/assignments")
    public List<AssignmentView> ofRole(@PathVariable UUID roleId) {
        return assignments.ofRole(roleId).stream().map(AssignmentView::of).toList();
    }

    @PostMapping("/assignments")
    public AssignmentView grant(@RequestBody GrantRequest request) {
        ScopeGrant scope = scopeOf(request);
        if ((request.userId() == null) == (request.groupId() == null)) {
            throw new RoleException("Assign to exactly one of userId or groupId");
        }
        RoleAssignment assignment = request.userId() != null
            ? assignments.assignToUser(request.roleId(), request.userId(), scope)
            : assignments.assignToGroup(request.roleId(), request.groupId(), scope);
        return AssignmentView.of(assignment);
    }

    @DeleteMapping("/assignments/{id}")
    public void revoke(@PathVariable UUID id) {
        assignments.revoke(id);
    }

    /** What the caller can exercise this permission over — scope resolution, not a second copy. */
    @GetMapping("/me/reach/{resource}/{action}")
    public ReachView reach(@PathVariable String resource, @PathVariable String action) {
        Permission permission = Permission.byCode(resource + ":" + action)
            .orElseThrow(() -> new RoleException(resource + ":" + action + " is not in the catalog"));
        Reach reach = scopes.reachFor(permission);
        return new ReachView(permission.code(), reach.wholeTenant(), reach.groupIds(), reach.courseIds());
    }

    private static ScopeGrant scopeOf(GrantRequest request) {
        AssignmentScopeType type;
        try {
            type = AssignmentScopeType.valueOf(request.scopeType());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new RoleException("scopeType must be one of TENANT, GROUP, COURSE");
        }
        if (type.isUnbounded() && request.scopeId() != null) {
            throw new RoleException(type + " scope points at everything; drop scopeId");
        }
        if (!type.isUnbounded() && request.scopeId() == null) {
            throw new RoleException(type + " scope needs a scopeId");
        }
        return new ScopeGrant(type, request.scopeId());
    }
}
