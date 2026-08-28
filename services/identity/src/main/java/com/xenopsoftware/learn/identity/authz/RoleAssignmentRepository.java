package com.xenopsoftware.learn.identity.authz;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Tenant-filtered by the persistence layer (T-1.1) — including the resolution query, which is
 * JPQL rather than native SQL for exactly that reason: the native recursive walk in
 * {@code GroupHierarchy} has to carry {@code tenant_id} by hand, and the fewer places that is
 * true, the fewer places it can be forgotten.
 */
public interface RoleAssignmentRepository extends JpaRepository<RoleAssignment, UUID> {

    long countByRoleId(UUID roleId);

    List<RoleAssignment> findByRoleId(UUID roleId);

    /**
     * Everything the caller holds, in one query: the permissions of every role assigned to them
     * directly or to a group they are in, each paired with the scope of the assignment that
     * carried it.
     */
    @Query("""
        select rp.permissionCode, ra.scopeType, ra.scopeId
          from RoleAssignment ra, RolePermission rp
         where rp.roleId = ra.roleId
           and (ra.userId = :userId
                or (ra.groupId is not null and ra.groupId in :groupIds))
        """)
    List<Object[]> resolveFor(@Param("userId") UUID userId, @Param("groupIds") Collection<UUID> groupIds);
}
