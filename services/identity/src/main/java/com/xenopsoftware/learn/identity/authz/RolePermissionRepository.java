package com.xenopsoftware.learn.identity.authz;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Tenant-filtered by the persistence layer (T-1.1). */
public interface RolePermissionRepository extends JpaRepository<RolePermission, UUID> {

    List<RolePermission> findByRoleId(UUID roleId);

    void deleteByRoleId(UUID roleId);
}
