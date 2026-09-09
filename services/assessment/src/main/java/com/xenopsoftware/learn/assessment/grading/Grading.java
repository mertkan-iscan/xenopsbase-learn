package com.xenopsoftware.learn.assessment.grading;

/**
 * Where an attempt's marking is (T-6.7).
 *
 * <p><b>A second axis, not a fifth attempt state.</b> {@code Attempt.State} says how an attempt
 * ended — submitted in time, the clock ran out, nobody came back. This says whether anybody has
 * marked it. An attempt that expired <em>and</em> is waiting on an essay is both of those things,
 * and one enum would have to lose one.
 */
public enum Grading {

    /** Never submitted, or abandoned. Nobody has marked it and nobody is going to. */
    NOT_GRADED,

    /**
     * A person has to look. THE STATE THIS TASK EXISTS FOR.
     *
     * <p>An attempt containing one essay is neither passed nor failed until somebody reads it.
     * Without this value the attempt would carry a null score, and a gate reading that as false
     * locks a learner out of a course they may well have passed.
     */
    AWAITING_GRADING,

    /** Settled. It carries a verdict, and a gate may act on it. */
    GRADED;

    /** Whether anybody may act on this result. The question a gate should ask. */
    public boolean settled() {
        return this == GRADED;
    }
}
