package com.xenopsoftware.learn.catalog.gate;

/**
 * How a gate combines its requirements (T-5.3).
 *
 * <p>Two, and there will not be a third. Both can be said in one English sentence, which is what
 * lets the explanation and the evaluation come from the same walk; a third would be the first step
 * back towards the expression language this design refuses.
 */
public enum Combinator {

    /** Every requirement. Reads as "and". */
    ALL("and"),

    /** Any one of them. Reads as "or". */
    ANY("or");

    private final String conjunction;

    Combinator(String conjunction) {
        this.conjunction = conjunction;
    }

    /** The word that joins the phrases when the rule is read aloud. */
    public String conjunction() {
        return conjunction;
    }
}
