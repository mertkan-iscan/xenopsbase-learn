package com.xenopsoftware.learn.assessment.review;

/**
 * How much of their own paper a learner may see back (T-6.9).
 *
 * <p>Ordered from least to most, and the order is load-bearing: {@link #atLeast} is how the review
 * builder asks "may I include this", so a level added in the middle is included by everything above
 * it without a switch anywhere having to learn about it.
 */
public enum ReviewVisibility {

    /**
     * The score, and nothing about the questions. <b>The default.</b>
     *
     * <p>It discloses nothing at all: a learner already knows what they answered, and the number is
     * the minimum a person is owed after sitting an exam.
     */
    SCORE_ONLY,

    /**
     * The score, and which questions were wrong — without saying what the right answer was.
     *
     * <p>The level most tests actually want. It tells a learner where to study without handing over
     * the bank: knowing that question four was wrong is worth something to them and nearly nothing
     * to somebody assembling a copy of the exam.
     */
    SCORE_AND_WHICH_WRONG,

    /**
     * Everything: the correct answers and the author's explanation.
     *
     * <p>For a formative quiz this is the entire point. For a certification exam drawn from a bank
     * it hands the bank to anybody willing to sit the test once and screenshot it — which is why it
     * is never a default and always a deliberate act.
     */
    FULL;

    /** Whether this policy permits at least as much as {@code level}. */
    public boolean atLeast(ReviewVisibility level) {
        return ordinal() >= level.ordinal();
    }
}
