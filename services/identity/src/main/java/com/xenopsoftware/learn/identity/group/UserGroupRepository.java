package com.xenopsoftware.learn.identity.group;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Tenant-filtered by the persistence layer (T-1.1): a group id from another tenant is absent. */
public interface UserGroupRepository extends JpaRepository<UserGroup, UUID> {

    List<UserGroup> findByParentId(UUID parentId);

    List<UserGroup> findByParentIdIsNull();
}
