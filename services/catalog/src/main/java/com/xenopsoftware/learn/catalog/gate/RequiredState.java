package com.xenopsoftware.learn.catalog.gate;

/**
 * What a required thing must have reached (T-5.3).
 *
 * <p>The verb is here rather than in the sentence builder, so a state added later cannot be
 * evaluated without also being sayable.
 */
public enum RequiredState {

    COMPLETED("complete"),
    PASSED("pass");

    private final String verb;

    RequiredState(String verb) {
        this.verb = verb;
    }

    /** The imperative a learner is given: "complete Module 1", "pass the safety test". */
    public String verb() {
        return verb;
    }
}
