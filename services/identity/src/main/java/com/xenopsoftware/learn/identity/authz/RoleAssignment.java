package com.xenopsoftware.learn.identity.authz;

import com.xenopsoftware.learn.common.tenancy.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

/** A role, held by a person or a group, somewhere (T-2.3). */
@Entity
@Table(name = "role_assignment")
public class RoleAssignment extends TenantOwned {

    @Id
    @UuidGenerator
    @Column(name = "id")
    private UUID id;

    @Column(name = "role_id", nullable = false)
    private UUID roleId;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "group_id")
    private UUID groupId;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false)
    private AssignmentScopeType scopeType;

    @Column(name = "scope_id")
    private UUID scopeId;

    @Column(name = "granted_by", nullable = false)
    private UUID grantedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected RoleAssignment() {}

    public static RoleAssignment toUser(UUID roleId, UUID userId, ScopeGrant scope, UUID grantedBy) {
        RoleAssignment assignment = new RoleAssignment(roleId, scope, grantedBy);
        assignment.userId = userId;
        return assignment;
    }

    public static RoleAssignment toGroup(UUID roleId, UUID groupId, ScopeGrant scope, UUID grantedBy) {
        RoleAssignment assignment = new RoleAssignment(roleId, scope, grantedBy);
        assignment.groupId = groupId;
        return assignment;
    }

    private RoleAssignment(UUID roleId, ScopeGrant scope, UUID grantedBy) {
        this.roleId = roleId;
        this.scopeType = scope.type();
        this.scopeId = scope.targetId();
        this.grantedBy = grantedBy;
    }

    public ScopeGrant scope() {
        return new ScopeGrant(scopeType, scopeId);
    }

    public UUID getId() {
        return id;
    }

    public UUID getRoleId() {
        return roleId;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getGroupId() {
        return groupId;
    }

    public AssignmentScopeType getScopeType() {
        return scopeType;
    }

    public UUID getScopeId() {
        return scopeId;
    }

    public UUID getGrantedBy() {
        return grantedBy;
    }
}
