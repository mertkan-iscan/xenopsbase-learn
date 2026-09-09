package com.xenopsoftware.learn.assessment.scoring;

import com.xenopsoftware.learn.assessment.question.type.Correctness;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * How much of a question's marks a partly-right answer earns (T-6.4).
 *
 * <p>T-6.3 answers <em>how much was right</em> — {@link Correctness}, two integers, "3 of 5 pairs
 * matched". This decides what that is worth, and the two are separate on purpose: the arithmetic of
 * correctness belongs to the question type and never changes, while what it is worth belongs to the
 * test and an author may change it and rescore.
 *
 * <p><b>There are two modes and there is deliberately not a third.</b> Every scheme anybody asks
 * for beyond these — per-option weights, graduated bands, "half marks for close" — is a way of
 * saying that some parts of a question are worth more than others, and every one of them needs
 * {@code grade} to report <em>which</em> parts were right rather than how many. That is a different
 * contract from {@link Correctness}, and the place to put those weights would be the answer key,
 * which is immutable once served (ADR-0106). A weight that cannot be corrected without a new
 * version of the question, and therefore without orphaning every attempt's recorded form, is a
 * weight nobody can fix. The way to make one part worth more here is to make it its own question.
 */
public enum ScoringMode {

    /**
     * All of the marks or none. The default, and right for most questions.
     *
     * <p>It is also the only honest mode for a question whose parts are not independent. Three of
     * five steps of a procedure in the right order is not three fifths of knowing the procedure,
     * and an author who wants it to be says so by choosing the other mode.
     */
    ALL_OR_NOTHING {
        @Override
        public BigDecimal award(Correctness correctness, BigDecimal points) {
            return correctness.fullyRight() ? points : BigDecimal.ZERO;
        }
    },

    /**
     * The fraction that was right, of the marks available.
     *
     * <p>For a type whose {@code available} is one — single choice, true/false, numeric — this is
     * exactly {@link #ALL_OR_NOTHING}, arithmetically and without a special case. That is worth
     * knowing: an author who turns partial credit on for a whole test has not quietly changed what
     * those questions are worth.
     *
     * <p><b>It composes with T-6.3's counting rather than replacing it.</b> On multiple choice,
     * {@code credited} is already right picks minus wrong ones, floored at zero, so selecting every
     * choice earns nothing here — the mode decides whether two of three is two thirds or nothing,
     * and it never decides whether guessing everything works.
     */
    PARTIAL_CREDIT {
        @Override
        public BigDecimal award(Correctness correctness, BigDecimal points) {
            if (correctness.fullyRight()) {
                // Exact rather than divided and multiplied back, so full marks are the number the
                // author typed and not that number plus a rounding error.
                return points;
            }
            return points.multiply(BigDecimal.valueOf(correctness.credited()))
                .divide(BigDecimal.valueOf(correctness.available()), AWARD_SCALE,
                    RoundingMode.HALF_UP);
        }
    };

    /**
     * Six decimal places on a single question's award.
     *
     * <p>Stated rather than assumed, because "the arithmetic is written down" is a criterion. A
     * third of a mark is 0.333333 here, and the error is at most 5e-7 per question. The verdict is
     * a comparison at whole-percent granularity (see {@link TestScore}), so a section would need
     * on the order of ten thousand questions before the accumulated error could move a percentage
     * point — and a section of ten thousand questions has a different problem.
     */
    static final int AWARD_SCALE = 6;

    /** What this response earns, before any penalty and before the section's weighting. */
    public abstract BigDecimal award(Correctness correctness, BigDecimal points);
}
