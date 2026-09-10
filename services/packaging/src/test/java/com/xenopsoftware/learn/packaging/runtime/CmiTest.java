package com.xenopsoftware.learn.packaging.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reading a package's own vocabulary (T-4.4, ADR-0107).
 *
 * <p>Every case here is a real thing a real exporter emits. The two that matter most are the ones
 * that would be silently wrong rather than visibly broken: a 1.2 package's {@code failed} being
 * read as "did not finish", and a package with no test being recorded as having failed one.
 */
class CmiTest {

    @Test
    @DisplayName("SCORM 2004 splits completion from success, and both are read")
    void reads2004() {
        Map<String, String> data = Map.of(
            "cmi.completion_status", "completed",
            "cmi.success_status", "passed",
            "cmi.score.raw", "88");

        assertThat(Cmi.completed(data)).isTrue();
        assertThat(Cmi.passed(data)).contains(true);
        assertThat(Cmi.scoreRaw(data)).contains(new BigDecimal("88"));
    }

    @Test
    @DisplayName("SCORM 1.2 answers both questions with one field")
    void reads12() {
        Map<String, String> passed = Map.of("cmi.core.lesson_status", "passed");
        assertThat(Cmi.completed(passed)).isTrue();
        assertThat(Cmi.passed(passed)).contains(true);
    }

    @Test
    @DisplayName("a 1.2 “failed” is finished AND not passed, which is two different columns")
    void failedIsStillFinished() {
        Map<String, String> data = Map.of("cmi.core.lesson_status", "failed");

        // The trap: reading `failed` as "not complete" sends somebody back through a course they
        // finished, to re-fail a test they already sat.
        assertThat(Cmi.completed(data)).isTrue();
        assertThat(Cmi.passed(data)).contains(false);
    }

    @Test
    @DisplayName("a package that says nothing about passing has not failed anybody")
    void silenceIsNotFailure() {
        Map<String, String> reading = Map.of("cmi.completion_status", "completed");

        // Empty, never `false`. A column holding false here would put "failed" beside every
        // learner who finished a reading module with no test in it.
        assertThat(Cmi.completed(reading)).isTrue();
        assertThat(Cmi.passed(reading)).isEmpty();
    }

    @Test
    @DisplayName("“unknown” is the 2004 word for “not said”")
    void unknownIsNotAnAnswer() {
        Map<String, String> data = Map.of(
            "cmi.completion_status", "unknown",
            "cmi.success_status", "unknown");

        assertThat(Cmi.completed(data)).isFalse();
        assertThat(Cmi.passed(data)).isEmpty();
    }

    @Test
    @DisplayName("nothing at all is not a completion")
    void silenceIsNotCompletion() {
        assertThat(Cmi.completed(Map.of())).isFalse();
        assertThat(Cmi.completed(Map.of("cmi.core.lesson_status", "not attempted"))).isFalse();
        assertThat(Cmi.completed(Map.of("cmi.core.lesson_status", "incomplete"))).isFalse();
    }

    @Test
    @DisplayName("the case an exporter chose does not decide whether we heard it")
    void caseDoesNotMatter() {
        assertThat(Cmi.completed(Map.of("cmi.core.lesson_status", "Completed"))).isTrue();
        assertThat(Cmi.completed(Map.of("cmi.completion_status", "COMPLETED"))).isTrue();
    }

    @Test
    @DisplayName("2004 wins over 1.2 when a package writes both")
    void theSplitFieldIsMorePrecise() {
        Map<String, String> data = Map.of(
            "cmi.completion_status", "incomplete",
            // A 1.2-era template left behind in a 2004 package. The split field is the one the
            // package's own runtime was writing deliberately.
            "cmi.core.lesson_status", "completed");

        assertThat(Cmi.completed(data)).isFalse();
    }

    @Test
    @DisplayName("a score that is not a number is not a reason to fail a learner's save")
    void anUnparseableScoreIsJustAbsent() {
        assertThat(Cmi.scoreRaw(Map.of("cmi.core.score.raw", "N/A"))).isEmpty();
        assertThat(Cmi.scoreRaw(Map.of("cmi.core.score.raw", ""))).isEmpty();
    }

    @Test
    @DisplayName("the resume point is read from whichever vocabulary stored it")
    void findsTheLocation() {
        assertThat(Cmi.location(Map.of("cmi.location", "slide-30"))).contains("slide-30");
        assertThat(Cmi.location(Map.of("cmi.core.lesson_location", "12"))).contains("12");
    }
}
