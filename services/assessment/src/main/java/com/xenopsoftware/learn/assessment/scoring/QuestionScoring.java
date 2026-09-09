package com.xenopsoftware.learn.assessment.scoring;

import java.math.BigDecimal;

/**
 * What one question in one test is worth, and how (T-6.4).
 *
 * <p>Per question rather than per test, because "the last question is worth three of the others" is
 * an ordinary thing for an author to want. Per question <b>in a test</b> rather than on the question
 * itself, because the same question appears in a refresher and in a certification exam and is not
 * worth the same in both — and because a weight on the question would be inside the versioned body
 * (ADR-0106), where it could not be corrected without orphaning every attempt that recorded it.
 *
 * @param points   what a fully right answer earns. Positive: a question worth nothing is a question
 *                 that should not be in the test, and expressing that as zero marks makes it look
 *                 like an answered question a learner got wrong in every report
 * @param mode     how a partly right answer is treated
 * @param penalty  what an answered, wholly wrong response costs, or zero. Never applied to an
 *                 unanswered question and never to a type where a wrong answer is not a guess —
 *                 see {@link Scores#award}
 */
public record QuestionScoring(BigDecimal points, ScoringMode mode, BigDecimal penalty) {

    public QuestionScoring {
        if (points == null || points.signum() <= 0) {
            throw new IllegalArgumentException(
                "A question is worth more than nothing, or it is not in the test: " + points);
        }
        if (mode == null) {
            throw new IllegalArgumentException("A question needs a scoring mode");
        }
        if (penalty == null || penalty.signum() < 0) {
            throw new IllegalArgumentException(
                "A penalty is what a wrong answer costs, so it is not negative: " + penalty);
        }
    }

    /** The ordinary question: one mark, all or nothing, no penalty. */
    public static QuestionScoring worthOneMark() {
        return new QuestionScoring(BigDecimal.ONE, ScoringMode.ALL_OR_NOTHING, BigDecimal.ZERO);
    }

    public QuestionScoring worth(BigDecimal newPoints) {
        return new QuestionScoring(newPoints, mode, penalty);
    }

    public QuestionScoring scoredBy(ScoringMode newMode) {
        return new QuestionScoring(points, newMode, penalty);
    }

    public QuestionScoring penalising(BigDecimal newPenalty) {
        return new QuestionScoring(points, mode, newPenalty);
    }
}
