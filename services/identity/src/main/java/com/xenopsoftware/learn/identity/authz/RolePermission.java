package com.xenopsoftware.learn.identity.authz;

import com.xenopsoftware.learn.common.tenancy.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

/** One permission inside one role. The code is the catalog's, enforced by a foreign key. */
@Entity
@Table(name = "role_permission")
public class RolePermission extends TenantOwned {

    @Id
    @UuidGenerator
    @Column(name = "id")
    private UUID id;

    @Column(name = "role_id", nullable = false)
    private UUID roleId;

    @Column(name = "permission_code", nullable = false)
    private String permissionCode;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected RolePermission() {}

    public RolePermission(UUID roleId, Permission permission) {
        this.roleId = roleId;
        this.permissionCode = permission.code();
    }

    public UUID getRoleId() {
        return roleId;
    }

    public String getPermissionCode() {
        return permissionCode;
    }

    /** The catalog entry, or empty if this row survived the code being retired (T-2.1). */
    public java.util.Optional<Permission> permission() {
        return Permission.byCode(permissionCode);
    }
}
