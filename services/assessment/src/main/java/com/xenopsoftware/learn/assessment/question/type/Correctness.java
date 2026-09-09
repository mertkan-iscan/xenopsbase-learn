package com.xenopsoftware.learn.assessment.question.type;

/**
 * How much of a response was right (T-6.3).
 *
 * <p>Two integers rather than a fraction or a percentage, and deliberately: "3 of 5 pairs matched"
 * survives being reported, audited and argued about, where 0.6 has already lost the information an
 * analyst would ask for next. T-7.7's item analysis wants the counts; a scaled score (T-6.4) is
 * computed from them and is not stored here.
 *
 * @param credited how many of the answerable parts this response got right
 * @param available how many there were to get right
 */
public record Correctness(int credited, int available) {

    public Correctness {
        if (available < 1) {
            throw new IllegalArgumentException("A question with nothing to get right is not a question");
        }
        if (credited < 0 || credited > available) {
            throw new IllegalArgumentException(
                "credited must be between 0 and " + available + ", not " + credited);
        }
    }

    /** The all-or-nothing case, which is most types. */
    public static Correctness of(boolean right) {
        return new Correctness(right ? 1 : 0, 1);
    }

    public boolean fullyRight() {
        return credited == available;
    }
}
