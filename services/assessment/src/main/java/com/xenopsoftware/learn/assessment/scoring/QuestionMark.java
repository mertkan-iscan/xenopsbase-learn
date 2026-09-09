package com.xenopsoftware.learn.assessment.scoring;

import java.math.BigDecimal;

/**
 * One question's contribution to a section (T-6.4).
 *
 * <p>Both numbers, always. {@code awarded} alone cannot be composed — six marks out of ten and six
 * out of a hundred are the same number and not the same result — and a section that only summed
 * awards would produce a score whose denominator depended on which questions happened to be drawn.
 *
 * @param awarded what this response earned, which may be negative when the test uses negative
 *                marking. The floor is applied to the SECTION, not here, so that a report can still
 *                show what a particular answer cost
 * @param points  what it was worth
 * @param graded  whether a machine could mark it at all. False for an essay or a file upload
 *                awaiting a human (T-6.3's {@code grade} returns empty for those), and such a
 *                question contributes to <b>neither</b> number — see {@link SectionMark}
 */
public record QuestionMark(BigDecimal awarded, BigDecimal points, boolean graded) {

    public QuestionMark {
        if (awarded == null || points == null) {
            throw new IllegalArgumentException("A mark is two numbers");
        }
        if (points.signum() <= 0) {
            throw new IllegalArgumentException("A question is worth more than nothing: " + points);
        }
    }

    /** A question a machine marked. */
    public static QuestionMark of(BigDecimal awarded, BigDecimal points) {
        return new QuestionMark(awarded, points, true);
    }

    /**
     * A question waiting for a person (T-6.7).
     *
     * <p>It carries its points so that "how much of this test is still unmarked" is answerable, and
     * it is excluded from both totals so that an attempt containing one essay is not reported as
     * failing that essay while nobody has read it. That is the state T-6.7 names
     * {@code AWAITING_GRADING}; this is the arithmetic underneath it, and getting it wrong is how a
     * gate reads a null score as a fail and locks somebody out of a course they passed.
     */
    public static QuestionMark awaitingAPerson(BigDecimal points) {
        return new QuestionMark(BigDecimal.ZERO, points, false);
    }
}
