package com.xenopsoftware.learn.catalog.web.rest;

import com.xenopsoftware.learn.catalog.due.AssignmentCycle;
import com.xenopsoftware.learn.catalog.due.CycleService;
import com.xenopsoftware.learn.catalog.due.Reminders;
import com.xenopsoftware.learn.common.tenancy.TenantContext;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cycles and reminders, for the administrator who has to answer for them (T-5.6).
 *
 * <p>Two reads and no writes. The schedule is set when the assignment is made; what is here is the
 * history nobody may edit and the failures nobody should have to go looking in a log for.
 *
 * <p>Authentication-only; {@code CatalogCoverageTest} carries the reason with the rest.
 */
@RestController
@RequestMapping("/api/v1")
public class DueResource {

    private final CycleService cycles;
    private final Reminders reminders;

    public DueResource(CycleService cycles, Reminders reminders) {
        this.cycles = cycles;
        this.reminders = reminders;
    }

    /**
     * @param dueOn null when every learner has their own date — a relative deadline counted from
     *              when each of them was reached
     */
    public record CycleView(UUID id, int cycleNumber, Instant opensAt, LocalDate dueOn,
                            Instant createdAt) {}

    /**
     * @param offsetDays days added to the due date: -14 is a fortnight before, +7 a week after
     */
    public record UnsentView(UUID cycleId, UUID assignmentId, UUID learnerId, int offsetDays,
                             LocalDate dueOn) {}

    /**
     * Every cycle this assignment has had, oldest first.
     *
     * <p>The 2025 row sits next to the 2026 one and still says what 2025 was — which is the whole
     * reason recurrence opens a new cycle instead of reopening the old.
     */
    @GetMapping("/assignments/{assignmentId}/cycles")
    public List<CycleView> cycles(@PathVariable UUID assignmentId) {
        return cycles.historyOf(assignmentId).stream().map(DueResource::view).toList();
    }

    /**
     * Reminders that were claimed and never delivered.
     *
     * <p>Its own endpoint because the alternative is a log nobody reads. Two things land here and
     * both matter to a different person: a provider that refused an address, and a window that
     * passed while the service was down — the second is what the pass does INSTEAD of mailing a
     * week of nudges at once, and it must not be the same as silence.
     */
    @GetMapping("/reminders/unsent")
    public List<UnsentView> unsent() {
        return reminders.unsent(TenantContext.require()).stream()
            .map(due -> new UnsentView(due.cycleId(), due.assignmentId(), due.learnerId(),
                due.offsetDays(), due.dueOn()))
            .toList();
    }

    private static CycleView view(AssignmentCycle cycle) {
        return new CycleView(cycle.getId(), cycle.getCycleNumber(), cycle.getOpensAt(),
            cycle.getDueOn().orElse(null), cycle.getCreatedAt());
    }
}
