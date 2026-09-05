package com.xenopsoftware.learn.catalog.structure;

import com.xenopsoftware.learn.common.tenancy.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A node: one place in a module where a content item appears (T-5.2).
 *
 * <p><b>A node is a reference, not a copy.</b> The same content item may appear in any number of
 * courses and any number of times — one video in both the onboarding course and the annual
 * refresher is one item pointed at twice. Copying it would give two items that drift, and a
 * learner who watched it in one course would get no credit in the other.
 */
@Entity
@Table(name = "course_node")
public class CourseNode extends TenantOwned {

    @Id
    private UUID id;

    @Column(name = "module_id", nullable = false)
    private UUID moduleId;

    @Column(name = "content_item_id", nullable = false)
    private UUID contentItemId;

    @Column(nullable = false)
    private BigDecimal ordinal;

    /**
     * Whether finishing this node counts towards finishing the course.
     *
     * <p>Optional nodes are visible and never block a gate (T-5.3). Default true, because
     * "counts" is what an author means by adding something to a course; optional is the
     * deliberate exception.
     */
    @Column(nullable = false)
    private boolean required;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CourseNode() {
        // Hibernate.
    }

    public static CourseNode in(UUID moduleId, UUID contentItemId, BigDecimal ordinal,
            boolean required) {
        CourseNode node = new CourseNode();
        node.id = UUID.randomUUID();
        node.moduleId = moduleId;
        node.contentItemId = contentItemId;
        node.ordinal = ordinal;
        node.required = required;
        node.createdAt = Instant.now();
        node.updatedAt = node.createdAt;
        return node;
    }

    public void moveTo(BigDecimal newOrdinal) {
        this.ordinal = newOrdinal;
        this.updatedAt = Instant.now();
    }

    /** Moves a node into another module, which is a reorder across two lists rather than a copy. */
    public void moveTo(UUID newModuleId, BigDecimal newOrdinal) {
        this.moduleId = newModuleId;
        moveTo(newOrdinal);
    }

    public void setRequired(boolean nowRequired) {
        this.required = nowRequired;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getModuleId() {
        return moduleId;
    }

    public UUID getContentItemId() {
        return contentItemId;
    }

    public BigDecimal getOrdinal() {
        return ordinal;
    }

    public boolean isRequired() {
        return required;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
