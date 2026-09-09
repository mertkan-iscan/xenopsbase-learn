package com.xenopsoftware.learn.assessment.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The pass boundary, which T-6.4 asks to have one defined answer (T-6.4).
 *
 * <p>The criterion names the case: <b>79.5% against a pass mark of 80.</b> The answer is fail — and
 * the half that matters, and that this class is really about, is that the learner is shown
 * <b>79%</b>. A screen reading "80%" beside "failed" is the failure being designed out, and it is
 * what half-up rounding produces.
 *
 * <p>So the displayed percentage and the verdict are not two roundings that happen to agree. They
 * are one number used twice, and these tests are what holds that true if somebody later reaches for
 * a prettier-looking rounding.
 */
class PassBoundaryTest {

    @Test
    void theCaseTheCriterionNames() {
        TestScore score = scoring(795, 1000, 80);

        assertThat(score.passed()).isFalse();
        assertThat(score.percent())
            .as("79, not 80. The number on the screen may never be one a learner could hold up "
                + "against the pass mark and win")
            .isEqualTo(79);
    }

    @Test
    void exactlyTheMarkPasses() {
        assertThat(scoring(800, 1000, 80).passed()).isTrue();
        assertThat(scoring(800, 1000, 80).percent()).isEqualTo(80);
    }

    @Test
    void oneThousandthBelowTheMarkFails() {
        TestScore score = scoring(7999, 10_000, 80);

        assertThat(score.passed()).isFalse();
        assertThat(score.percent())
            .as("truncated, never rounded up: a score that ROUNDS to the pass mark without "
                + "reaching it would hand somebody a mark they did not earn")
            .isEqualTo(79);
    }

    /**
     * The property, over every whole percentage of a thousand-mark test.
     *
     * <p>Two claims at once, and the second is the one worth having: the displayed percentage is a
     * true floor of the real score, so it never overstates it; and the verdict agrees with it at
     * every pass mark, so there is no score anywhere for which a learner is shown a number that
     * contradicts what they were told.
     */
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 50, 79, 80, 99, 100})
    void theDisplayedNumberNeverContradictsTheVerdict(int passMark) {
        for (int raw = 0; raw <= 1000; raw++) {
            TestScore score = scoring(raw, 1000, passMark);

            assertThat(score.percent())
                .as("%d of 1000 displays as", raw)
                .isEqualTo(raw * 100 / 1000);
            assertThat(score.passed())
                .as("%d of 1000 against a pass mark of %d", raw, passMark)
                .isEqualTo(score.percent() >= passMark);
        }
    }

    @Test
    void aPassMarkOfZeroPassesEverybodyIncludingSomebodyWhoScoredNothing() {
        // A practice quiz nobody can fail is a real thing, and it has to be expressible as a
        // deliberate choice rather than as an omission -- which is why the column has no default.
        assertThat(scoring(0, 1000, 0).passed()).isTrue();
    }

    @Test
    void aPassMarkOfOneHundredNeedsAllOfIt() {
        assertThat(scoring(1000, 1000, 100).passed()).isTrue();
        assertThat(scoring(999, 1000, 100).passed()).isFalse();
    }

    /**
     * A provisional score below the pass mark is not a fail, and a gate must not read it as one.
     *
     * <p>{@code passed} is false here because the marks so far do not reach the bar — but
     * {@code decided} is false too, and that is the flag standing between a learner and being
     * locked out of a course while their essay sits unread (T-6.7).
     */
    @Test
    void aProvisionalScoreIsNotAVerdict() {
        TestScore score = Scores.compose(List.of(
            SectionMark.of("Marked", 1, List.of(
                QuestionMark.of(BigDecimal.ZERO, BigDecimal.ONE),
                QuestionMark.awaitingAPerson(BigDecimal.ONE))),
            SectionMark.of("Essays", 1, List.of(QuestionMark.awaitingAPerson(BigDecimal.ONE)))),
            80);

        assertThat(score.passed()).isFalse();
        assertThat(score.decided()).isFalse();
    }

    /** One section out of {@code possible}, so the scaled score is exactly {@code raw/possible}. */
    private static TestScore scoring(int raw, int possible, int passMarkPercent) {
        return Scores.compose(List.of(new SectionMark("Only", BigDecimal.valueOf(raw),
            BigDecimal.valueOf(possible), BigDecimal.ZERO, 1)), passMarkPercent);
    }
}
