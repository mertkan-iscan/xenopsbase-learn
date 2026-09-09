package com.xenopsoftware.learn.assessment.scoring;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * What a section came to, and what it counts for (T-6.4).
 *
 * <p>Sections are T-6.5's to define. This is the shape their result takes, written now because the
 * composition arithmetic is what this task owes and it cannot be written twice: a test score
 * assembled in one place and a section score assembled in another is two answers to the same
 * question.
 *
 * <h2>The floor is here, and that is the decision</h2>
 *
 * <p>T-6.4 requires that negative marking "cannot take a test score below zero". The floor is
 * applied to each <b>section</b> rather than only to the test, and that is a stronger rule than the
 * criterion asks for, chosen deliberately:
 *
 * <ul>
 *   <li>A section is a reported unit. "You scored −20% on Fire safety" is not something a learner
 *       or their manager can act on, and a report that can print it will print it.
 *   <li>Flooring only at the end lets one disastrous section eat into another section's marks. An
 *       author who weighted two sections equally meant that each is worth half; a negative one
 *       silently makes the other worth more than all of it, which is not the arithmetic they
 *       configured.
 * </ul>
 *
 * <p>What negative marking still does, which is the whole of what it is for: inside a section, a
 * wrong guess cancels a right one. It just cannot reach across a boundary the author drew.
 *
 * @param name     the section, for the breakdown a result screen shows
 * @param raw      the sum of the awards, <b>before</b> the floor — kept unfloored so a report can
 *                 show what the guessing actually cost, which is the only way anybody learns not to
 * @param possible the sum of the points of the questions that could be marked
 * @param awaiting the points of the questions in it that a person still has to mark. Carried
 *                 rather than derived, because it is the difference between "this section scored
 *                 zero" and "nobody has read it yet" — and a section of nine marked questions and
 *                 one essay is both scorable and unfinished, which no single flag can say
 * @param weight   what this section counts for against the others. Zero is legitimate and means
 *                 "scored and shown but not counted", which is how a practice section lives inside
 *                 a real test
 */
public record SectionMark(String name, BigDecimal raw, BigDecimal possible, BigDecimal awaiting,
                          int weight) {

    public SectionMark {
        if (raw == null || possible == null || awaiting == null) {
            throw new IllegalArgumentException("A section mark is three numbers");
        }
        if (possible.signum() < 0) {
            throw new IllegalArgumentException("A section cannot be worth less than nothing");
        }
        if (weight < 0) {
            throw new IllegalArgumentException("A section's weight is not negative: " + weight);
        }
    }

    /**
     * Adds up the questions, skipping the ones a person still has to mark.
     *
     * <p>An ungraded question is in neither total (see {@link QuestionMark#awaitingAPerson}), so a
     * section of ten questions with one essay is scored out of nine until somebody reads it — and
     * {@link TestScore#provisional()} is what says so.
     */
    public static SectionMark of(String name, int weight, List<QuestionMark> marks) {
        BigDecimal raw = BigDecimal.ZERO;
        BigDecimal possible = BigDecimal.ZERO;
        BigDecimal awaiting = BigDecimal.ZERO;
        for (QuestionMark mark : marks) {
            if (mark.graded()) {
                raw = raw.add(mark.awarded());
                possible = possible.add(mark.points());
            } else {
                awaiting = awaiting.add(mark.points());
            }
        }
        return new SectionMark(name, raw, possible, awaiting, weight);
    }

    /** Whether anything in this section could be marked at all. */
    public boolean scorable() {
        return possible.signum() > 0;
    }

    /** Whether a person still has to mark something here. */
    public boolean awaitingAPerson() {
        return awaiting.signum() > 0;
    }

    /**
     * This section as a 0..1 value: the floored raw over what was possible.
     *
     * <p>Zero when nothing here was scorable, which the caller must not confuse with a score of
     * zero — {@link #scorable()} is the difference, and {@link Scores} uses it rather than letting
     * an unscorable section count as a failed one.
     */
    public BigDecimal scaled() {
        if (!scorable()) {
            return BigDecimal.ZERO;
        }
        BigDecimal floored = raw.max(BigDecimal.ZERO);
        return floored.divide(possible, Scores.SCALE, RoundingMode.DOWN);
    }
}
