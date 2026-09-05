package com.xenopsoftware.learn.catalog.gate;

import com.xenopsoftware.learn.common.tenancy.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** The row behind a {@link GateRule}: what it guards, and how its requirements combine (T-5.3). */
@Entity
@Table(name = "gate")
public class Gate extends TenantOwned {

    @Id
    private UUID id;

    @Column(name = "course_id", nullable = false)
    private UUID courseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 16)
    private StructurePart targetType;

    @Column(name = "target_id", nullable = false)
    private UUID targetId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Combinator combinator;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Gate() {
        // Hibernate.
    }

    public static Gate on(UUID courseId, StructurePart targetType, UUID targetId,
            Combinator combinator) {
        Gate gate = new Gate();
        gate.id = UUID.randomUUID();
        gate.courseId = courseId;
        gate.targetType = targetType;
        gate.targetId = targetId;
        gate.combinator = combinator;
        gate.createdAt = Instant.now();
        gate.updatedAt = gate.createdAt;
        return gate;
    }

    public void recombine(Combinator newCombinator) {
        this.combinator = newCombinator;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getCourseId() {
        return courseId;
    }

    public StructurePart getTargetType() {
        return targetType;
    }

    public UUID getTargetId() {
        return targetId;
    }

    public Combinator getCombinator() {
        return combinator;
    }
}
