package com.xenopsoftware.learn.assessment.integrity;

/**
 * The things a browser may tell us happened during an attempt (T-6.8).
 *
 * <p><b>A closed set, and a deliberately short one.</b> Every kind added here is a new thing this
 * product collects about a person, which should be a decision somebody makes on purpose rather than
 * one that arrives by widening a free-text column.
 *
 * <p>Each carries its innocent explanation in its own javadoc, because the whole task turns on
 * those being remembered by whoever reads a review screen. A signal without its innocent
 * explanation beside it is a signal somebody will act on.
 */
public enum IntegritySignal {

    /** The window stopped being focused. A screen reader moves focus. A notification steals it. */
    FOCUS_LOST("When this window loses focus, and for how long."),

    /** It came back. Paired with the above so a reviewer can see how long, not just that. */
    FOCUS_REGAINED("When it gets focus back."),

    /** The tab was hidden. A second monitor, a phone call, a laptop lid. */
    TAB_HIDDEN("When this tab is hidden."),

    /** It is visible again. */
    TAB_VISIBLE("When this tab is shown again."),

    /**
     * Something was pasted into an answer.
     *
     * <p>The length travels; <b>the text never does</b>. A learner drafting a long answer in a text
     * editor because the browser lost their work last time is the ordinary case, and collecting
     * what they wrote would be collecting their answer twice — once as an answer and once as
     * surveillance.
     */
    PASTE("When something is pasted into an answer -- how much, never what."),

    /**
     * The request came from a different address than the one before it.
     *
     * <p>A phone dropping from wi-fi to cellular does this in the middle of a sentence. So does a
     * corporate VPN rotating an egress node. This is the signal with the highest ratio of innocent
     * to guilty causes, and it is here because a reviewer who already suspects something wants to
     * know — not because it means anything on its own.
     */
    ADDRESS_CHANGED("When your requests start coming from a different network address.");

    private final String disclosure;

    IntegritySignal(String disclosure) {
        this.disclosure = disclosure;
    }

    /**
     * The sentence a learner is shown before they start, in their terms.
     *
     * <p>On the enum rather than in a template, so that <b>a signal cannot be collected without
     * being disclosed</b> (T-6.8): adding a constant without a sentence does not compile, and the
     * disclosure is generated from the same values the recorder accepts. Prose in a template is
     * prose that stops matching the code the first time somebody adds a kind.
     */
    public String disclosure() {
        return disclosure;
    }
}
