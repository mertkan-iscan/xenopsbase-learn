package com.xenopsoftware.learn.catalog.due;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The deadline arithmetic, on its own (T-5.6).
 *
 * <p>No Spring and no database, because none of this needs one — which is the point of
 * {@link Deadlines} being static and pure. Everything difficult about deadlines is here, and a
 * failure in this file names the rule that broke rather than a request that returned 500.
 */
class DeadlinesTest {

    private static final ZoneId AUCKLAND = ZoneId.of("Pacific/Auckland");
    private static final ZoneId LOS_ANGELES = ZoneId.of("America/Los_Angeles");
    private static final LocalDate THE_THIRTY_FIRST = LocalDate.of(2026, 3, 31);

    @Test
    void aLearnerHasAllOfTheDayItIsDue() {
        Instant lateThatEvening = LocalDate.of(2026, 3, 31).atTime(23, 50)
            .atZone(LOS_ANGELES).toInstant();

        assertThat(Deadlines.isOverdue(THE_THIRTY_FIRST, LOS_ANGELES, lateThatEvening))
            .as("a deadline is the END of the due day; treating it as the start files a whole "
                + "day of honest work as late")
            .isFalse();
    }

    @Test
    void twoPeopleWithTheSameDueDateAreLateAtDifferentMoments() {
        Instant aucklandHasEnded = LocalDate.of(2026, 4, 1).atTime(0, 30)
            .atZone(AUCKLAND).toInstant();

        assertThat(Deadlines.isOverdue(THE_THIRTY_FIRST, AUCKLAND, aucklandHasEnded)).isTrue();
        assertThat(Deadlines.isOverdue(THE_THIRTY_FIRST, LOS_ANGELES, aucklandHasEnded))
            .as("the same instant, and Los Angeles is still on the 30th -- which is not an "
                + "inconsistency to normalise away, it is what the date means to each of them")
            .isFalse();
    }

    @Test
    void aDeadlineOnTheNightTheClocksChangeExpiresWhenThatDayActuallyEnds() {
        // 29 March 2026 is when Europe/Berlin loses an hour. The day is 23 hours long, and the
        // deadline is the start of the 30th regardless -- which is what atStartOfDay(zone) gives
        // and what adding 24 hours would not.
        ZoneId berlin = ZoneId.of("Europe/Berlin");
        Instant expires = Deadlines.expiresAt(LocalDate.of(2026, 3, 29), berlin);

        assertThat(expires).isEqualTo(LocalDate.of(2026, 3, 30).atStartOfDay(berlin).toInstant());
        assertThat(Deadlines.isOverdue(LocalDate.of(2026, 3, 29), berlin,
            expires.minusSeconds(1))).isFalse();
    }

    @Test
    void anAbsoluteDeadlineDoesNotMoveForALateJoiner() {
        Deadlines.DueSpec audit = Deadlines.DueSpec.on(THE_THIRTY_FIRST);
        LocalDate cycleOpened = LocalDate.of(2026, 1, 1);

        LocalDate forTheJoiner = Deadlines.dueDateForLearner(audit, cycleOpened,
            Deadlines.sharedDueDate(audit, cycleOpened), LocalDate.of(2026, 3, 25)).orElseThrow();

        assertThat(forTheJoiner)
            .as("the audit does not move because somebody was hired last week, and a quiet "
                + "extension would report compliance the company does not have")
            .isEqualTo(THE_THIRTY_FIRST);
    }

    @Test
    void aRelativeDeadlineFromReachedGivesTheLateJoinerTheirOwnClock() {
        Deadlines.DueSpec onboarding = Deadlines.DueSpec.within(30, DueBasis.REACHED);
        LocalDate cycleOpened = LocalDate.of(2026, 1, 1);

        assertThat(Deadlines.sharedDueDate(onboarding, cycleOpened))
            .as("there is no shared date to put on the cycle -- everybody has their own")
            .isEmpty();
        assertThat(Deadlines.dueDateForLearner(onboarding, cycleOpened, Optional.empty(),
            LocalDate.of(2026, 11, 20)))
            .as("thirty days from when they joined, not from a date they had nothing to do with")
            .contains(LocalDate.of(2026, 12, 20));
    }

    @Test
    void aRelativeDeadlineFromAssignedIsTheSameDateForEverybody() {
        Deadlines.DueSpec spec = Deadlines.DueSpec.within(30, DueBasis.ASSIGNED);
        LocalDate cycleOpened = LocalDate.of(2026, 1, 1);
        Optional<LocalDate> shared = Deadlines.sharedDueDate(spec, cycleOpened);

        assertThat(shared).contains(LocalDate.of(2026, 1, 31));
        assertThat(Deadlines.dueDateForLearner(spec, cycleOpened, shared,
            LocalDate.of(2026, 1, 29)))
            .as("reckoned from the assignment, so a late joiner inherits it")
            .contains(LocalDate.of(2026, 1, 31));
    }

    @Test
    void somebodyReachedBeforeALaterCycleOpenedIsNotOverdueTheMomentItDoes() {
        Deadlines.DueSpec onboarding = Deadlines.DueSpec.within(30, DueBasis.REACHED);
        LocalDate cycleTwoOpened = LocalDate.of(2027, 1, 1);

        assertThat(Deadlines.dueDateForLearner(onboarding, cycleTwoOpened, Optional.empty(),
            LocalDate.of(2025, 5, 1)))
            .as("counting from a date in the new cycle's past would make them overdue for a "
                + "period they were never in")
            .contains(LocalDate.of(2027, 1, 31));
    }

    @Test
    void anAnnualDeadlineDoesNotDriftThroughLeapYears() {
        LocalDate lastDayOfFebruary = LocalDate.of(2024, 2, 29);

        assertThat(Deadlines.nextDueDate(lastDayOfFebruary, 12)).isEqualTo(LocalDate.of(2025, 2, 28));
        assertThat(Deadlines.nextDueDate(lastDayOfFebruary, 48))
            .as("four years on, and back to the 29th -- because the arithmetic is in months from "
                + "the FIRST cycle rather than a day added at a time")
            .isEqualTo(LocalDate.of(2028, 2, 29));
    }

    @Test
    void aLearnerWhoHasNotSaidWhereTheyAreIsReckonedInUtcRatherThanAGuess() {
        assertThat(Deadlines.zoneOf(null)).isEqualTo(Deadlines.FALLBACK);
        assertThat(Deadlines.zoneOf("  ")).isEqualTo(Deadlines.FALLBACK);
        assertThat(Deadlines.zoneOf("Mars/Olympus_Mons"))
            .as("a zone that was retired, or was never valid, must not take a whole home screen "
                + "down with it")
            .isEqualTo(Deadlines.FALLBACK);
        assertThat(Deadlines.zoneOf("Europe/Istanbul")).isEqualTo(ZoneId.of("Europe/Istanbul"));
    }

    @Test
    void anAssignmentWithNoDeadlineHasNoDueDateAtAll() {
        assertThat(Deadlines.sharedDueDate(Deadlines.DueSpec.none(), LocalDate.of(2026, 1, 1)))
            .as("\"no deadline\" is a real answer, and nothing here may invent one")
            .isEmpty();
    }
}
