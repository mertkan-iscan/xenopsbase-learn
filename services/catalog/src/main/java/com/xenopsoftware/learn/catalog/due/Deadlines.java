package com.xenopsoftware.learn.catalog.due;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * When something is due, and whether it is late (T-5.6).
 *
 * <p><b>Pure, static, and touching neither the database nor the clock unless it is handed one.</b>
 * Everything difficult about deadlines is here — the timezone rule, the two anchors, the recurrence
 * arithmetic — and all of it is testable by calling a function with three arguments. The service
 * around it does the reading and writing and makes no decisions.
 *
 * <h2>The rule, written down (T-5.6's fifth criterion)</h2>
 *
 * <p><b>A deadline is a calendar date. It expires when that date ends in the learner's own
 * timezone. Before that instant the obligation is upcoming; from it, overdue. Overdue changes
 * nothing else</b> — it does not revoke the assignment, does not close the course, and does not
 * stop a learner doing the training. A platform whose response to somebody being late with
 * mandatory training is to prevent them doing it turns a fixable gap into a permanent one.
 *
 * <p>Three consequences of that rule worth stating, because each one is a bug if it is discovered
 * rather than decided:
 *
 * <ul>
 *   <li><b>Two people with the same due date are late at different moments.</b> Auckland's 31st
 *       ends thirteen hours before London's. That is not an inconsistency to be normalised away;
 *       it is what the date means to each of them.</li>
 *   <li><b>A learner has all of the due day.</b> The deadline is the START of the following day
 *       locally, so somebody finishing at 23:50 on the 31st is on time. Treating the deadline as
 *       the start of the 31st would file a whole day of honest work as late.</li>
 *   <li><b>A learner with no recorded timezone is reckoned in {@link #FALLBACK}.</b> UTC, and it
 *       can make somebody up to a day late earlier than their own clock would. The fix is a
 *       timezone on the person, not a guess from their address; a guess would be wrong silently
 *       and for years.</li>
 * </ul>
 */
public final class Deadlines {

    /**
     * The zone used for a learner who has not told us theirs.
     *
     * <p>UTC rather than the server's zone. A server default is worse in the way that matters: it
     * is invisible, it differs between a laptop and a cluster, and it changes when somebody moves a
     * deployment — so the same learner would become late at a different moment for a reason nobody
     * could see in the data.
     */
    public static final ZoneId FALLBACK = ZoneOffset.UTC;

    private Deadlines() {
    }

    /** How an assignment states its deadline, as the columns hold it. */
    public record DueSpec(DueKind kind, LocalDate dueOn, Integer afterDays, DueBasis basis,
                          Integer recurrenceMonths) {

        public static DueSpec none() {
            return new DueSpec(DueKind.NONE, null, null, null, null);
        }

        public static DueSpec on(LocalDate date) {
            return new DueSpec(DueKind.ABSOLUTE, date, null, null, null);
        }

        public static DueSpec within(int days, DueBasis basis) {
            return new DueSpec(DueKind.RELATIVE, null, days, basis, null);
        }

        /** The same deadline, repeating. {@code 12} is the annual mandatory training case. */
        public DueSpec repeatingEvery(int months) {
            return new DueSpec(kind, dueOn, afterDays, basis, months);
        }

        public boolean recurs() {
            return recurrenceMonths != null;
        }

        /** Whether every learner reached by this assignment shares one date. */
        public boolean isSharedAcrossLearners() {
            return kind == DueKind.ABSOLUTE || (kind == DueKind.RELATIVE && basis == DueBasis.ASSIGNED);
        }
    }

    /**
     * The date a cycle is due, where every learner shares one.
     *
     * <p>Empty for {@link DueBasis#REACHED}, whose date differs per learner and is therefore not a
     * property of the cycle at all — see {@link #dueDateForLearner}. Empty for {@link DueKind#NONE}
     * for the obvious reason.
     *
     * @param opensOn the date this cycle's obligation started: when the assignment was made, for
     *                the first cycle, and the previous cycle's due date afterwards
     */
    public static Optional<LocalDate> sharedDueDate(DueSpec spec, LocalDate opensOn) {
        return switch (spec.kind()) {
            case NONE -> Optional.empty();
            case ABSOLUTE -> Optional.of(spec.dueOn());
            case RELATIVE -> spec.basis() == DueBasis.ASSIGNED
                ? Optional.of(opensOn.plusDays(spec.afterDays()))
                : Optional.empty();
        };
    }

    /**
     * The date THIS learner's obligation is due.
     *
     * <p>The shared date where there is one; otherwise counted from when this learner was first
     * reached. A learner reached after the cycle opened counts from their own arrival, which is the
     * entire point of {@link DueBasis#REACHED}.
     *
     * @param reachedOn when this learner was first reached by the assignment, in their own zone.
     *                  Only consulted for a {@link DueBasis#REACHED} deadline
     */
    public static Optional<LocalDate> dueDateForLearner(DueSpec spec, LocalDate cycleOpensOn,
            Optional<LocalDate> cycleDueOn, LocalDate reachedOn) {
        if (spec.kind() == DueKind.RELATIVE && spec.basis() == DueBasis.REACHED) {
            // Never earlier than the cycle opened: somebody reached long before a later cycle
            // began would otherwise be handed a deadline in that cycle's past and be overdue the
            // moment it opened, for a period they were never in.
            LocalDate from = reachedOn.isBefore(cycleOpensOn) ? cycleOpensOn : reachedOn;
            return Optional.of(from.plusDays(spec.afterDays()));
        }
        return cycleDueOn;
    }

    /**
     * The instant a deadline expires for somebody in this zone: the start of the following day.
     *
     * <p>{@code atStartOfDay(zone)} rather than an offset arithmetic, so a deadline that lands on
     * the night a country changes its clocks expires when that day actually ends there.
     */
    public static Instant expiresAt(LocalDate dueOn, ZoneId zone) {
        return dueOn.plusDays(1).atStartOfDay(zone).toInstant();
    }

    /** Whether this deadline has passed for somebody in this zone. Never blocks anything. */
    public static boolean isOverdue(LocalDate dueOn, ZoneId zone, Instant now) {
        return !now.isBefore(expiresAt(dueOn, zone));
    }

    /**
     * The date the next cycle is due, given this one's.
     *
     * <p>Months rather than days, so annual training due on 28 February stays on the 28th instead
     * of drifting a day every leap year — and {@link LocalDate#plusMonths} already resolves 31
     * January plus one month to 28 February rather than throwing.
     */
    public static LocalDate nextDueDate(LocalDate dueOn, int recurrenceMonths) {
        return dueOn.plusMonths(recurrenceMonths);
    }

    /** A zone id as identity recorded it, or {@link #FALLBACK} when it is absent or nonsense. */
    public static ZoneId zoneOf(String zoneId) {
        if (zoneId == null || zoneId.isBlank()) {
            return FALLBACK;
        }
        try {
            return ZoneId.of(zoneId);
        } catch (java.time.DateTimeException e) {
            // A zone that no longer exists (they are retired) or was never valid. Falling back
            // beats failing: a deadline that cannot be computed would take the learner's whole
            // home screen with it.
            return FALLBACK;
        }
    }
}
