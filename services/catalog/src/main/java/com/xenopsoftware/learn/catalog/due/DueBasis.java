package com.xenopsoftware.learn.catalog.due;

/**
 * What a relative deadline counts from (T-5.6, question 2).
 *
 * <p><b>This is the whole answer to "what is the due date for somebody who joins the group after
 * the assignment was made", and it is deliberately not a default.</b> Both readings are right for
 * different training and neither is right for both, so the person making the assignment states
 * which they mean and the column is NOT NULL for every relative deadline.
 */
public enum DueBasis {

    /**
     * From the moment the assignment was made — one date for everybody.
     *
     * <p>A late joiner inherits it and may be overdue on their first day. That is the correct
     * answer for "everyone must have done this before the audit": the audit does not move because
     * somebody was hired last week, and a platform that quietly gave them their own extension
     * would be reporting compliance the company does not have.
     */
    ASSIGNED,

    /**
     * From the moment this particular learner was first reached — their own clock.
     *
     * <p>The onboarding answer: "within 30 days of joining". Somebody added to the group eleven
     * months later gets thirty days from then, not from a date they had nothing to do with.
     */
    REACHED
}
