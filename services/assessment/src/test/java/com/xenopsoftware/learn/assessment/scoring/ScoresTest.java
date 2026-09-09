package com.xenopsoftware.learn.assessment.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xenopsoftware.learn.assessment.question.type.Correctness;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The arithmetic, at its boundaries (T-6.4).
 *
 * <p>No Spring and no database, because there is nothing here that needs one: scoring is a function
 * of a result and a policy. That is also the claim — a test that had to stand a service up to ask
 * what somebody scored would be evidence that the answer depends on something other than the marks.
 */
class ScoresTest {

    private static final BigDecimal ONE_MARK = BigDecimal.ONE;
    private static final QuestionScoring ALL_OR_NOTHING = QuestionScoring.worthOneMark();
    private static final QuestionScoring PARTIAL =
        QuestionScoring.worthOneMark().scoredBy(ScoringMode.PARTIAL_CREDIT);

    @Nested
    class OneQuestion {

        @Test
        void allOrNothingIsExactlyThat() {
            assertThat(marks(new Correctness(3, 3), ALL_OR_NOTHING.worth(five())))
                .isEqualByComparingTo("5");
            assertThat(marks(new Correctness(2, 3), ALL_OR_NOTHING.worth(five())))
                .as("two of three steps of a procedure in the right order is not two thirds of "
                    + "knowing the procedure, and an author who disagrees says so with a mode")
                .isEqualByComparingTo("0");
        }

        @Test
        void partialCreditIsTheFractionThatWasRight() {
            assertThat(marks(new Correctness(2, 3), PARTIAL.worth(five())))
                .isEqualByComparingTo("3.333333");
        }

        @Test
        void fullMarksUnderPartialCreditAreTheNumberTheAuthorTyped() {
            // Divided and multiplied back, five thirds times three is 4.999998. Nobody would ever
            // find that in a report, and everybody would find it in the total.
            assertThat(marks(new Correctness(3, 3), PARTIAL.worth(five())))
                .isEqualByComparingTo("5");
        }

        @Test
        void partialCreditOnAOnePartQuestionIsAllOrNothing() {
            // Arithmetic, not a special case -- which is why turning partial credit on for a whole
            // test does not quietly change what its single-choice questions are worth.
            assertThat(marks(new Correctness(1, 1), PARTIAL.worth(five())))
                .isEqualByComparingTo(marks(new Correctness(1, 1), ALL_OR_NOTHING.worth(five())));
            assertThat(marks(new Correctness(0, 1), PARTIAL.worth(five())))
                .isEqualByComparingTo(marks(new Correctness(0, 1), ALL_OR_NOTHING.worth(five())));
        }

        @Test
        void aQuestionWaitingForAPersonIsInNeitherTotal() {
            QuestionMark mark = Scores.award(Optional.empty(), true, ALL_OR_NOTHING, false, false);

            assertThat(mark.graded()).isFalse();
            assertThat(mark.points())
                .as("it still carries what it is worth, so \"how much of this is unmarked\" has "
                    + "an answer")
                .isEqualByComparingTo(ONE_MARK);
        }
    }

    @Nested
    class NegativeMarking {

        private final QuestionScoring guessed =
            QuestionScoring.worthOneMark().penalising(new BigDecimal("0.25"));

        @Test
        void aWrongGuessCosts() {
            assertThat(award(new Correctness(0, 1), true, guessed, true, true))
                .isEqualByComparingTo("-0.25");
        }

        @Test
        void aBlankNeverCosts() {
            assertThat(award(new Correctness(0, 1), false, guessed, true, true))
                .as("penalising a blank converts \"I do not know\" into a worse outcome than a "
                    + "guess, which is the opposite of what negative marking is for")
                .isEqualByComparingTo("0");
        }

        @Test
        void aTypedAnswerIsNeverPenalisedHoweverTheTestIsConfigured() {
            assertThat(award(new Correctness(0, 1), true, guessed, false, true))
                .as("nobody guesses \"Ankara\": a penalty here charges somebody for a typo, and "
                    + "the type is the only thing that knows the difference")
                .isEqualByComparingTo("0");
        }

        @Test
        void aTestWithNegativeMarkingOffCostsNothingEvenWithAPenaltySet() {
            assertThat(award(new Correctness(0, 1), true, guessed, true, false))
                .isEqualByComparingTo("0");
        }

        @Test
        void partlyRightIsNotWrong() {
            QuestionScoring partlyGuessed = guessed.scoredBy(ScoringMode.PARTIAL_CREDIT);

            assertThat(award(new Correctness(1, 3), true, partlyGuessed, true, true))
                .as("a penalty is for a wholly wrong answer; one of three right earns, it does "
                    + "not cost")
                .isEqualByComparingTo("0.333333");
        }
    }

    @Nested
    class OneSection {

        @Test
        void aWrongGuessCancelsARightAnswerInsideTheSection() {
            SectionMark section = SectionMark.of("Fire safety", 1,
                List.of(QuestionMark.of(ONE_MARK, ONE_MARK),
                    QuestionMark.of(BigDecimal.ONE.negate(), ONE_MARK)));

            assertThat(section.raw()).isEqualByComparingTo("0");
            assertThat(section.scaled()).isEqualByComparingTo("0");
        }

