package com.xenopsoftware.learn.catalog.due;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssignmentCycleRepository extends JpaRepository<AssignmentCycle, UUID> {

    /** Every cycle of one assignment, oldest first — the history the criterion says to preserve. */
    List<AssignmentCycle> findByAssignmentIdOrderByCycleNumberAsc(UUID assignmentId);

    Optional<AssignmentCycle> findFirstByAssignmentIdOrderByCycleNumberDesc(UUID assignmentId);

    /**
     * Cycles whose shared deadline falls in a window — what a reminder pass scans.
     *
     * <p>Bounded by the window rather than by "every open cycle", so the work of a pass is
     * proportional to what is due around now and not to everything the company has ever assigned.
     */
    List<AssignmentCycle> findByDueOnBetween(LocalDate from, LocalDate to);

    /**
     * Cycles with no shared deadline: every learner has their own ({@link DueBasis#REACHED}).
     *
     * <p>These cannot be narrowed by date here, because the date is not in the row. The pass
     * narrows them per learner instead, and the set is bounded by the number of ASSIGNMENTS of
     * that kind rather than by the number of people they reach.
     */
    List<AssignmentCycle> findByDueOnIsNull();
}
