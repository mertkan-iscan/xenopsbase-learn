package com.xenopsoftware.learn.identity.group;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Tenant-filtered by the persistence layer (T-1.1). */
public interface GroupMembershipRepository extends JpaRepository<GroupMembership, UUID> {

    Optional<GroupMembership> findByGroupIdAndUserId(UUID groupId, UUID userId);

    List<GroupMembership> findByGroupId(UUID groupId);

    long countByGroupId(UUID groupId);
}
