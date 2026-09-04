package com.xenopsoftware.learn.identity.group;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Edits to the tree, with the two rules that keep it a tree (T-1.3): no cycles, and nothing deeper
 * than {@link GroupHierarchy#MAX_DEPTH}.
 */
@Service
public class GroupService {

    private final UserGroupRepository groups;
    private final GroupMembershipRepository memberships;
    private final GroupHierarchy hierarchy;
    private final com.xenopsoftware.learn.identity.tenant.StatusGuard statusGuard;

    public GroupService(UserGroupRepository groups, GroupMembershipRepository memberships,
            GroupHierarchy hierarchy,
            com.xenopsoftware.learn.identity.tenant.StatusGuard statusGuard) {
        this.statusGuard = statusGuard;
        this.groups = groups;
        this.memberships = memberships;
        this.hierarchy = hierarchy;
    }

    @Transactional
    public UserGroup create(String name, UUID parentId) {
        statusGuard.requireWritable();
        if (parentId != null) {
            UserGroup parent = require(parentId);
            int parentDepth = hierarchy.ancestorIds(parent.getId()).size();
            if (parentDepth + 1 > GroupHierarchy.MAX_DEPTH) {
                throw new GroupStructureException("A group at depth " + (parentDepth + 1)
                    + " would pass the maximum of " + GroupHierarchy.MAX_DEPTH);
            }
        }
        return groups.save(new UserGroup(name, parentId));
    }

    /**
     * Re-parents a subtree. One row changes; membership rows are not read, let alone written.
     *
     * <p>Both guards are checked against the tree as it is, before the write: the new parent may
     * not be inside the subtree being moved (that is how an adjacency list grows a cycle — a
     * ring nothing can reach and no query can leave), and the moved subtree's own height counts
     * toward the depth limit, so a deep branch cannot be tucked under a deep node.
     */
    @Transactional
    public UserGroup move(UUID groupId, UUID newParentId) {
        statusGuard.requireWritable();
        UserGroup group = require(groupId);
        if (newParentId == null) {
            group.moveUnder(null);
            return groups.save(group);
        }
        if (groupId.equals(newParentId)) {
            throw new GroupStructureException("A group cannot be its own parent");
        }
        UserGroup newParent = require(newParentId);
        if (hierarchy.ancestorIds(newParent.getId()).contains(groupId)) {
            throw new GroupStructureException(
                "Moving " + groupId + " under " + newParentId + " would make it its own ancestor");
        }
        int newParentDepth = hierarchy.ancestorIds(newParent.getId()).size();
        int height = hierarchy.subtreeHeight(groupId);
        if (newParentDepth + 1 + height > GroupHierarchy.MAX_DEPTH) {
            throw new GroupStructureException("That move would put the deepest group at "
                + (newParentDepth + 1 + height) + ", past the maximum of " + GroupHierarchy.MAX_DEPTH);
        }
        group.moveUnder(newParentId);
        return groups.save(group);
    }

    /**
     * Deletes a group. A group with members or children is refused with the counts — silently
     * orphaning either is not an option, and neither is deciding on the caller's behalf that
     * their people should move.
     */
    @Transactional
    public void delete(UUID groupId) {
        statusGuard.requireWritable();
        require(groupId);
        long members = memberships.countByGroupId(groupId);
        long children = groups.findByParentId(groupId).size();
        if (members > 0 || children > 0) {
            throw new GroupStructureException("Group holds " + members + " member(s) and "
                + children + " child group(s); re-home them explicitly, or delete with rehome=true");
        }
        groups.deleteById(groupId);
    }

    /**
     * The explicit alternative: members and children move up to this group's parent, and only
     * then does it go. A root group's children become roots and its members become unaffiliated
     * — stated here so nobody has to infer it from behaviour.
     */
    @Transactional
    public void deleteAndRehome(UUID groupId) {
        statusGuard.requireWritable();
        UserGroup group = require(groupId);
        UUID newParent = group.getParentId();
        for (UserGroup child : groups.findByParentId(groupId)) {
            child.moveUnder(newParent);
            groups.save(child);
        }
        for (GroupMembership membership : memberships.findByGroupId(groupId)) {
            memberships.delete(membership);
            if (newParent != null && memberships.findByGroupIdAndUserId(newParent, membership.getUserId()).isEmpty()) {
                memberships.save(new GroupMembership(newParent, membership.getUserId()));
            }
        }
        groups.deleteById(groupId);
    }

    @Transactional
    public GroupMembership addMember(UUID groupId, UUID userId) {
        statusGuard.requireWritable();
        require(groupId);
        return memberships.findByGroupIdAndUserId(groupId, userId)
            .orElseGet(() -> memberships.save(new GroupMembership(groupId, userId)));
    }

    @Transactional
    public void removeMember(UUID groupId, UUID userId) {
        statusGuard.requireWritable();
        // The group must be one of ours (T-1.6). Without this the endpoint answered 200 to
        // another company's group id -- nothing was removed and nothing was disclosed, but a
        // success for a row that is not here is a boundary that reads as porous, and the next
        // method written against it inherits the habit. Removing somebody who is not a member
        // stays a no-op: DELETE is idempotent about the membership, never about the group.
        require(groupId);
        memberships.findByGroupIdAndUserId(groupId, userId).ifPresent(memberships::delete);
    }

    /**
     * The subtree and everyone in it — for a group that exists <em>here</em>.
     *
     * <p>Routed through the service rather than straight to {@link GroupHierarchy} for exactly
     * one reason (T-1.6): those queries are native SQL handed a tenant id, so another company's
     * group produces an empty answer rather than a refusal, and the endpoint returned 200 to an
     * id it should not be able to address. The check belongs here, where every other caller
     * inherits it.
     */
    public GroupReach reach(UUID groupId) {
        require(groupId);
        return new GroupReach(hierarchy.subtreeIds(groupId),
            hierarchy.reachableUserIds(Set.of(groupId)));
    }

    /** A group admin's view from one group: the subtree, and everyone in it. */
    public record GroupReach(Set<UUID> groupIds, Set<UUID> userIds) {}

    public List<UserGroup> roots() {
        return groups.findByParentIdIsNull();
    }

    public List<UserGroup> children(UUID groupId) {
        require(groupId);
        return groups.findByParentId(groupId);
    }

    private UserGroup require(UUID groupId) {
        // Tenant-filtered by the persistence layer: another tenant's group is not found here,
        // which is the 404-not-403 shape ADR-0102 promises.
        return groups.findById(groupId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
