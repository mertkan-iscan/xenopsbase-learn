package com.xenopsoftware.learn.packaging.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Reading a length of time out of content that was not written to be read (T-4.4).
 *
 * <p>Every string below is one an exporter actually produces or a value a package actually writes.
 * The interesting half is the second one — what happens to the strings that are nearly right —
 * because a parser that throws on those turns a formatting quirk into a learner's lost save, and
 * one that reads them as nought claims the session took no time.
 */
class TimespanTest {

    @ParameterizedTest(name = "SCORM 1.2 reads {0} as {1} seconds")
    @CsvSource({
        "0000:00:00.00,   0",
        "0000:12:00.00, 720",
        "0001:30:00.00, 5400",
        // Four-digit hours is what the standard allows and what Storyline emits.
        "0100:00:00.00, 360000",
        // The hundredths are read and dropped: nothing this platform reports is accurate to a
        // hundredth of a second, and a total carrying them would imply it was.
        "0000:00:45.75,  45",
        // Exporters are looser than the standard about the hour field. A session is not worth
        // losing over a missing leading zero.
        "0:05:00,        300",
        "00:05:00,       300",
    })
    void scorm12(String written, long seconds) {
        assertThat(Timespan.parse(written)).contains(seconds);
    }

    @ParameterizedTest(name = "SCORM 2004 reads {0} as {1} seconds")
    @CsvSource({
        "PT0H0M0S,      0",
        "PT12M,         720",
        "PT1H30M0S,     5400",
        "PT45.75S,      45",
        "P1DT2H,        93600",
        // Both are legal ways to write nothing, and both appear.
        "PT,            0",
        "P,             0",
    })
    void scorm2004(String written, long seconds) {
        assertThat(Timespan.parse(written)).contains(seconds);
    }

    @ParameterizedTest(name = "{0} is not a length of time")
    @ValueSource(strings = {
        "",
        "   ",
        "N/A",
        "12 minutes",
        // Ambiguous between five minutes and five hours. Guessing would invent the answer, and the
        // wrong guess is out by a factor of twelve.
        "05:00",
        // Legal 2004 grammar, and meaningless as a session: turning it into seconds means deciding
        // what a month is. Nothing real writes it.
        "P1M",
        "P2Y",
        // A minute field that is not a minute field.
        "0000:99:00.00",
    })
    @DisplayName("nearly-right values read as said nothing, never as zero and never as a failure")
    void unreadable(String written) {
        assertThat(Timespan.parse(written)).isEmpty();
    }

    @Test
    @DisplayName("either vocabulary is found, whichever the manifest declared")
    void bothElementNamesAreAsked() {
        // The reason is Cmi's: a 2004-declared export built from a 1.2 template writes the 1.2
        // element, and reading only the declared vocabulary records nought for the whole course.
        assertThat(Timespan.sessionSeconds(Map.of("cmi.core.session_time", "0000:05:00.00")))
            .contains(300L);
        assertThat(Timespan.sessionSeconds(Map.of("cmi.session_time", "PT5M"))).contains(300L);
        assertThat(Timespan.sessionSeconds(Map.of("cmi.location", "slide-3"))).isEmpty();
    }

    @Test
    @DisplayName("a total is written back in the vocabulary the package can read")
    void formatting() {
        // `PT1H30M0S` handed to a SCORM 1.2 course is a string that means nothing to it, and
        // `0001:30:00.00` handed to a 2004 one is the same problem mirrored.
        assertThat(Timespan.format(5400, false)).isEqualTo("0001:30:00.00");
        assertThat(Timespan.format(5400, true)).isEqualTo("PT1H30M0S");
        assertThat(Timespan.format(0, false)).isEqualTo("0000:00:00.00");
        assertThat(Timespan.format(0, true)).isEqualTo("PT0H0M0S");
    }

    @Test
    @DisplayName("what is written can be read back")
    void aRoundTrip() {
        // The one property that has to hold across the pair: the total this platform hands a
        // package is a value that platform could read if the package handed it straight back.
        for (long seconds : new long[] {0, 1, 59, 60, 3599, 3600, 86_399, 1_234_567}) {
            assertThat(Timespan.parse(Timespan.format(seconds, false))).contains(seconds);
            assertThat(Timespan.parse(Timespan.format(seconds, true))).contains(seconds);
        }
    }
}