        @Test
        void andCannotDragItBelowZero() {
            SectionMark section = SectionMark.of("Fire safety", 1, List.of(
                QuestionMark.of(BigDecimal.ONE.negate(), ONE_MARK),
                QuestionMark.of(BigDecimal.ONE.negate(), ONE_MARK)));

            assertThat(section.raw())
                .as("the unfloored total is kept, because what the guessing cost is the only way "
                    + "anybody learns not to")
                .isEqualByComparingTo("-2");
            assertThat(section.scaled()).isEqualByComparingTo("0");
        }

        @Test
        void anUnmarkedQuestionIsInNeitherTotalAndIsCountedAsWaiting() {
            SectionMark section = SectionMark.of("Fire safety", 1, List.of(
                QuestionMark.of(ONE_MARK, ONE_MARK),
                QuestionMark.awaitingAPerson(new BigDecimal("9"))));

            assertThat(section.possible())
                .as("scored out of what could be scored, not out of ten")
                .isEqualByComparingTo("1");
            assertThat(section.scaled()).isEqualByComparingTo("1");
            assertThat(section.awaitingAPerson()).isTrue();
        }
    }

    @Nested
    class ComposingATest {

        @Test
        void equalWeightsMeanEqualHalvesHoweverManyQuestionsEachSectionHas() {
            // THE TEST THE WEIGHTED MEAN EXISTS FOR. One question against forty. Summing marks
            // would make this 1/41 -- about two per cent -- and adding a question to the long
            // section would silently move everybody's score again.
            TestScore score = Scores.compose(List.of(
                section("Short", 1, allRight(1)),
                section("Long", 1, allWrong(40))), 50);

            assertThat(score.scaled()).isEqualByComparingTo("0.5");
            assertThat(score.percent()).isEqualTo(50);
            assertThat(score.passed()).isTrue();
        }

        @Test
        void weightsAreWhatDecides() {
            TestScore score = Scores.compose(List.of(
                section("Heavy", 3, allRight(10)),
                section("Light", 1, allWrong(10))), 50);

            assertThat(score.scaled()).isEqualByComparingTo("0.75");
            assertThat(score.percent()).isEqualTo(75);
        }

        @Test
        void aSectionWeightedZeroIsScoredAndShownButDoesNotCount() {
            TestScore score = Scores.compose(List.of(
                section("Real", 1, allRight(10)),
                section("Practice", 0, allWrong(10))), 50);

            assertThat(score.percent())
                .as("a practice section inside a real test")
                .isEqualTo(100);
            assertThat(score.possible())
                .as("but it is still in the raw totals a result screen shows")
                .isEqualByComparingTo("20");
        }

        @Test
        void aSectionNobodyCouldScoreIsExcludedWeightAndAll() {
            TestScore score = Scores.compose(List.of(
                section("Marked", 1, allRight(10)),
                section("Essays", 1, List.of(QuestionMark.awaitingAPerson(ONE_MARK)))), 50);

            assertThat(score.percent())
                .as("leaving its weight in the denominator would make an unmarked section a "
                    + "failed one -- half marks for work nobody has read")
                .isEqualTo(100);
            assertThat(score.provisional()).isTrue();
            assertThat(score.decided()).isFalse();
        }

        @Test
        void aTestWhereNothingCouldBeMarkedIsNotAFail() {
            TestScore score = Scores.compose(List.of(
                section("Essays", 1, List.of(QuestionMark.awaitingAPerson(ONE_MARK)))), 50);

            assertThat(score.passed())
                .as("zero would be a lie in the direction that costs somebody a course")
                .isFalse();
            assertThat(score.provisional()).isTrue();
            assertThat(score.scaled()).isEqualByComparingTo("0");
        }

        @Test
        void anEmptyTestIsNotProvisional() {
            TestScore score = Scores.compose(List.of(), 50);

            assertThat(score.provisional())
                .as("there is nothing in it for anybody to mark, which is different from waiting")
                .isFalse();
            assertThat(score.passed()).isFalse();
        }

        @Test
        void theOrderOfTheSectionsChangesNothing() {
            List<SectionMark> sections = List.of(
                section("A", 3, allRight(10)), section("B", 1, allWrong(10)));

            assertThat(Scores.compose(sections, 50).scaled())
                .isEqualByComparingTo(Scores.compose(sections.reversed(), 50).scaled());
        }

        @Test
        void aPassMarkOutsideAPercentageIsRefused() {
            assertThatThrownBy(() -> Scores.compose(List.of(), 101))
                .hasMessageContaining("whole percent between 0 and 100");
        }
    }

    // ---------------------------------------------------------------- helpers

    private static BigDecimal five() {
        return new BigDecimal("5");
    }

    private static BigDecimal marks(Correctness correctness, QuestionScoring scoring) {
        return award(correctness, true, scoring, false, false);
    }

    private static BigDecimal award(Correctness correctness, boolean answered,
            QuestionScoring scoring, boolean guessable, boolean negativeMarkingOn) {
        return Scores.award(Optional.of(correctness), answered, scoring, guessable,
            negativeMarkingOn).awarded();
    }

    private static SectionMark section(String name, int weight, List<QuestionMark> marks) {
        return SectionMark.of(name, weight, marks);
    }

    private static List<QuestionMark> allRight(int count) {
        return marksOf(count, ONE_MARK);
    }

    private static List<QuestionMark> allWrong(int count) {
        return marksOf(count, BigDecimal.ZERO);
    }

    private static List<QuestionMark> marksOf(int count, BigDecimal awarded) {
        return java.util.stream.IntStream.range(0, count)
            .mapToObj(index -> QuestionMark.of(awarded, ONE_MARK))
            .toList();
    }
}
