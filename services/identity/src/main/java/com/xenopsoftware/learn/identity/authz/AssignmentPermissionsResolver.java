package com.xenopsoftware.learn.identity.authz;

import com.xenopsoftware.learn.identity.group.GroupHierarchy;
import com.xenopsoftware.learn.identity.group.GroupMembership;
import com.xenopsoftware.learn.identity.group.GroupMembershipRepository;
import com.xenopsoftware.learn.identity.user.UserProvisioningService;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * The real grant source (T-2.3): what T-2.4 built the {@link PermissionsResolver} port for, and
 * what {@code UngrantedResolver} was standing in for until assignments existed.
 *
 * <p><b>The decision this issue asked to be recorded explicitly: a group assignment applies to
 * the group's members AND to the members of its descendants.</b> The alternative — members of
 * that exact group only — makes the tree decorative: a role granted at the company root would
 * reach nobody except the handful of people filed directly at the root, and every reorganisation
 * would silently change who holds what. Containment is what the tree means, so containment is
 * what an assignment follows.
 *
 * <p>Which is why the lookup walks <em>up</em> from the caller's own groups: an assignment on any
 * ancestor of a group they belong to is an assignment that reaches them. The walk is bounded by
 * {@link GroupHierarchy#MAX_DEPTH}, and the whole resolution is one membership read, one
 * bounded ancestor walk and one assignment query — paid once per request.
 */
@Component
public class AssignmentPermissionsResolver implements PermissionsResolver {

    private final UserProvisioningService provisioning;
    private final GroupMembershipRepository memberships;
    private final GroupHierarchy hierarchy;
    private final RoleAssignmentRepository assignments;

    public AssignmentPermissionsResolver(UserProvisioningService provisioning,
            GroupMembershipRepository memberships, GroupHierarchy hierarchy,
            RoleAssignmentRepository assignments) {
        this.provisioning = provisioning;
        this.memberships = memberships;
        this.hierarchy = hierarchy;
        this.assignments = assignments;
    }

    @Override
    public GrantedPermissions resolveFor(Jwt caller) {
        UUID userId = provisioning.provision(caller).getId();

        Set<UUID> reachingGroups = new LinkedHashSet<>();
        for (GroupMembership membership : memberships.findAll()) {
            if (membership.getUserId().equals(userId)) {
                reachingGroups.add(membership.getGroupId());
                reachingGroups.addAll(hierarchy.ancestorIds(membership.getGroupId()));
            }
        }
        // A sentinel, because "in no groups" must not become "in every group" via an empty IN
        // list -- and JPQL rejects an empty collection parameter outright.
        List<UUID> groupIds = new ArrayList<>(reachingGroups);
        if (groupIds.isEmpty()) {
            groupIds.add(new UUID(0, 0));
        }

        Map<Permission, Set<ScopeGrant>> grants = new EnumMap<>(Permission.class);
        for (Object[] row : assignments.resolveFor(userId, groupIds)) {
            String code = (String) row[0];
            AssignmentScopeType scopeType = (AssignmentScopeType) row[1];
            UUID scopeId = (UUID) row[2];
            // A permission code that is no longer in the catalog is skipped rather than
            // crashing the request: T-2.1 orphans retired codes precisely so a role holding one
            // keeps working for everything else it grants.
            Permission.byCode(code).ifPresent(permission -> grants
                .computeIfAbsent(permission, any -> new LinkedHashSet<>())
                .add(new ScopeGrant(scopeType, scopeId)));
        }
        return new GrantedPermissions(grants);
    }
}
