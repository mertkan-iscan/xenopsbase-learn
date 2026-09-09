package com.xenopsoftware.learn.assessment.review;

/**
 * When what {@link ReviewVisibility} permits actually opens (T-6.9).
 *
 * <p>Timing gates visibility; it does not replace it. Under {@code SCORE_ONLY} there is nothing to
 * gate, which is why the default pairing of {@code SCORE_ONLY} and {@link #IMMEDIATELY} is
 * restrictive rather than lax.
 */
public enum ReviewTiming {

    /** As soon as the attempt is marked. */
    IMMEDIATELY,

    /**
     * Once the learner has used every attempt they are allowed.
     *
     * <p>The one that stops somebody sitting attempt one to read the answers and attempt two to use
     * them. <b>Not expressible on a test with no attempt limit</b>, where it would mean never —
     * refused at authoring with that sentence rather than left to be discovered by a learner who
     * can never see their paper.
     */
    AFTER_ALL_ATTEMPTS,

    /** After a date the author chose — an exam window closing, a results day. */
    AFTER_DATE
}
