package com.xenopsoftware.learn.identity.web.rest;

import com.xenopsoftware.learn.identity.group.GroupService;
import com.xenopsoftware.learn.identity.group.UserGroup;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The group tree and its membership (T-1.3).
 *
 * <p><b>These endpoints are authentication-only, and that is a gap with a date on it.</b> The
 * checks they want — {@code group:read} and {@code group:manage} — exist in the catalog (T-2.1)
 * and the evaluator that would enforce them exists (T-2.4), but nothing can yet HOLD a
 * permission: roles and scoped assignments are T-2.2 and T-2.3. Annotating now would deny every
 * caller, including the ones the product is for, so the checks arrive with the grants and
 * {@code CatalogCoverageTest} carries the reason in the meantime.
 */
@RestController
@RequestMapping("/api/v1/groups")
public class GroupResource {

    private final GroupService groupService;

    public GroupResource(GroupService groupService) {
        this.groupService = groupService;
    }

    public record CreateGroupRequest(String name, UUID parentId) {}

    public record MoveGroupRequest(UUID parentId) {}

    public record GroupView(UUID id, String name, UUID parentId) {

        static GroupView of(UserGroup group) {
            return new GroupView(group.getId(), group.getName(), group.getParentId());
        }
    }

    @GetMapping
    public List<GroupView> roots() {
        return groupService.roots().stream().map(GroupView::of).toList();
    }

    @GetMapping("/{id}/children")
    public List<GroupView> children(@PathVariable UUID id) {
        return groupService.children(id).stream().map(GroupView::of).toList();
    }

    /** An admin's reach from this group: the subtree, and everyone in it. */
    @GetMapping("/{id}/reach")
    public GroupService.GroupReach reach(@PathVariable UUID id) {
        return groupService.reach(id);
    }

    @PostMapping
    public GroupView create(@RequestBody CreateGroupRequest request) {
        return GroupView.of(groupService.create(request.name(), request.parentId()));
    }

    @PutMapping("/{id}/parent")
    public GroupView move(@PathVariable UUID id, @RequestBody MoveGroupRequest request) {
        return GroupView.of(groupService.move(id, request.parentId()));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable UUID id,
            @RequestParam(name = "rehome", defaultValue = "false") boolean rehome) {
        if (rehome) {
            groupService.deleteAndRehome(id);
        } else {
            groupService.delete(id);
        }
    }

    @PostMapping("/{id}/members/{userId}")
    public void addMember(@PathVariable UUID id, @PathVariable UUID userId) {
        groupService.addMember(id, userId);
    }

    @DeleteMapping("/{id}/members/{userId}")
    public void removeMember(@PathVariable UUID id, @PathVariable UUID userId) {
        groupService.removeMember(id, userId);
    }
}
