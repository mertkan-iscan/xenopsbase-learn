package com.xenopsoftware.learn.assessment.exam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xenopsoftware.learn.assessment.PostgresTestHarness;
import com.xenopsoftware.learn.assessment.question.type.Correctness;
import com.xenopsoftware.learn.assessment.question.type.QuestionTypeDefinition;
import com.xenopsoftware.learn.assessment.question.type.QuestionTypes;
import com.xenopsoftware.learn.assessment.scoring.QuestionMark;
import com.xenopsoftware.learn.assessment.scoring.QuestionScoring;
import com.xenopsoftware.learn.assessment.scoring.Responses;
import com.xenopsoftware.learn.assessment.scoring.Scores;
import com.xenopsoftware.learn.assessment.scoring.ScoringMode;
import com.xenopsoftware.learn.assessment.scoring.SectionMark;
import com.xenopsoftware.learn.assessment.scoring.TestScore;
import com.xenopsoftware.learn.common.tenancy.TenantContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A test's scoring policy, and the seam from a graded answer to a mark (T-6.4).
 *
 * <p>{@code ScoresTest} covers the arithmetic on its own. This covers the two things it cannot: that
 * the policy survives being stored and read back, and that {@link QuestionTypes#grade} — T-6.3's
 * answer about a <em>real</em> question body — reaches {@link Scores#award} carrying everything the
 * penalty rule needs. That join is where a scoring design usually goes wrong, because each half
 * looks right on its own.
 */
@SpringBootTest
class TestScoringTest extends PostgresTestHarness {

    private static final String TENANT = "acme";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private TestService tests;
    @Autowired
    private QuestionTypes types;
    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void empty() {
        emptyEveryTable(dataSource);
    }

    // ---------------------------------------------------------------- the policy

    @Test
    void aNewTestScoresEveryQuestionTheSafeWay() {
        TestDefinition test = create("Fire safety", 80);

        assertThat(test.getPassMarkPercent()).isEqualTo(80);
        assertThat(test.isNegativeMarking())
            .as("off, because a test that started costing learners marks nobody configured is "
                + "the direction a default has to fail in")
            .isFalse();
        assertThat(test.defaultScoring().mode()).isEqualTo(ScoringMode.ALL_OR_NOTHING);
        assertThat(test.defaultScoring().points()).isEqualByComparingTo("1");
        assertThat(test.defaultScoring().penalty()).isEqualByComparingTo("0");
    }

    @Test
    void thePolicySurvivesBeingStoredAndReadBack() {
        UUID id = create("Fire safety", 80).getId();

        TenantContext.callWithUnchecked(TENANT, () -> tests.scoredAs(id, 65, true,
            new BigDecimal("2.5"), ScoringMode.PARTIAL_CREDIT, new BigDecimal("0.25")));
        TestDefinition read = TenantContext.callWithUnchecked(TENANT, () -> tests.get(id));

        assertThat(read.getPassMarkPercent()).isEqualTo(65);
        assertThat(read.isNegativeMarking()).isTrue();
        assertThat(read.defaultScoring().points()).isEqualByComparingTo("2.5");
        assertThat(read.defaultScoring().mode()).isEqualTo(ScoringMode.PARTIAL_CREDIT);
        assertThat(read.defaultScoring().penalty()).isEqualByComparingTo("0.25");
    }

    @Test
    void aTestMustSayWhatPassingItMeans() {
        assertThatThrownBy(() -> create("Fire safety", 101))
            .hasMessageContaining("whole percent between 0 and 100");
        assertThatThrownBy(() -> create("Fire safety", -1))
            .hasMessageContaining("whole percent between 0 and 100");
    }

    @Test
    void aQuestionWorthNothingIsRefusedRatherThanStored() {
        UUID id = create("Fire safety", 80).getId();

        assertThatThrownBy(() -> TenantContext.callWithUnchecked(TENANT, () ->
            tests.scoredAs(id, 80, false, BigDecimal.ZERO, ScoringMode.ALL_OR_NOTHING,
                BigDecimal.ZERO)))
            .as("zero marks looks in every report exactly like an answered question somebody got "
                + "wrong, which is not what the author meant")
            .hasMessageContaining("worth more than nothing");
    }

    @Test
    void aNegativePenaltyIsRefused() {
        UUID id = create("Fire safety", 80).getId();

        assertThatThrownBy(() -> TenantContext.callWithUnchecked(TENANT, () ->
            tests.scoredAs(id, 80, true, BigDecimal.ONE, ScoringMode.ALL_OR_NOTHING,
                new BigDecimal("-1"))))
            .hasMessageContaining("not negative");
    }

    /**
     * Editing the policy does not rescore anything, and that is the decision.
     *
     * <p>There is nothing sat yet to rescore — attempts are T-6.6 (#65) — so what this asserts is
     * the shape of the promise: {@code scoredAs} touches one row and produces no side effect
     * anywhere else. A policy edit that silently rewrote history would change a compliance record
     * nobody was told about, which is why the last criterion asks for rescoring to be a deliberate,
     * audited act instead.
     */
    @Test
    void changingThePolicyIsOneRowAndNoSideEffects() {
        UUID id = create("Fire safety", 80).getId();
        UUID other = create("Manual handling", 50).getId();

        TenantContext.callWithUnchecked(TENANT, () -> tests.scoredAs(id, 90, false,
            BigDecimal.ONE, ScoringMode.PARTIAL_CREDIT, BigDecimal.ZERO));

        assertThat(TenantContext.callWithUnchecked(TENANT, () -> tests.get(other))
            .getPassMarkPercent()).isEqualTo(50);
    }

    // ---------------------------------------------------------------- the seam

    /**
     * A real question, graded by T-6.3, scored by T-6.4.
     *
     * <p>Three of four regions right and one wrong is credited two of three by the type — right
     * picks minus wrong ones, which is T-6.3's arithmetic and not a mode — and partial credit is
     * what turns that into two thirds of the marks.
     */
    @Test
    void aPartlyRightAnswerToARealQuestionEarnsPartOfItsMarks() {
        JsonNode body = json("""
            {'type':'multiple-choice','stem':'Which of these are extinguisher classes?',
             'options':{'choices':[{'id':'a','text':'A'},{'id':'b','text':'B'},
                                   {'id':'c','text':'C'},{'id':'d','text':'Z'}]},
             'answerKey':{'correct':['a','b','c']}}""");
        JsonNode response = json("{'chosen':['a','b','c','d']}");

        Optional<Correctness> correctness = types.grade(body, response);
        QuestionMark mark = Scores.award(correctness, Responses.wasAnswered(response),
            QuestionScoring.worthOneMark().scoredBy(ScoringMode.PARTIAL_CREDIT),
            guessable(body), false);

        assertThat(correctness).contains(new Correctness(2, 3));
        assertThat(mark.awarded()).isEqualByComparingTo("0.666667");
    }

    /**
     * The rule that makes negative marking mean anything: the question type has the last word.
     *
     * <p>The same test, the same penalty, the same wholly wrong answer — and a guessable type is
     * charged for it while a typed one is not. That is the whole of what the table in
     * {@code docs/scoring.md} claims, asserted against the real definitions rather than against a
     * copy of the list.
     */
    @Test
    void onlyAGuessableTypeIsEverPenalised() {
        JsonNode picked = json("""
            {'type':'single-choice','stem':'Which extinguisher suits an electrical fire?',
             'options':{'choices':[{'id':'a','text':'Water'},{'id':'b','text':'CO2'}]},
             'answerKey':{'correct':['b']}}""");
        JsonNode typed = json("""
            {'type':'fill-in','stem':'The capital of Turkey is ___.',
             'options':{'blanks':[{'id':'b1','text':'capital'}]},
             'answerKey':{'accepted':{'b1':['Ankara']}}}""");
        QuestionScoring penalised = QuestionScoring.worthOneMark().penalising(new BigDecimal("0.25"));

        assertThat(mark(picked, json("{'chosen':['a']}"), penalised).awarded())
            .as("two choices, so a coin scores half; a penalty is how a test stops rewarding that")
            .isEqualByComparingTo("-0.25");
        assertThat(mark(typed, json("{'filled':{'b1':'Ankarra'}}"), penalised).awarded())
            .as("a misspelling is not a guess, and charging for one is charging for a typo")
            .isEqualByComparingTo("0");
    }

    @Test
    void anUnansweredQuestionCostsNothingHoweverTheTestIsConfigured() {
        JsonNode picked = json("""
            {'type':'single-choice','stem':'Which extinguisher suits an electrical fire?',
             'options':{'choices':[{'id':'a','text':'Water'},{'id':'b','text':'CO2'}]},
             'answerKey':{'correct':['b']}}""");
        JsonNode blank = json("{'chosen':[]}");

        assertThat(Responses.wasAnswered(blank))
            .as("an empty choice list is T-6.3's \"unanswered\", and the scorer has to see it as "
                + "that -- Correctness floors both to zero and cannot tell them apart")
            .isFalse();
        assertThat(mark(picked, blank,
            QuestionScoring.worthOneMark().penalising(new BigDecimal("0.25"))).awarded())
            .isEqualByComparingTo("0");
    }

    /**
     * An essay in the middle of a marked test, all the way through to the verdict.
     *
     * <p>The learner got everything a machine could mark right. Scoring the essay as zero would
     * report 50% against a pass mark of 80 and fail somebody nobody has read yet — which is exactly
     * how a gate locks a learner out of a course they passed (T-6.7's warning, and the arithmetic
     * that has to be right before that state can be modelled).
     */
    @Test
    void anAttemptWaitingOnAnEssayIsNotFailedInTheMeantime() {
        JsonNode essay = json("{'type':'essay','stem':'Describe the evacuation procedure.'}");
        JsonNode picked = json("""
            {'type':'true-false','stem':'Water conducts electricity.',
             'answerKey':{'correct':['true']}}""");

        List<QuestionMark> marks = List.of(
            mark(picked, json("{'chosen':['true']}"), QuestionScoring.worthOneMark()),
            mark(essay, json("{'text':'You leave by the nearest exit.'}"),
                QuestionScoring.worthOneMark()));
        TestScore score = Scores.compose(List.of(SectionMark.of("Fire safety", 1, marks)), 80);

        assertThat(score.percent())
            .as("scored out of what could be scored, which is one question")
            .isEqualTo(100);
        assertThat(score.provisional()).isTrue();
        assertThat(score.decided())
            .as("and a gate must wait rather than read this as a verdict")
            .isFalse();
    }

    // ---------------------------------------------------------------- helpers

    private TestDefinition create(String title, int passMark) {
        return TenantContext.callWithUnchecked(TENANT,
            () -> tests.create(title, null, passMark));
    }

    private QuestionMark mark(JsonNode body, JsonNode response, QuestionScoring scoring) {
        return Scores.award(types.grade(body, response), Responses.wasAnswered(response), scoring,
            guessable(body), true);
    }

    private boolean guessable(JsonNode body) {
        return types.find(body.get("type").asString())
            .map(QuestionTypeDefinition::guessable)
            .orElseThrow();
    }

    /**
     * Single quotes, so a JSON fixture reads as one line of text rather than as a wall of escapes.
     *
     * <p>The character literals are numeric because {@code '\''} inside a text block is a quoting
     * argument nobody wins — the shape {@code QuestionShapesTest} already settled on (T-6.3).
     */
    private static JsonNode json(String withSingleQuotes) {
        return JSON.readTree(withSingleQuotes.replace((char) 39, (char) 34));
    }
}
