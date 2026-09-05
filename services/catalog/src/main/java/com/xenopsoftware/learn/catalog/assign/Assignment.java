package com.xenopsoftware.learn.catalog.assign;

import com.xenopsoftware.learn.catalog.due.Deadlines;
import com.xenopsoftware.learn.catalog.due.DueBasis;
import com.xenopsoftware.learn.catalog.due.DueKind;
import com.xenopsoftware.learn.common.tenancy.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One obligation: this target must do this thing (T-5.5).
 *
 * <p><b>A group assignment is ONE row.</b> Not one per member — assigning a course to a five
 * thousand person department writes a single row here, and who it reaches is resolved when
 * somebody reads. Materialising per member would make the assignment itself a batch job, would
 * need a second batch job every time somebody joins or leaves the group, and would leave the two
 * disagreeing whenever one of them failed halfway.
 */
@Entity
@Table(name = "assignment")
public class Assignment extends TenantOwned {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 16)
    private TargetKind targetType;

    /** Null for a company-wide assignment: "everyone" is not an id. */
    @Column(name = "target_id")
    private UUID targetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reference_type", nullable = false, length = 16)
    private ReferenceKind referenceType;

    @Column(name = "reference_id", nullable = false)
    private UUID referenceId;

    /** The course's structure_version when this was made. Null for a bare content item. */
    @Column(name = "pinned_version")
    private Long pinnedVersion;

    @Column(name = "assigned_by", nullable = false)
    private UUID assignedBy;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    /**
     * The deadline, in the four columns that state it (T-5.6).
     *
     * <p>A CHECK constraint makes each kind carry exactly the columns it means and none of the
     * others, so a RELATIVE assignment cannot also be holding a stale absolute date that a second
     * reader would believe.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "due_kind", nullable = false, length = 16)
    private DueKind dueKind = DueKind.NONE;

    /** A DATE, not an instant: it expires when that day ends where the LEARNER is. */
    @Column(name = "due_on")
    private LocalDate dueOn;

    @Column(name = "due_after_days")
    private Integer dueAfterDays;

    @Enumerated(EnumType.STRING)
    @Column(name = "due_basis", length = 16)
    private DueBasis dueBasis;

    /** 12 for annual mandatory training; null for an obligation that is due once. */
    @Column(name = "recurrence_months")
    private Integer recurrenceMonths;

    protected Assignment() {
        // Hibernate.
    }

    public static Assignment of(TargetKind targetType, UUID targetId, ReferenceKind referenceType,
            UUID referenceId, Long pinnedVersion, UUID assignedBy) {
        Assignment assignment = new Assignment();
        assignment.id = UUID.randomUUID();
        assignment.targetType = targetType;
        assignment.targetId = targetType == TargetKind.TENANT ? null : targetId;
        assignment.referenceType = referenceType;
        assignment.referenceId = referenceId;
        assignment.pinnedVersion = pinnedVersion;
        assignment.assignedBy = assignedBy;
        assignment.assignedAt = Instant.now();
        return assignment;
    }

    /**
     * Withdraws the obligation without erasing that it existed.
     *
     * <p>Revoked rather than deleted, because "this was withdrawn" and "this never happened" are
     * different facts and a compliance report a year later has to tell them apart. What it does
     * NOT touch is anything the learner already finished — completion is recorded against the
     * node, not against the assignment, so the history survives the obligation (T-5.5's fourth
     * criterion).
     */
    public void revoke() {
        if (revokedAt == null) {
            revokedAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public TargetKind getTargetType() {
        return targetType;
    }

    public UUID getTargetId() {
        return targetId;
    }

    public ReferenceKind getReferenceType() {
        return referenceType;
    }

    public UUID getReferenceId() {
        return referenceId;
    }

    public Long getPinnedVersion() {
        return pinnedVersion;
    }

    public UUID getAssignedBy() {
        return assignedBy;
    }

    public Instant getAssignedAt() {
        return assignedAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public boolean isLive() {
        return revokedAt == null;
    }

    /** The deadline, as the pure arithmetic wants it. */
    public Deadlines.DueSpec due() {
        return new Deadlines.DueSpec(dueKind, dueOn, dueAfterDays, dueBasis, recurrenceMonths);
    }

    /**
     * Sets the deadline, refusing a shape the database would refuse.
     *
     * <p>Checked here as well as by the CHECK constraint, because a constraint violation reaches
     * the caller as a 500 about a constraint name. The database is what makes the rule true; this
     * is what makes the refusal answerable.
     */
    public void setDue(Deadlines.DueSpec spec) {
        switch (spec.kind()) {
            case NONE -> {
                if (spec.dueOn() != null || spec.afterDays() != null || spec.basis() != null) {
                    throw new IllegalArgumentException(
                        "An assignment with no deadline cannot also carry one");
                }
            }
            case ABSOLUTE -> {
                if (spec.dueOn() == null || spec.afterDays() != null || spec.basis() != null) {
                    throw new IllegalArgumentException(
                        "An absolute deadline is a date and nothing else");
                }
            }
            case RELATIVE -> {
                if (spec.dueOn() != null || spec.afterDays() == null || spec.afterDays() <= 0
                        || spec.basis() == null) {
                    throw new IllegalArgumentException(
                        "A relative deadline needs a positive number of days and a basis: "
                        + "ASSIGNED gives everybody the same date, REACHED gives each learner "
                        + "their own (T-5.6)");
                }
            }
        }
        if (spec.recurrenceMonths() != null
                && (spec.recurrenceMonths() <= 0 || spec.kind() == DueKind.NONE)) {
            throw new IllegalArgumentException(
                "Recurrence needs a deadline to recur against: \"every year, no due date\" has "
                + "no meaning, and would produce cycles that never end");
        }
        this.dueKind = spec.kind();
        this.dueOn = spec.dueOn();
        this.dueAfterDays = spec.afterDays();
        this.dueBasis = spec.basis();
        this.recurrenceMonths = spec.recurrenceMonths();
    }
}
