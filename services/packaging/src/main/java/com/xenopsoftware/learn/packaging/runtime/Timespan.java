package com.xenopsoftware.learn.packaging.runtime;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The two ways a package writes down a length of time (T-4.4).
 *
 * <h2>Why this is a class and not a call to {@code Duration.parse}</h2>
 *
 * <p>SCORM 1.2's {@code CMITimespan} is {@code HHHH:MM:SS.SS} and SCORM 2004's is an ISO 8601
 * duration ({@code PT1H30M}). They are the same idea written by two committees, and a package
 * reports its session in whichever one its runtime speaks. {@link java.time.Duration#parse} reads
 * the second and throws on the first, so a service that used it directly would silently record
 * zero for every SCORM 1.2 course in the catalogue — which is most of them.
 *
 * <h2>Unreadable is empty, never zero and never an exception</h2>
 *
 * <p>These are free-text CMI elements written by content nobody here has seen. A package that
 * writes {@code "00:12"} or {@code "12 minutes"} into one has done something ordinary and slightly
 * wrong; refusing the whole save over it would lose the learner's answers to a formatting detail,
 * and reading it as zero would claim the session took no time. Empty means "said nothing", the
 * accumulated total is left where it was, and the wall-clock corroboration this platform keeps
 * anyway (ADR-0107) is unaffected.
 */
public final class Timespan {

    /**
     * {@code HHHH:MM:SS.SS} — hours 2 to 4 digits, the fraction optional.
     *
     * <p>Real exporters are looser than the standard about the hour field, and a package writing
     * {@code 0:05:00} is not worth losing a session over — so one digit is accepted here too. What
     * is NOT relaxed is the shape: three colon-separated fields, because {@code 05:00} is
     * ambiguous between five minutes and five hours and guessing would invent the answer.
     */
    private static final Pattern SCORM_12 =
        Pattern.compile("(\\d{1,4}):([0-5]?\\d):([0-5]?\\d)(?:\\.(\\d{1,2}))?");

    /**
     * ISO 8601, restricted to the parts that can be counted in seconds.
     *
     * <p>Years and months are deliberately absent. They are legal in the 2004 grammar and
     * meaningless in a session time, and turning {@code P1M} into seconds means choosing what a
     * month is — so a value carrying one reads as "said nothing" rather than as an invented
     * number. Nothing real writes them.
     */
    private static final Pattern SCORM_2004 = Pattern.compile(
        "P(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+(?:\\.\\d+)?)S)?)?");

    private static final long SECONDS_PER_DAY = 24 * 60 * 60;
    private static final long SECONDS_PER_HOUR = 60 * 60;

    /** A century. Longer than any session, and the ceiling that stops an absurd value. */
    private static final long MAX_SECONDS = 100L * 365 * SECONDS_PER_DAY;

    private Timespan() {}

    /**
     * How long the package says the current session has lasted, in seconds.
     *
     * <p>Both element names are asked for, in both vocabularies, for {@link Cmi}'s reason: a
     * manifest's declared profile is not always the truth about the JavaScript inside it, and a
     * 2004-declared package built from a 1.2 template writes {@code cmi.core.session_time}.
     *
     * <p><b>This is the session, not the delta.</b> {@code session_time} is cumulative within one
     * launch — a package commits at slide three saying twelve minutes and at slide six saying
     * thirty — so adding each reported value to a running total would count the first twelve
     * minutes three times over. {@link PackageRuntime} adds it to the total the session STARTED
     * at, which is the accumulation the standard describes.
     */
    public static Optional<Long> sessionSeconds(Map<String, String> data) {
        return read(data, "cmi.core.session_time").or(() -> read(data, "cmi.session_time"));
    }

    private static Optional<Long> read(Map<String, String> data, String key) {
        String raw = data == null ? null : data.get(key);
        return raw == null ? Optional.empty() : parse(raw);
    }

    /** Either vocabulary, in seconds, or empty when it is not a length of time. */
    public static Optional<Long> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String value = raw.strip().toUpperCase(Locale.ROOT);
        Matcher iso = SCORM_2004.matcher(value);
        if (value.startsWith("P") && iso.matches()) {
            // "P" and "PT" alone match the pattern with every group absent, which is nought
            // seconds written as a duration rather than a failure to write one.
            long seconds = group(iso, 1) * SECONDS_PER_DAY
                + group(iso, 2) * SECONDS_PER_HOUR
                + group(iso, 3) * 60
                + fractional(iso.group(4));
            return bounded(seconds);
        }
        Matcher classic = SCORM_12.matcher(value);
        if (classic.matches()) {
            long seconds = Long.parseLong(classic.group(1)) * SECONDS_PER_HOUR
                + Long.parseLong(classic.group(2)) * 60
                + Long.parseLong(classic.group(3));
            // The hundredths are read and dropped. Nothing this platform reports is accurate to a
            // hundredth of a second, and a total that carried them would imply it was.
            return bounded(seconds);
        }
        return Optional.empty();
    }

    /**
     * A total, written the way the package expects to read it back.
     *
     * <p>{@code cmi.core.total_time} and {@code cmi.total_time} are read-only elements the LMS
     * fills in, and a package that displays "time on this course" is displaying this string. In
     * the wrong vocabulary it renders as blank or as a parse error inside somebody else's
     * JavaScript, which is why the profile decides the format rather than a default.
     */
    public static String format(long seconds, boolean scorm2004) {
        long safe = Math.max(seconds, 0);
        if (scorm2004) {
            long hours = safe / SECONDS_PER_HOUR;
            long minutes = (safe % SECONDS_PER_HOUR) / 60;
            long rest = safe % 60;
            return "PT" + hours + "H" + minutes + "M" + rest + "S";
        }
        // Four digits for the hours, which is the widest CMITimespan allows and what every
        // exporter emits. A 1.2 package parsing this with a fixed-width read gets what it expects.
        return String.format(Locale.ROOT, "%04d:%02d:%02d.00",
            safe / SECONDS_PER_HOUR, (safe % SECONDS_PER_HOUR) / 60, safe % 60);
    }

    private static long group(Matcher matcher, int index) {
        String value = matcher.group(index);
        return value == null ? 0 : Long.parseLong(value);
    }

    /** Seconds with a fraction, truncated rather than rounded — see the note in {@link #parse}. */
    private static long fractional(String value) {
        return value == null ? 0 : (long) Double.parseDouble(value);
    }

    private static Optional<Long> bounded(long seconds) {
        return seconds < 0 || seconds > MAX_SECONDS ? Optional.empty() : Optional.of(seconds);
    }
}
