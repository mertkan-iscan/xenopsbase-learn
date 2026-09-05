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

/** One requirement of one gate (T-5.3). */
@Entity
@Table(name = "gate_requirement")
public class GateRequirement extends TenantOwned {

    @Id
    private UUID id;

    @Column(name = "gate_id", nullable = false)
    private UUID gateId;

    @Enumerated(EnumType.STRING)
    @Column(name = "requirement_type", nullable = false, length = 16)
    private StructurePart requirementType;

    @Column(name = "requirement_id", nullable = false)
    private UUID requirementId;

    @Enumerated(EnumType.STRING)
    @Column(name = "required_state", nullable = false, length = 16)
    private RequiredState requiredState;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected GateRequirement() {
        // Hibernate.
    }

    public static GateRequirement of(UUID gateId, StructurePart type, UUID requirementId,
            RequiredState state) {
        GateRequirement requirement = new GateRequirement();
        requirement.id = UUID.randomUUID();
        requirement.gateId = gateId;
        requirement.requirementType = type;
        requirement.requirementId = requirementId;
        requirement.requiredState = state;
        requirement.createdAt = Instant.now();
        return requirement;
    }

    public UUID getId() {
        return id;
    }

    public UUID getGateId() {
        return gateId;
    }

    public StructurePart getRequirementType() {
        return requirementType;
    }

    public UUID getRequirementId() {
        return requirementId;
    }

    public RequiredState getRequiredState() {
        return requiredState;
    }
}
