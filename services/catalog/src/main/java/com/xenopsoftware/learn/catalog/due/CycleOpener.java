package com.xenopsoftware.learn.catalog.due;

import com.xenopsoftware.learn.catalog.assign.Assignment;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes one cycle row, in its own transaction (T-5.6).
 *
 * <p><b>Its own bean, and that is the entire reason it exists.</b> Spring's {@code @Transactional}
 * is a proxy, so a method calling another method OF THE SAME BEAN gets no proxy and no new
 * transaction — {@code REQUIRES_NEW} written on a private helper of {@link CycleService} would
 * have compiled, read correctly, and done nothing. Two things depend on it actually working: a
 * learner reading their home screen inside a read-only transaction must still be able to have a
 * missing cycle opened, and losing the race to insert one must not mark the reader's transaction
 * rollback-only.
 */
@Component
public class CycleOpener {

    private final AssignmentCycleRepository cycles;

    public CycleOpener(AssignmentCycleRepository cycles) {
        this.cycles = cycles;
    }

    /**
     * Opens cycle {@code number}, or returns the one somebody else opened first.
     *
     * <p>Concurrency is settled by the unique constraint on {@code (assignment_id, cycle_number)}:
     * two requests both compute "cycle 3 is missing" and both insert, exactly one wins, and the
     * loser reads what the winner wrote. Cheaper and more honest than a lock somebody has to
     * remember to take.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AssignmentCycle open(Assignment assignment, int number, Instant opensAt) {
        return write(assignment, number, opensAt);
    }

    /**
     * The same write, joining the caller's transaction instead of starting its own.
     *
     * <p>For the one caller that has just created the assignment and has not committed it yet.
     * {@link #open}'s new transaction cannot see that row — the insert fails on
     * {@code assignment_cycle_assignment_id_fkey}, which is the same shape of mistake as writing
     * an audit row in {@code REQUIRES_NEW} against a person who has not been committed, and this
     * repository has now met it twice. A new transaction is the right default for the READ path,
     * where the assignment is old news; it is exactly wrong on the write path that made it.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public AssignmentCycle openBeside(Assignment assignment, int number, Instant opensAt) {
        return write(assignment, number, opensAt);
    }

    private AssignmentCycle write(Assignment assignment, int number, Instant opensAt) {
        LocalDate opensOn = opensAt.atZone(ZoneId.of("UTC")).toLocalDate();
        Deadlines.DueSpec spec = assignment.due();
        LocalDate dueOn = number == 1
            ? Deadlines.sharedDueDate(spec, opensOn).orElse(null)
            : nextSharedDate(assignment, spec, number, opensOn);
        try {
            return cycles.saveAndFlush(
                AssignmentCycle.of(assignment.getId(), number, opensAt, dueOn));
        } catch (DataIntegrityViolationException lostTheRace) {
            return cycles.findByAssignmentIdOrderByCycleNumberAsc(assignment.getId()).stream()
                .filter(cycle -> cycle.getCycleNumber() == number)
                .findFirst()
                .orElseThrow(() -> lostTheRace);
        }
    }

    /**
     * The date cycle {@code number} is due.
     *
     * <p>Computed from the FIRST cycle's date plus whole periods, not from the previous cycle's,
     * so an annual deadline of 28 February stays on the 28th instead of walking forward a day at a
     * time through the leap years.
     */
    private LocalDate nextSharedDate(Assignment assignment, Deadlines.DueSpec spec, int number,
            LocalDate opensOn) {
        Optional<LocalDate> first = cycles
            .findByAssignmentIdOrderByCycleNumberAsc(assignment.getId()).stream()
            .filter(cycle -> cycle.getCycleNumber() == 1)
            .findFirst()
            .flatMap(AssignmentCycle::getDueOn);
        if (first.isEmpty() || !spec.recurs()) {
            return Deadlines.sharedDueDate(spec, opensOn).orElse(null);
        }
        return Deadlines.nextDueDate(first.get(), spec.recurrenceMonths() * (number - 1));
    }
}
