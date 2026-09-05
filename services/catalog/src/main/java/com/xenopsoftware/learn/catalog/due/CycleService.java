package com.xenopsoftware.learn.catalog.due;

import com.xenopsoftware.learn.catalog.assign.Assignment;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opening cycles, and keeping last year's (T-5.6's second criterion).
 *
 * <p><b>Cycles are opened on demand, not by a job.</b> A nightly job that rolls every recurring
 * assignment forward is a job that can be down, and a company whose annual training silently failed
 * to open its 2026 cycle discovers it during an audit. Deriving the cycles from the assignment's
 * own dates means the answer is the same whether anything ran or not — the rows are a cache of an
 * arithmetic that is always available, so the failure mode of the writer is a slow read rather than
 * a wrong one.
 *
 * <p><b>Concurrency is settled by the database.</b> Two requests arriving together both compute
 * "cycle 3 is missing" and both insert; the unique constraint on {@code (assignment_id,
 * cycle_number)} lets exactly one win and the loser reads what the winner wrote. That is cheaper
 * and more honest than a lock somebody has to remember to take.
 */
@Service
public class CycleService {

    private static final Logger LOG = LoggerFactory.getLogger(CycleService.class);

    /**
     * How many cycles one call will open.
     *
     * <p>A guard, not a policy. An annual assignment made in 2019 and read today legitimately needs
     * six; a bad {@code recurrence_months} of zero would need infinitely many, and a CHECK
     * constraint already refuses that. The bound is what stops a data problem becoming a hung
     * request while somebody works out which.
     */
    static final int MAX_CYCLES_PER_CALL = 64;

    private final AssignmentCycleRepository cycles;
    private final CycleOpener opener;

    public CycleService(AssignmentCycleRepository cycles, CycleOpener opener) {
        this.cycles = cycles;
        this.opener = opener;
    }

    /**
     * The cycle that is open now, opening any that are overdue to exist.
     *
     * <p>Empty for an assignment with no deadline: there is nothing to be in a cycle of.
     */
    @Transactional(readOnly = true)
    public Optional<AssignmentCycle> currentCycle(Assignment assignment, Instant now) {
        Deadlines.DueSpec spec = assignment.due();
        if (spec.kind() == DueKind.NONE) {
            return Optional.empty();
        }
        AssignmentCycle latest = cycles
            .findFirstByAssignmentIdOrderByCycleNumberDesc(assignment.getId())
            .orElseGet(() -> opener.open(assignment, 1, assignment.getAssignedAt()));

        if (!spec.recurs()) {
            return Optional.of(latest);
        }
        for (int guard = 0; guard < MAX_CYCLES_PER_CALL; guard++) {
            Optional<LocalDate> due = latest.getDueOn();
            if (due.isEmpty()) {
                // A recurring assignment reckoned from REACHED has no shared date to roll on.
                // Refused at write time; if one is ever here anyway, the honest answer is the
                // cycle that exists rather than an invented one.
                return Optional.of(latest);
            }
            // The cycle ends when its due date ends. In UTC, deliberately: the CYCLE is a company
            // level period, and the per-learner timezone applies to the DEADLINE inside it
            // (Deadlines). Rolling per learner would give one assignment as many cycle numbers as
            // it has timezones, and "the 2026 cycle" would stop meaning one thing.
            Instant endsAt = Deadlines.expiresAt(due.get(), ZoneId.of("UTC"));
            if (now.isBefore(endsAt)) {
                return Optional.of(latest);
            }
            latest = opener.open(assignment, latest.getCycleNumber() + 1, endsAt);
        }
        LOG.warn("Assignment {} needed more than {} cycles opened in one call; stopping at {}. "
            + "Check recurrence_months.", assignment.getId(), MAX_CYCLES_PER_CALL,
            latest.getCycleNumber());
        return Optional.of(latest);
    }

    /**
     * Opens cycle 1 for an assignment being created right now, in the caller's transaction.
     *
     * <p>Not for correctness — {@link #currentCycle} would open it on the first read anyway — but
     * so that "what is this assignment's deadline" is answerable from the database the moment it is
     * made, by a report that never calls this service.
     *
     * <p>It has to join the caller's transaction rather than start its own, because the assignment
     * it points at is not committed yet: a new transaction cannot see the row and the insert fails
     * on the foreign key. Nothing is opened for an assignment with no deadline; there is no period
     * to be in a cycle of.
     */
    @Transactional
    public Optional<AssignmentCycle> openFirstCycle(Assignment assignment) {
        if (assignment.due().kind() == DueKind.NONE) {
            return Optional.empty();
        }
        return Optional.of(opener.openBeside(assignment, 1, assignment.getAssignedAt()));
    }

    /** Every cycle this assignment has ever had, oldest first. Nothing here is ever rewritten. */
    @Transactional(readOnly = true)
    public List<AssignmentCycle> historyOf(UUID assignmentId) {
        return cycles.findByAssignmentIdOrderByCycleNumberAsc(assignmentId);
    }
}
