package com.xenopsoftware.learn.packaging.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rules a compliance record depends on (T-4.4, ADR-0107).
 *
 * <p>All three are about what happens on the SECOND visit, which is where a resume feature that
 * was only ever tested once goes wrong: a learner reopening a finished course must not lose the
 * completion, must not move its date, and must not produce a second event.
 */
class PackageRuntimeTest {

    private static final Instant JANUARY = Instant.parse("2026-01-15T09:00:00Z");
    private static final Instant MARCH = Instant.parse("2026-03-20T14:00:00Z");

    private PackageRuntime fresh() {
        return new PackageRuntime(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), JANUARY);
    }

    @Test
    @DisplayName("the save that completes it says so, and the ones after it do not")
    void onlyTheFirstCompletionAnnounces() {
        PackageRuntime runtime = fresh();

        assertThat(runtime.save(Map.of("cmi.completion_status", "incomplete"), 60, JANUARY))
            .as("not complete yet")
            .isFalse();
        assertThat(runtime.save(Map.of("cmi.completion_status", "completed"), 60, JANUARY))
            .as("this is the one")
            .isTrue();
        // A conformant package commits on every slide, and keeps saying `completed` once it is.
        // Announcing on each would put one event per slide on the bus and one row per slide
        // through catalog's idempotency check.
        assertThat(runtime.save(Map.of("cmi.completion_status", "completed"), 60, MARCH))
            .as("said again, not done again")
            .isFalse();
    }

    @Test
    @DisplayName("a package cannot revoke a completion by reopening")
    void completionIsARatchet() {
        PackageRuntime runtime = fresh();
        runtime.save(Map.of("cmi.completion_status", "completed"), 0, JANUARY);

        /*
         * THE ONE THAT WOULD COST SOMEBODY THEIR COMPLIANCE RECORD.
         *
         * A conformant SCORM package reports `incomplete` when a learner reopens a finished course
         * — that is what the standard means by a new attempt. Storing it as written would revoke a
         * completion the learner earned in January because they clicked into the course in March.
         */
        runtime.save(Map.of("cmi.completion_status", "incomplete"), 30, MARCH);

        assertThat(runtime.isCompleted()).isTrue();
        assertThat(runtime.getCompletedAt()).isEqualTo(JANUARY);
    }

    @Test
    @DisplayName("the completion date is set once and never walks forward")
    void theDateIsTheThingAnAuditorReads() {
        PackageRuntime runtime = fresh();
        runtime.save(Map.of("cmi.core.lesson_status", "passed"), 0, JANUARY);
        runtime.save(Map.of("cmi.core.lesson_status", "passed"), 0, MARCH);

        assertThat(runtime.getCompletedAt()).isEqualTo(JANUARY);
    }

    @Test
    @DisplayName("reopening a passed course does not erase the pass or the score")
    void silenceDoesNotEraseAnOutcome() {
        PackageRuntime runtime = fresh();
        runtime.save(Map.of("cmi.core.lesson_status", "passed", "cmi.core.score.raw", "88"),
            0, JANUARY);

        // What a conformant package reports on a new attempt: incomplete, and no score at all.
        runtime.save(Map.of("cmi.core.lesson_status", "incomplete"), 10, MARCH);

        assertThat(runtime.getPassed()).as("they passed in January").isTrue();
        assertThat(runtime.getScoreRaw()).hasToString("88");
    }

    @Test
    @DisplayName("a retake that says something new is believed")
    void aNewAnswerReplacesTheOld() {
        PackageRuntime runtime = fresh();
        runtime.save(Map.of("cmi.core.lesson_status", "failed", "cmi.core.score.raw", "41"),
            0, JANUARY);
        runtime.save(Map.of("cmi.core.lesson_status", "passed", "cmi.core.score.raw", "92"),
            0, MARCH);

        // The ratchet is on SILENCE, not on the value: somebody who fails and comes back and
        // passes has passed, and a record that refused to move would be worse than one that never
        // ratcheted at all.
        assertThat(runtime.getPassed()).isTrue();
        assertThat(runtime.getScoreRaw()).hasToString("92");
    }

    @Test
    @DisplayName("time in the package accumulates and is never a negative number somebody sent")
    void timeIsCorroboration() {
        PackageRuntime runtime = fresh();
        runtime.save(Map.of(), 120, JANUARY);
        runtime.save(Map.of(), 90, JANUARY);
        // A browser sending a negative delta -- a clock that stepped back, or somebody trying it --
        // must not subtract from a record.
        runtime.save(Map.of(), -5_000, JANUARY);

        assertThat(runtime.getSecondsSpent()).isEqualTo(210);
    }

    @Test
    @DisplayName("the data model comes back exactly as the package left it")
    void theMapIsStorage() {
        PackageRuntime runtime = fresh();
        Map<String, String> written = Map.of(
            "cmi.suspend_data", "{\"slide\":30,\"answers\":[1,4,2]}",
            "cmi.location", "slide-30",
            "cmi.interactions.0.student_response", "b");

        runtime.save(written, 0, JANUARY);

        // Nothing normalises, trims or reinterprets it: a package that stores 0.85 and reads back
        // 0.85000000001 is a package that fails its own comparison.
        assertThat(runtime.getData()).isEqualTo(written);
    }

    @Test
    @DisplayName("a launch counts, because cmi.core.entry depends on it")
    void launchesAreCounted() {
        PackageRuntime runtime = fresh();
        assertThat(runtime.getLaunches()).isZero();

        runtime.launched(UUID.randomUUID(), JANUARY);
        assertThat(runtime.getLaunches()).isEqualTo(1);

        runtime.launched(UUID.randomUUID(), MARCH);
        // The second launch is what makes `cmi.core.entry` answer `resume` rather than
        // `ab-initio`, which a conformant package branches on.
        assertThat(runtime.getLaunches()).isEqualTo(2);
    }

    @Test
    @DisplayName("session time accumulates into a total, and a chatty package does not inflate it")
    void totalTimeIsAccumulatedNotSummed() {
        PackageRuntime runtime = fresh();
        UUID first = UUID.randomUUID();
        runtime.launched(first, JANUARY);

        /*
         * THE MISTAKE THIS TEST EXISTS TO CATCH, and it is the one a reasonable implementation
         * makes: `session_time` is cumulative WITHIN a launch, so a package that commits three
         * times reports 12, then 20, then 30 minutes for the SAME half hour. Adding each report to
         * a running total would record 62 minutes for a 30-minute session -- and the error grows
         * with how conscientiously the package commits.
         */
        runtime.save(Map.of("cmi.core.session_time", "0000:12:00.00"), 0, JANUARY);
        runtime.save(Map.of("cmi.core.session_time", "0000:20:00.00"), 0, JANUARY);
        runtime.save(Map.of("cmi.core.session_time", "0000:30:00.00"), 0, JANUARY);

        assertThat(runtime.getSessionSeconds()).isEqualTo(30 * 60);
        assertThat(runtime.getTotalSeconds()).isEqualTo(30 * 60);

        // A second launch starts from the total the first one left, in the other vocabulary --
        // which a package built from a 1.2 template inside a 2004 export will do.
        runtime.launched(UUID.randomUUID(), MARCH);
        assertThat(runtime.getSessionSeconds()).as("a new launch is a new session").isZero();

        runtime.save(Map.of("cmi.session_time", "PT10M"), 0, MARCH);

        assertThat(runtime.getSessionSeconds()).isEqualTo(10 * 60);
        assertThat(runtime.getTotalSeconds()).as("40 minutes over two sittings").isEqualTo(40 * 60);
    }

    @Test
    @DisplayName("a package that reports no session time leaves the total where it was")
    void silenceDoesNotResetTheTotal() {
        PackageRuntime runtime = fresh();
        runtime.launched(UUID.randomUUID(), JANUARY);
        runtime.save(Map.of("cmi.core.session_time", "0001:00:00.00"), 0, JANUARY);

        runtime.launched(UUID.randomUUID(), MARCH);
        // Opened, did something, said nothing about how long it took. Plenty of real packages
        // write session_time only on Finish, and a browser that was closed never gets there.
        runtime.save(Map.of("cmi.location", "slide-4"), 300, MARCH);

        assertThat(runtime.getTotalSeconds()).as("the hour they did in January stands")
            .isEqualTo(3600);
        assertThat(runtime.getSecondsSpent()).as("and the browser's own count still moved")
            .isEqualTo(300);
    }

    @Test
    @DisplayName("the most recent launch owns the registration and the one before it is refused")
    void theNewestLaunchWins() {
        PackageRuntime runtime = fresh();
        UUID firstTab = UUID.randomUUID();
        runtime.launched(firstTab, JANUARY);
        assertThat(runtime.ownedBy(firstTab)).isTrue();

        UUID secondTab = UUID.randomUUID();
        runtime.launched(secondTab, JANUARY);

        assertThat(runtime.ownedBy(secondTab)).isTrue();
        /*
         * The first tab is still open, still running the package, and still committing. Without
         * this it would overwrite the second tab's data model with its own -- silently, because
         * both are the same learner and both requests are perfectly valid on their own.
         */
        assertThat(runtime.ownedBy(firstTab)).isFalse();
    }

    @Test
    @DisplayName("a row written before the rule existed is not locked out by it")
    void aRowWithNoSessionAcceptsAnySave() {
        PackageRuntime runtime = fresh();
        // No launched() call, which is what every row migrated by V5 looks like: active_session
        // is null. Refusing those would make the deploy of this rule the moment a learner
        // mid-course lost their place.
        assertThat(runtime.ownedBy(UUID.randomUUID())).isTrue();
        assertThat(runtime.ownedBy(null)).isTrue();
    }
}
