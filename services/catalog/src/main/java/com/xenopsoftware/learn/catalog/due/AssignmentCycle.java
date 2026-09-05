package com.xenopsoftware.learn.catalog.due;

import com.xenopsoftware.learn.common.tenancy.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * One period of a recurring obligation (T-5.6, question 3).
 *
 * <p><b>Annual training opens a new cycle; it does not reopen last year's.</b> Reopening destroys
 * the only thing a compliance report is for — "did they do it in 2025" stops being answerable the
 * moment 2026 reuses the row. A fresh assignment each year would split one standing obligation
 * into a pile of them, collide with the partial unique index that stops the same course being
 * assigned twice, and turn "why do I have this" into a list that grows forever.
 *
 * <p><b>Every assignment with a deadline has at least one cycle, recurring or not.</b> A
 * once-only obligation is the one-cycle case rather than a separate shape, so reminders, overdue
 * and history have one thing to hang off instead of two nearly identical paths of which the rare
 * one is wrong.
 */
@Entity
@Table(name = "assignment_cycle")
public class AssignmentCycle extends TenantOwned {

    @Id
    private UUID id;

    @Column(name = "assignment_id", nullable = false)
    private UUID assignmentId;

    /** 1, 2, 3… The 2026 run is cycle 2, and cycle 1 still sits beside it saying what 2025 was. */
    @Column(name = "cycle_number", nullable = false)
    private int cycleNumber;

    @Column(name = "opens_at", nullable = false)
    private Instant opensAt;

    /** Null when every learner has their own date — see {@link DueBasis#REACHED}. */
    @Column(name = "due_on")
    private LocalDate dueOn;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AssignmentCycle() {
        // Hibernate.
    }

    public static AssignmentCycle of(UUID assignmentId, int cycleNumber, Instant opensAt,
            LocalDate dueOn) {
        AssignmentCycle cycle = new AssignmentCycle();
        cycle.id = UUID.randomUUID();
        cycle.assignmentId = assignmentId;
        cycle.cycleNumber = cycleNumber;
        cycle.opensAt = opensAt;
        cycle.dueOn = dueOn;
        cycle.createdAt = Instant.now();
        return cycle;
    }

    public UUID getId() {
        return id;
    }

    public UUID getAssignmentId() {
        return assignmentId;
    }

    public int getCycleNumber() {
        return cycleNumber;
    }

    public Instant getOpensAt() {
        return opensAt;
    }

    public Optional<LocalDate> getDueOn() {
        return Optional.ofNullable(dueOn);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
