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
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * A role a customer built by selecting permissions (T-2.2).
 *
 * <p>The role's identity is {@link #id} and only {@link #id}: assignments (T-2.3), audit entries
 * and every future reference point at it, so {@link #rename} changes what a human reads and
 * nothing else. A role keyed by name would make renaming a data migration, which is how a
 * product ends up refusing to let customers rename things.
 */
@Entity
@Table(name = "app_role")
public class Role extends TenantOwned {

    @Id
    @UuidGenerator
    @Column(name = "id")
    private UUID id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "side", nullable = false)
    private PermissionSide side;

    @Column(name = "system", nullable = false)
    private boolean system;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Role() {}

    public Role(String name, String description, PermissionSide side) {
        this.name = name;
        this.description = description;
        this.side = side;
        this.system = false;
    }

    public void rename(String newName, String newDescription) {
        this.name = newName;
        this.description = newDescription;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public PermissionSide getSide() {
        return side;
    }

    public boolean isSystem() {
        return system;
    }
}
