package com.xenopsoftware.learn.identity.web.rest;

import com.xenopsoftware.learn.identity.authz.Permission;
import com.xenopsoftware.learn.identity.authz.PermissionSide;
import com.xenopsoftware.learn.identity.authz.Role;
import com.xenopsoftware.learn.identity.authz.RoleException;
import com.xenopsoftware.learn.identity.authz.RoleService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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
 * Roles, built and edited at runtime by selecting permissions (T-2.2).
 *
 * <p>Authentication-only for the same reason the group endpoints are: {@code role:read} and
 * {@code role:manage} are catalogued and the evaluator is live, but nothing can hold a grant
 * until assignments exist (T-2.3). That makes this the last stop on a short list —
 * <b>T-2.3 is what lets every endpoint in this service finally state its own check.</b>
 */
@RestController
@RequestMapping("/api/v1/roles")
public class RoleResource {

    private final RoleService roleService;

    public RoleResource(RoleService roleService) {
        this.roleService = roleService;
    }

    public record CreateRoleRequest(String name, String description) {}

    public record RenameRoleRequest(String name, String description) {}

    public record PermissionsRequest(Set<String> permissions) {}

    public record RoleView(UUID id, String name, String description, String side, boolean system,
                           Set<String> permissions) {}

    @GetMapping
    public List<RoleView> all() {
        return roleService.all().stream().map(this::view).toList();
    }

    @GetMapping("/{id}")
    public RoleView get(@PathVariable UUID id) {
        return view(roleService.get(id));
    }

    @PostMapping
    public RoleView create(@RequestBody CreateRoleRequest request) {
        return view(roleService.create(request.name(), request.description(), PermissionSide.TENANT));
    }

    @PutMapping("/{id}")
    public RoleView rename(@PathVariable UUID id, @RequestBody RenameRoleRequest request) {
        return view(roleService.rename(id, request.name(), request.description()));
    }

    /** The whole set, replaced. See {@code RoleService#setPermissions} for why not add/remove. */
    @PutMapping("/{id}/permissions")
    public RoleView setPermissions(@PathVariable UUID id, @RequestBody PermissionsRequest request) {
        Set<Permission> permissions = request.permissions().stream()
            .map(code -> Permission.byCode(code).orElseThrow(() -> new RoleException(
                code + " is not in the permission catalog")))
            .collect(Collectors.toSet());
        return view(roleService.setPermissions(id, permissions));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable UUID id,
            @RequestParam(name = "cascade", defaultValue = "false") boolean cascade) {
        if (cascade) {
            roleService.deleteCascading(id);
        } else {
            roleService.delete(id);
        }
    }

    private RoleView view(Role role) {
        return new RoleView(role.getId(), role.getName(), role.getDescription(),
            role.getSide().name(), role.isSystem(), roleService.currentCodes(role.getId()));
    }
}
