package com.xenopsoftware.learn.assessment.scoring;

import com.xenopsoftware.learn.assessment.question.type.Correctness;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * The arithmetic, in one place and written down (T-6.4).
 *
 * <p>Static and stateless, deliberately. Scoring is a function of a result and a policy, and a
 * service with a database behind it would be a place for one of those to arrive from somewhere
 * else — which is how two answers to "what did they score" come to exist.
 *
 * <h2>The whole of it, in three steps</h2>
 *
 * <ol>
 *   <li><b>A question.</b> {@link #award} turns T-6.3's {@link Correctness} into marks through the
 *       {@link ScoringMode}, and applies a penalty only where a wrong answer was a guess.
 *   <li><b>A section.</b> {@link SectionMark#of} sums the marks; the section's raw is floored at
 *       zero and divided by what was possible.
 *   <li><b>A test.</b> {@link #compose} is the weighted mean of the section fractions:
 *       <pre>scaled = Σ(sectionᵢ.scaled × weightᵢ) / Σ(weightᵢ)</pre>
 *       over the sections that could be scored and carry weight.
 * </ol>
 *
 * <p><b>A weighted mean of fractions, not a sum of marks.</b> The difference is the point of
 * weighting: with a sum, a forty-question section outweighs a ten-question one four to one whatever
 * the author configured, and adding a question to a section silently changes what every other
 * section is worth. Here the author's weights are the only thing that decides, and a section can
 * grow or shrink without moving anybody's pass mark.
 *
 * <h2>Where nothing is scorable</h2>
 *
 * <p>A section with nothing a machine could mark is <b>excluded, weight and all</b>, rather than
 * counted as zero. A section nobody could score is not a section somebody failed, and putting its
 * weight in the denominator would make it exactly that.
 */
public final class Scores {

    /**
     * Ten decimal places on every intermediate fraction, truncated rather than rounded.
     *
     * <p>Truncated so that no intermediate can round <em>up</em> across the pass boundary: a score
     * that rounds to the pass mark without reaching it would pass a learner the rule says failed,
     * and a mark nobody earned is worse than a percentage point of precision. Ten places is far
     * below the whole-percent granularity the verdict is decided at.
     */
    static final int SCALE = 10;

    private Scores() {
    }

    /**
     * What one response earns.
     *
     * @param correctness what T-6.3 said was right, or empty when a person still has to look
     * @param answered    whether the learner attempted it at all ({@link Responses#wasAnswered}).
     *                    A blank never earns and <b>never costs</b>
     * @param guessable   whether a wrong answer to this type could have been a guess with
     *                    computable odds. Only those may be penalised: a penalty on a typed answer
     *                    punishes a typo, and the type is the only thing that knows the difference
     *                    ({@code QuestionTypeDefinition.guessable})
     * @param negativeMarkingOn whether this test uses it at all
     */
    public static QuestionMark award(java.util.Optional<Correctness> correctness, boolean answered,
            QuestionScoring scoring, boolean guessable, boolean negativeMarkingOn) {
        if (correctness.isEmpty()) {
            return QuestionMark.awaitingAPerson(scoring.points());
        }
        if (!answered) {
            // Neither marks nor penalty. Penalising a blank converts "I do not know" into a worse
            // outcome than a guess, which is the opposite of what negative marking is for.
            return QuestionMark.of(BigDecimal.ZERO, scoring.points());
        }
        BigDecimal earned = scoring.mode().award(correctness.get(), scoring.points());
        if (earned.signum() > 0) {
            return QuestionMark.of(earned, scoring.points());
        }
        boolean penalise = negativeMarkingOn && guessable && scoring.penalty().signum() > 0;
        return QuestionMark.of(penalise ? scoring.penalty().negate() : BigDecimal.ZERO,
            scoring.points());
    }

    /**
     * The test score, and the verdict.
     *
     * @param sections   in any order; the arithmetic is a weighted mean and does not depend on one
     * @param passMarkPercent 0..100, a whole percent — see {@link TestScore} for why that is what
     *                        makes the displayed number and the verdict the same number
     */
    public static TestScore compose(List<SectionMark> sections, int passMarkPercent) {
        if (passMarkPercent < 0 || passMarkPercent > 100) {
            throw new IllegalArgumentException(
                "A pass mark is a whole percent between 0 and 100, not " + passMarkPercent);
        }

        BigDecimal weighted = BigDecimal.ZERO;
        BigDecimal totalWeight = BigDecimal.ZERO;
        BigDecimal raw = BigDecimal.ZERO;
        BigDecimal possible = BigDecimal.ZERO;
        boolean anythingAwaitingAPerson = false;

        for (SectionMark section : sections) {
            anythingAwaitingAPerson |= section.awaitingAPerson();
            if (!section.scorable()) {
                // Excluded entirely, weight included. A section nobody could score is not a
                // section somebody failed, and leaving its weight in the denominator would make
                // it exactly that. An EMPTY section is excluded the same way and is not
                // provisional -- there is nothing in it for anybody to mark.
                continue;
            }
            raw = raw.add(section.raw().max(BigDecimal.ZERO));
            possible = possible.add(section.possible());
            if (section.weight() == 0) {
                // Scored and shown, but not counted -- a practice section inside a real test.
                continue;
            }
            BigDecimal weight = BigDecimal.valueOf(section.weight());
            weighted = weighted.add(section.scaled().multiply(weight));
            totalWeight = totalWeight.add(weight);
        }

        if (totalWeight.signum() == 0) {
            // Nothing counted: no sections, none scorable, or every one of them weighted zero.
            return TestScore.nothingToScore(anythingAwaitingAPerson);
        }

        BigDecimal scaled = weighted.divide(totalWeight, SCALE, RoundingMode.DOWN);
        // The displayed percentage and the verdict, from one number. Floored, because flooring is
        // the only rounding that cannot put a percentage at or above the pass mark next to a fail.
        int percent = scaled.movePointRight(2).setScale(0, RoundingMode.FLOOR).intValue();
        return new TestScore(raw, possible, scaled, percent, percent >= passMarkPercent,
            anythingAwaitingAPerson);
    }
}
