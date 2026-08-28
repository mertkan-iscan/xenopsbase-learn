package com.xenopsoftware.learn.identity.group;

import com.xenopsoftware.learn.common.tenancy.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * A node in a tenant's group tree (T-1.3).
 *
 * <p>The parent is a plain {@code UUID} rather than a {@code @ManyToOne}: nothing here wants to
 * walk the tree one lazy hop at a time, and the walking that does happen belongs to
 * {@link GroupHierarchy}, which does it in one query. An association would invite exactly the
 * per-node traversal this design exists to avoid.
 */
@Entity
@Table(name = "user_group")
public class UserGroup extends TenantOwned {

    @Id
    @UuidGenerator
    @Column(name = "id")
    private UUID id;

    /** {@code null} for a root group. */
    @Column(name = "parent_id")
    private UUID parentId;

    @Column(name = "name", nullable = false)
    private String name;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserGroup() {}

    public UserGroup(String name, UUID parentId) {
        this.name = name;
        this.parentId = parentId;
    }

    /**
     * The whole of a move (T-1.3's fourth criterion): one field on one row. No descendant row
     * moves, and no membership row is touched — membership points at this group's id, which
     * does not change when its place in the tree does.
     */
    public void moveUnder(UUID newParentId) {
        this.parentId = newParentId;
    }

    public void rename(String newName) {
        this.name = newName;
    }

    public UUID getId() {
        return id;
    }

    public UUID getParentId() {
        return parentId;
    }

    public String getName() {
        return name;
    }
}
