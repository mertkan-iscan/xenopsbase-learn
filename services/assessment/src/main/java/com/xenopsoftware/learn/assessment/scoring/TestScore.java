package com.xenopsoftware.learn.assessment.scoring;

import java.math.BigDecimal;

/**
 * What a test came to, and whether it was passed (T-6.4).
 *
 * <h2>Two numbers are stored and one of them is read</h2>
 *
 * <p>{@code raw} exists so a result can be explained — "34 of 40" is what a learner asks about and
 * what a rescore is checked against. {@code scaled} exists so a rule can be written against it, and
 * <b>everything downstream reads the scaled one</b>: gates (T-5.3), reports (E7), certificates. A
 * pass rule written against raw points breaks the moment a section changes length or a weight is
 * edited, and it breaks quietly — the test still runs and the pass mark just means something
 * different than it did last month.
 *
 * <h2>The percentage and the verdict are the same number</h2>
 *
 * <p>{@link #percent()} is the scaled score <b>floored</b> to a whole percent, and {@link #passed()}
 * is that integer compared with the test's pass mark. Not two roundings that agree — one number used
 * twice.
 *
 * <p>This is the answer to "79.5% against a pass mark of 80 must have one defined answer". It is
 * <b>fail</b>, and — the part that matters — the learner is shown <b>79%</b>, not 80%. Rounding the
 * display half up is the version of this that puts "80%" and "failed" on the same screen, and there
 * is no explaining that to anybody. Flooring is the only rounding that cannot contradict a
 * greater-or-equal comparison, and keeping the pass mark a whole percent (see {@code V3__test.sql})
 * is what makes the equivalence exact rather than nearly exact.
 *
 * @param raw         the sum of the section awards, floored per section
 * @param possible    what those sections were worth
 * @param scaled      0..1, the weighted composition. The value every downstream rule reads
 * @param percent     {@code scaled} floored to a whole percent — the number to display, and the
 *                    number compared with the pass mark
 * @param passed      whether {@code percent} reached the test's pass mark
 * @param provisional some questions are still waiting for a person (T-6.7), so this score is
 *                    computed over the rest. <b>A provisional score that has not reached the pass
 *                    mark is not a fail</b>, and a gate that treats it as one locks a learner out
 *                    of a course they may well have passed
 */
public record TestScore(BigDecimal raw, BigDecimal possible, BigDecimal scaled, int percent,
                        boolean passed, boolean provisional) {

    /**
     * A test where nothing could be marked: no sections, or every question waiting for a person.
     *
     * <p>Provisional, never passed, and never a fail. Zero would be a lie in the direction that
     * costs somebody a course.
     */
    public static TestScore nothingToScore(boolean anythingAwaitingAPerson) {
        return new TestScore(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, false,
            anythingAwaitingAPerson);
    }

    /** Whether this is a settled verdict a gate may act on. */
    public boolean decided() {
        return !provisional;
    }
}
