package com.xenopsoftware.learn.catalog.due;

/**
 * How an assignment's deadline is stated (T-5.6).
 *
 * <p>Three values rather than a nullable date, because "no deadline" is a real and common answer
 * that a null would leave indistinguishable from "somebody forgot to fill it in".
 */
public enum DueKind {

    /** No deadline. Nothing is ever overdue, and no reminder is ever due. */
    NONE,

    /** One calendar date, the same for everybody the assignment reaches. */
    ABSOLUTE,

    /** So many days after an anchor — see {@link DueBasis} for which anchor. */
    RELATIVE
}
