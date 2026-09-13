package com.xenopsoftware.learn.packaging.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What real authoring tools actually leave in the data model, and what this platform reads out of
 * it (T-4.4).
 *
 * <h2>Why these are shaped like exports rather than like assertions</h2>
 *
 * <p>{@link CmiTest} checks one element at a time, which is the right way to pin a rule and the
 * wrong way to find out whether the rules add up. A real package writes twenty elements at once,
 * several of them contradicting each other, and the failures this platform has actually met are
 * all of that kind: a 2004-declared export writing 1.2 element names, a course that reports
 * {@code failed} and expects to be recorded as finished, a status field left at its default beside
 * a score of 92.
 *
 * <p>So each fixture below is a whole map as one tool leaves it, and each assertion is about the
 * three facts the platform acts on — complete, passed, score. They are reconstructed from the
 * documented behaviour of the tools named rather than lifted from a customer's archive: a package
 * is somebody's copyrighted course, and the part that matters here is the shape of the map, which
 * is the part that is public.
 */
class ConformanceFixturesTest {

    private static final Instant NOW = Instant.parse("2026-02-01T10:00:00Z");

    private PackageRuntime runtimeOf(Map<String, String> data) {
        PackageRuntime runtime =
            new PackageRuntime(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), NOW);
        runtime.launched(UUID.randomUUID(), NOW);
        runtime.save(data, 0, NOW);
        return runtime;
    }

    /** A SCORM 1.2 export from a slide-based tool, finished and passed with a score out of 100. */
    private static Map<String, String> storylinePassed() {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("cmi.core.student_id", "e3f1c0aa-0000-4000-8000-000000000001");
        data.put("cmi.core.student_name", "Yilmaz, Deniz");
        data.put("cmi.core.lesson_location", "5");
        data.put("cmi.core.credit", "credit");
        data.put("cmi.core.lesson_status", "passed");
        data.put("cmi.core.entry", "");
        data.put("cmi.core.score.raw", "90");
        data.put("cmi.core.score.max", "100");
        data.put("cmi.core.score.min", "0");
        data.put("cmi.core.total_time", "0000:00:00.00");
        data.put("cmi.core.lesson_mode", "normal");
        data.put("cmi.core.exit", "suspend");
        data.put("cmi.core.session_time", "0000:14:32.00");
        data.put("cmi.suspend_data", "1051H210~2j1L20233...");
        data.put("cmi.launch_data", "");
        data.put("cmi.interactions.0.id", "Scene1_Slide4_MultiChoice_0_0");
        data.put("cmi.interactions.0.type", "choice");
        data.put("cmi.interactions.0.student_response", "b");
        data.put("cmi.interactions.0.result", "correct");
        return data;
    }

    @Test
    @DisplayName("a SCORM 1.2 slide export that passed is complete, passed and scored")
    void scorm12Passed() {
        PackageRuntime runtime = runtimeOf(storylinePassed());

        assertThat(runtime.isCompleted()).isTrue();
        assertThat(runtime.getPassed()).isTrue();
        assertThat(runtime.getScoreRaw()).hasToString("90");
        // Read from the package's own account of the session, not from the browser's.
        assertThat(runtime.getTotalSeconds()).isEqualTo(14 * 60 + 32);
    }

    @Test
    @DisplayName("a SCORM 1.2 export that FAILED its test has still finished the material")
    void scorm12Failed() {
        Map<String, String> data = new LinkedHashMap<>(storylinePassed());
        data.put("cmi.core.lesson_status", "failed");
        data.put("cmi.core.score.raw", "45");

        PackageRuntime runtime = runtimeOf(data);

        /*
         * THE ONE THAT LOOKS WRONG AND IS NOT. SCORM 1.2's single status field answers two
         * questions, and somebody who sat the test and failed it has finished the course.
         * Recording that as "not complete" would send them back through material they completed to
         * re-fail a test, and the thing a report needs -- did they pass -- is the other column.
         */
        assertThat(runtime.isCompleted()).isTrue();
        assertThat(runtime.getPassed()).isFalse();
        assertThat(runtime.getScoreRaw()).hasToString("45");
    }

    @Test
    @DisplayName("a SCORM 1.2 export mid-course is neither complete nor failed")
    void scorm12InProgress() {
        Map<String, String> data = new LinkedHashMap<>(storylinePassed());
        data.put("cmi.core.lesson_status", "incomplete");
        data.remove("cmi.core.score.raw");

        PackageRuntime runtime = runtimeOf(data);

        assertThat(runtime.isCompleted()).isFalse();
        // Not false. A course they have not finished has not failed them, and a column reading
        // `false` here would put a fail beside every learner still working through it.
        assertThat(runtime.getPassed()).isNull();
        assertThat(runtime.getScoreRaw()).isNull();
    }

    /** A SCORM 2004 export from a scrolling-page tool: the two statuses split, the score scaled. */
    private static Map<String, String> riseCompleted() {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("cmi.learner_id", "e3f1c0aa-0000-4000-8000-000000000002");
        data.put("cmi.learner_name", "Yilmaz, Deniz");
        data.put("cmi._version", "1.0");
        data.put("cmi.completion_status", "completed");
        data.put("cmi.success_status", "unknown");
        data.put("cmi.credit", "credit");
        data.put("cmi.mode", "normal");
        data.put("cmi.entry", "resume");
        data.put("cmi.exit", "normal");
        data.put("cmi.location", "lesson-7");
        data.put("cmi.progress_measure", "1");
        data.put("cmi.session_time", "PT22M14S");
        data.put("cmi.total_time", "PT0H0M0S");
        data.put("cmi.suspend_data", "eyJsZXNzb25zIjpbMSwyLDMsNCw1LDYsN119");
        return data;
    }

    @Test
    @DisplayName("a SCORM 2004 course with no test is complete and says nothing about passing")
    void scorm2004CompletedWithoutATest() {
        PackageRuntime runtime = runtimeOf(riseCompleted());

        assertThat(runtime.isCompleted()).isTrue();
        /*
         * `unknown` is the 2004 vocabulary for "there was no test", and it is why the column is
         * nullable. This is the single most common shape in a real catalogue -- a reading module --
         * and recording it as a fail would put one beside almost every completion in the product.
         */
        assertThat(runtime.getPassed()).isNull();
        assertThat(runtime.getTotalSeconds()).isEqualTo(22 * 60 + 14);
    }

    @Test
    @DisplayName("a SCORM 2004 assessment reports completion and success separately")
    void scorm2004Assessment() {
        Map<String, String> data = new LinkedHashMap<>(riseCompleted());
        data.put("cmi.success_status", "passed");
        data.put("cmi.score.scaled", "0.86");
        data.put("cmi.score.raw", "86");
        data.put("cmi.score.max", "100");
        data.put("cmi.scaled_passing_score", "0.8");

        PackageRuntime runtime = runtimeOf(data);

        assertThat(runtime.isCompleted()).isTrue();
        assertThat(runtime.getPassed()).isTrue();
        // The RAW score, because it is the one the package put on its own scale. `score.scaled` is
        // read only when there is no raw one -- see Cmi: deriving a percentage from a fraction
        // without the bounds is how a course marked out of 20 reports 17%.
        assertThat(runtime.getScoreRaw()).hasToString("86");
    }

    @Test
    @DisplayName("a SCORM 2004 attempt that failed the test is complete and not passed")
    void scorm2004Failed() {
        Map<String, String> data = new LinkedHashMap<>(riseCompleted());
        data.put("cmi.success_status", "failed");
        data.put("cmi.score.scaled", "0.4");

        PackageRuntime runtime = runtimeOf(data);

        assertThat(runtime.isCompleted()).isTrue();
        assertThat(runtime.getPassed()).isFalse();
        // No raw score anywhere, so the scaled one is stored AS IT STANDS. Nothing multiplies it
        // by a hundred: the package never claimed a percentage.
        assertThat(runtime.getScoreRaw()).hasToString("0.4");
    }

    @Test
    @DisplayName("a 2004-declared export that writes 1.2 element names is still read")
    void aManifestIsNotTheTruthAboutTheJavaScript() {
        /*
         * THE FIXTURE THAT MOTIVATES ASKING FOR EVERY VOCABULARY EVERY TIME.
         *
         * Rebuilt-from-a-template exports are common and they are not rare enough to ignore: the
         * manifest says 2004 because somebody changed a dropdown, and the JavaScript inside still
         * calls LMSSetValue with 1.2 names because it was copied from a course that worked. Read
         * strictly by the declared profile, this learner has reported nothing at all.
         */
        Map<String, String> data = new LinkedHashMap<>();
        data.put("cmi._version", "1.0");
        data.put("cmi.core.lesson_status", "completed");
        data.put("cmi.core.score.raw", "78");
        data.put("cmi.core.session_time", "0000:09:00.00");

        PackageRuntime runtime = runtimeOf(data);

        assertThat(runtime.isCompleted()).isTrue();
        assertThat(runtime.getScoreRaw()).hasToString("78");
        assertThat(runtime.getTotalSeconds()).isEqualTo(9 * 60);
    }

    @Test
    @DisplayName("an HTML5 bundle that reports through the wrapper's own API is read the same way")
    void html5Bundle() {
        /*
         * What the four calls in `window.xenopslearn` leave behind, which is deliberately the same
         * vocabulary a 2004 package would write. A report cannot tell the difference and does not
         * need to: both are SELF_REPORTED, and that is the distinction that matters (ADR-0107).
         */
        Map<String, String> data = new LinkedHashMap<>();
        data.put("cmi.completion_status", "completed");
        data.put("cmi.success_status", "passed");
        data.put("cmi.score.raw", "7");
        data.put("cmi.location", "chapter-3");
        data.put("cmi.suspend_data", "{\"seen\":[1,2,3]}");
        data.put("cmi.core.session_time", "0000:03:20.00");

        PackageRuntime runtime = runtimeOf(data);

        assertThat(runtime.isCompleted()).isTrue();
        assertThat(runtime.getPassed()).isTrue();
        // Seven out of whatever the bundle counts in. Nothing compares it to anything.
        assertThat(runtime.getScoreRaw()).hasToString("7");
        assertThat(runtime.getTotalSeconds()).isEqualTo(200);
    }

    @Test
    @DisplayName("an HTML5 bundle that reports nothing is complete because it was opened")
    void html5BundleThatSaysNothing() {
        // What the wrapper writes on open() for content with no runtime of its own. "Was it
        // opened" is the entire evidence available, and a gate that could never be satisfied is
        // the alternative.
        PackageRuntime runtime = runtimeOf(Map.of("cmi.completion_status", "completed"));

        assertThat(runtime.isCompleted()).isTrue();
        assertThat(runtime.getPassed()).isNull();
        assertThat(runtime.getScoreRaw()).isNull();
    }

    @Test
    @DisplayName("a package that writes rubbish into its own score does not fail the learner's save")
    void anUnparseableScoreIsNotAnError() {
        Map<String, String> data = new LinkedHashMap<>(storylinePassed());
        // Real, and more common than it should be: `score.raw` is free text in the model.
        data.put("cmi.core.score.raw", "N/A");

        PackageRuntime runtime = runtimeOf(data);

        assertThat(runtime.isCompleted()).isTrue();
        assertThat(runtime.getPassed()).isTrue();
        assertThat(runtime.getScoreRaw()).as("no score, and no exception").isNull();
    }
}
