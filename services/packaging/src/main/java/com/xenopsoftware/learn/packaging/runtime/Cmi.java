package com.xenopsoftware.learn.packaging.runtime;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Reading the three facts this platform acts on out of a package's data model (T-4.4, ADR-0107).
 *
 * <h2>Why this is server-side and not the wrapper's job</h2>
 *
 * <p>The wrapper runs on the content origin, inside a document a customer's uploaded JavaScript
 * shares. It is the right place to hold the CMI map and the wrong place to decide what the map
 * means, because the decision is the compliance record: "completion is derived by the server" is
 * ADR-0107's title, and a wrapper that posted {@code completed: true} would be asking this service
 * to record a boolean an uploaded package computed.
 *
 * <p>So the wrapper posts <b>the data model</b>, and this reads it. A package can still assert its
 * own completion — that is the standard's contract and the exception the ADR carves out — but it
 * asserts it in the standard's vocabulary, and the translation from that vocabulary to a row is
 * ours.
 *
 * <h2>Three vocabularies, because there are three runtimes</h2>
 *
 * <p>SCORM 1.2 has one field for both ideas ({@code cmi.core.lesson_status} carries
 * {@code completed} <em>and</em> {@code passed}); SCORM 2004 split them into
 * {@code cmi.completion_status} and {@code cmi.success_status} precisely because conflating them
 * was a mistake; cmi5 and HTML5 bundles use the small API the wrapper exposes, which writes into
 * the 2004 names.
 *
 * <p>Every key is asked for in every vocabulary rather than switched on the declared profile. A
 * manifest's declaration is not always the truth about the JavaScript inside it — a 2004-declared
 * package built from a 1.2 template writes {@code cmi.core.lesson_status} and would otherwise be
 * read as having reported nothing at all.
 */
public final class Cmi {

    /** SCORM 1.2 statuses that mean the learner is finished with it. */
    private static final java.util.Set<String> COMPLETE_12 =
        java.util.Set.of("completed", "passed", "failed");

    private Cmi() {}

    /**
     * Whether the package says this learner is done.
     *
     * <p><b>{@code failed} counts as complete in SCORM 1.2, and that is not a bug.</b> The 1.2
     * field answers two questions at once, and a learner who sat the package's own test and failed
     * it has finished the material — whether they PASSED is {@link #passed}, and the two are
     * separate columns for exactly this reason. Treating a fail as "not finished" would send
     * somebody back through a course they completed to re-fail a test.
     */
    public static boolean completed(Map<String, String> data) {
        // SCORM 2004 first: when a package writes both, the split field is the more precise one.
        Optional<String> completion = value(data, "cmi.completion_status");
        if (completion.isPresent()) {
            return completion.get().equals("completed");
        }
        Optional<String> lesson = value(data, "cmi.core.lesson_status");
        return lesson.filter(COMPLETE_12::contains).isPresent();
    }

    /**
     * Whether the package says they passed, or empty when it says nothing about passing.
     *
     * <p><b>Empty is not false.</b> A course with no test neither passed nor failed anybody, and a
     * column that recorded `false` for it would put "failed" beside every learner who completed a
     * reading module. {@code unknown} is the 2004 vocabulary for exactly this and is read as empty.
     */
    public static Optional<Boolean> passed(Map<String, String> data) {
        Optional<String> success = value(data, "cmi.success_status");
        if (success.isPresent()) {
            String status = success.get();
            if (status.equals("passed")) {
                return Optional.of(true);
            }
            if (status.equals("failed")) {
                return Optional.of(false);
            }
            // `unknown`, which is the default the wrapper seeds and means "not said".
            return Optional.empty();
        }
        Optional<String> lesson = value(data, "cmi.core.lesson_status");
        if (lesson.isPresent()) {
            if (lesson.get().equals("passed")) {
                return Optional.of(true);
            }
            if (lesson.get().equals("failed")) {
                return Optional.of(false);
            }
        }
        return Optional.empty();
    }

    /**
     * The raw score the package reported, on whatever scale it chose.
     *
     * <p><b>Nothing compares this to anything.</b> SCORM 1.2's {@code score.raw} is on the
     * package's own scale, with {@code score.min} and {@code score.max} optional and frequently
     * absent; 2004's {@code score.scaled} is a fraction from -1 to 1. Deriving a percentage from
     * either without the bounds is how a course marked out of 20 reports 17%. It is stored so a
     * report can show what the package said, and read as a number rather than interpreted as one.
     *
     * <p>Unparseable is empty rather than an error: {@code score.raw} is a free-text CMI element,
     * and a package writing {@code "N/A"} into it is a package doing something ordinary and
     * slightly wrong, not a reason to fail a learner's save.
     */
    public static Optional<BigDecimal> scoreRaw(Map<String, String> data) {
        return value(data, "cmi.core.score.raw")
            .or(() -> value(data, "cmi.score.raw"))
            // 2004's scaled score, when there is no raw one. Stored as it stands -- see above:
            // multiplying it by 100 would invent a percentage the package never claimed.
            .or(() -> value(data, "cmi.score.scaled"))
            .flatMap(Cmi::number);
    }

    /** Where in the package they were, for a screen that wants to say so. Never acted on. */
    public static Optional<String> location(Map<String, String> data) {
        return value(data, "cmi.location").or(() -> value(data, "cmi.core.lesson_location"));
    }

    /**
     * A present, non-blank value, lowercased.
     *
     * <p>Lowercased because the vocabularies are defined in lower case and real packages are not
     * consistent about it — {@code "Completed"} and {@code "COMPLETED"} both appear in the wild,
     * and a case-sensitive comparison would read either as "said nothing".
     */
    private static Optional<String> value(Map<String, String> data, String key) {
        String raw = data == null ? null : data.get(key);
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(raw.strip().toLowerCase(Locale.ROOT));
    }

    private static Optional<BigDecimal> number(String raw) {
        try {
            return Optional.of(new BigDecimal(raw));
        } catch (NumberFormatException notANumber) {
            return Optional.empty();
        }
    }
}
