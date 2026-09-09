package com.xenopsoftware.learn.assessment.question.type;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The shapes, per type (T-6.3).
 *
 * <p>The failure this whole file exists for is one sentence in the issue: a question saved with an
 * answer key that does not match its options is discovered when a learner cannot score. So the
 * assertions come in pairs -- a well-formed question of every type is accepted, and the specific
 * disagreement each type can have between its halves is refused.
 *
 * <p>Fixtures live here as strings rather than as builders. A builder would produce whatever it was
 * written to produce; these are the documents a client actually sends, and reading them is how a
 * person checks that the shape in the javadoc is the shape being tested.
 */
class QuestionShapesTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final QuestionTypes types = new QuestionTypes(List.of(
        new ChoiceQuestionTypes().singleChoiceQuestionType(),
        new ChoiceQuestionTypes().multipleChoiceQuestionType(),
        new ChoiceQuestionTypes().trueFalseQuestionType(),
        new ArrangementQuestionTypes().matchingQuestionType(),
        new ArrangementQuestionTypes().orderingQuestionType(),
        new EnteredAnswerQuestionTypes().fillInQuestionType(),
        new EnteredAnswerQuestionTypes().numericQuestionType(),
        new HotspotQuestionType().clickTheRightPartOfThePicture(),
        new HumanGradedQuestionTypes().essayQuestionType(),
        new HumanGradedQuestionTypes().fileUploadQuestionType()));

    // ------------------------------------------------------------ a fixture per type

    /** One well-formed question of every type the product ships. */
    static List<String> everyType() {
        return List.of(
            """
            {"type":"single-choice","stem":"Which extinguisher suits an electrical fire?",
             "options":{"choices":[{"id":"a","text":"Water"},{"id":"b","text":"CO2"}]},
             "answerKey":{"correct":["b"]}}""",
            """
            {"type":"multiple-choice","stem":"Which of these conduct electricity?",
             "options":{"choices":[{"id":"a","text":"Copper"},{"id":"b","text":"Rubber"},
                                   {"id":"c","text":"Salt water"}]},
             "answerKey":{"correct":["a","c"]}}""",
            """
            {"type":"true-false","stem":"Water conducts electricity.",
             "answerKey":{"correct":["true"]}}""",
            """
            {"type":"matching","stem":"Match the extinguisher to the fire.",
             "options":{"left":[{"id":"l1","text":"CO2"},{"id":"l2","text":"Foam"}],
                        "right":[{"id":"r1","text":"Electrical"},{"id":"r2","text":"Liquid"}]},
             "answerKey":{"pairs":[{"left":"l1","right":"r1"},{"left":"l2","right":"r2"}]}}""",
            """
            {"type":"ordering","stem":"Put the shutdown steps in order.",
             "options":{"items":[{"id":"i1","text":"Close the valve"},
                                 {"id":"i2","text":"Cut the power"},
                                 {"id":"i3","text":"Call it in"}]},
             "answerKey":{"order":["i2","i1","i3"]}}""",
            """
            {"type":"fill-in","stem":"The capital of Turkey is ___.",
             "options":{"blanks":[{"id":"b1","text":"the capital"}]},
             "answerKey":{"accepted":{"b1":["Ankara","Angora"]}}}""",
            """
            {"type":"numeric","stem":"How many kilograms in a stone?",
             "options":{"unit":"kg"},
             "answerKey":{"value":6.35,"tolerance":0.05}}""",
            """
            {"type":"hotspot","stem":"Click the CO2 extinguisher.",
             "options":{"image":"3f6b1d2e-1c4a-4a7e-9b3d-2f5c8e7a1b04",
                        "regions":[{"id":"r1","text":"The CO2 extinguisher","shape":{"x":10,"y":20}},
                                   {"id":"r2","text":"The water extinguisher","shape":{"x":90,"y":20}}]},
             "answerKey":{"correct":["r1"]}}""",
            """
            {"type":"essay","stem":"Describe the evacuation procedure.",
             "options":{"guidance":"Two hundred words."}}""",
            """
            {"type":"file-upload","stem":"Upload your signed checklist.",
             "options":{"accept":["pdf"]}}""");
    }

    @ParameterizedTest
    @MethodSource("everyType")
    void everyTypeAcceptsAWellFormedQuestion(String body) {
        types.validateAsked(json(body));
    }

    @Test
    void tenTypesAndNoDuplicates() {
        assertThat(types.all()).extracting(QuestionTypeDefinition::code)
            .containsExactlyInAnyOrder("single-choice", "multiple-choice", "true-false", "matching",
                "ordering", "fill-in", "numeric", "hotspot", "essay", "file-upload");
    }

    @Test
    void twoDefinitionsCannotClaimOneCode() {
        // Which validator runs would be decided by bean ordering: it would work in development and
        // refuse a customer's question in production, or accept one it should not.
        assertThatThrownBy(() -> new QuestionTypes(List.of(
            new ChoiceQuestionTypes().singleChoiceQuestionType(),
            new ChoiceQuestionTypes().singleChoiceQuestionType())))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("both claim the code 'single-choice'");
    }

    // ------------------------------------------------------------ the disagreement, per type

    @Test
    void aKeyCannotNameAChoiceTheQuestionDoesNotOffer() {
        assertThatThrownBy(() -> types.validateAsked(json("""
            {"type":"single-choice","stem":"?",
             "options":{"choices":[{"id":"a","text":"Water"},{"id":"b","text":"CO2"}]},
             "answerKey":{"correct":["d"]}}""")))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("names 'd', which this question does not offer");
    }

    @Test
    void aSingleChoiceQuestionWithTwoRightAnswersCannotBeAnswered() {
        assertThatThrownBy(() -> types.validateAsked(json("""
            {"type":"single-choice","stem":"?",
             "options":{"choices":[{"id":"a","text":"A"},{"id":"b","text":"B"},{"id":"c","text":"C"}]},
             "answerKey":{"correct":["a","b"]}}""")))
            .hasMessageContaining("exactly one correct choice");
    }

    @Test
    void everyChoiceBeingCorrectIsRefused() {
        assertThatThrownBy(() -> types.validateAsked(json("""
            {"type":"multiple-choice","stem":"?",
             "options":{"choices":[{"id":"a","text":"A"},{"id":"b","text":"B"}]},
             "answerKey":{"correct":["a","b"]}}""")))
            .hasMessageContaining("nothing to get wrong");
    }

    @Test
    void twoChoicesCannotShareAnId() {
        assertThatThrownBy(() -> types.validateAsked(json("""
            {"type":"single-choice","stem":"?",
             "options":{"choices":[{"id":"a","text":"A"},{"id":"a","text":"Also A"}]},
             "answerKey":{"correct":["a"]}}""")))
            .hasMessageContaining("share the id 'a'");
    }

    /** The accessibility rule, enforced where it survives: at the schema. */
    @Test
    void anOrderingItemWithoutTextCannotBeOperatedByKeyboard() {
        assertThatThrownBy(() -> types.validateAsked(json("""
            {"type":"ordering","stem":"?",
             "options":{"items":[{"id":"i1"},{"id":"i2","text":"Second"}]},
             "answerKey":{"order":["i1","i2"]}}""")))
            .hasMessageContaining("needs a non-empty 'text'");
    }

    @Test
    void aHotspotRegionWithoutTextIsRefusedForTheSameReason() {
        assertThatThrownBy(() -> types.validateAsked(json("""
            {"type":"hotspot","stem":"?",
             "options":{"image":"abc","regions":[{"id":"r1","shape":{}},{"id":"r2","text":"B"}]},
             "answerKey":{"correct":["r1"]}}""")))
            .hasMessageContaining("needs a non-empty 'text'");
    }

    @Test
    void aHotspotImageIsAContentItemIdAndNotAUrl() {
        assertThatThrownBy(() -> types.validateAsked(json("""
            {"type":"hotspot","stem":"?",
             "options":{"image":"https://cdn.example.com/fire.png",
                        "regions":[{"id":"r1","text":"A"},{"id":"r2","text":"B"}]},
             "answerKey":{"correct":["r1"]}}""")))
            .hasMessageContaining("must be a content item id, not a URL");
    }

    @Test
    void mediaInAStemIsAContentItemIdAndNotAUrl() {
        assertThatThrownBy(() -> types.validateAsked(json("""
            {"type":"true-false","stem":"?","media":["https://cdn.example.com/x.png"],
             "answerKey":{"correct":["true"]}}""")))
            .hasMessageContaining("must be content item ids, not URLs");
    }

    @Test
    void anOrderCoveringSomeItemsLeavesALearnerHoldingOne() {
        assertThatThrownBy(() -> types.validateAsked(json("""
            {"type":"ordering","stem":"?",
             "options":{"items":[{"id":"i1","text":"A"},{"id":"i2","text":"B"},{"id":"i3","text":"C"}]},
             "answerKey":{"order":["i1","i2"]}}""")))
            .hasMessageContaining("Every item needs a position");
    }

    @Test
    void aMatchingKeyMustPairEveryLeftHandItem() {
        assertThatThrownBy(() -> types.validateAsked(json("""
            {"type":"matching","stem":"?",
             "options":{"left":[{"id":"l1","text":"A"},{"id":"l2","text":"B"}],
                        "right":[{"id":"r1","text":"X"},{"id":"r2","text":"Y"}]},
             "answerKey":{"pairs":[{"left":"l1","right":"r1"}]}}""")))
            .hasMessageContaining("Every one needs a pair");
    }

    @Test
    void aBlankWithNoAcceptedAnswerIsAMarkNobodyCanEarn() {
        assertThatThrownBy(() -> types.validateAsked(json("""
            {"type":"fill-in","stem":"?",
             "options":{"blanks":[{"id":"b1","text":"the capital"}]},
             "answerKey":{"accepted":{"b2":["Ankara"]}}}""")))
            .hasMessageContaining("has no accepted answers");
    }

    @Test
    void aNumericQuestionSaysWhatCloseEnoughMeans() {
        assertThatThrownBy(() -> types.validateAsked(json("""
            {"type":"numeric","stem":"?","answerKey":{"value":3.5}}""")))
            .hasMessageContaining("needs a numeric 'tolerance'");
    }

    @Test
    void anEssayAnswerKeyIsRefusedRatherThanIgnored() {
        // Ignoring it would mean an author writes a model answer, nothing reads it, and they find
        // out when the marks come back blank.
        assertThatThrownBy(() -> types.validateAsked(json("""
            {"type":"essay","stem":"?","answerKey":{"model":"Evacuate calmly."}}""")))
            .hasMessageContaining("no answer key");
    }

    @Test
    void anUnknownTypeIsTheCallersMistakeAndSaysWhatExists() {
        assertThatThrownBy(() -> types.validateAsked(json("""
            {"type":"crossword","stem":"?"}""")))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("No question type 'crossword'")
            .hasMessageContaining("single-choice");
    }

    // ------------------------------------------------------------ responses

    @Test
    void anUnansweredQuestionIsAnOrdinaryResponse() {
        // Refusing it would turn "I do not know" into a failed request, mid-exam.
        types.validateResponse(json(everyType().get(0)), json("{'chosen':[]}"));
        types.validateResponse(json(everyType().get(4)), json("{'order':[]}"));
        types.validateResponse(json(everyType().get(6)), json("{}"));
    }

    @Test
    void aResponseCannotNameSomethingTheLearnerWasNotShown() {
        assertThatThrownBy(() -> types.validateResponse(json(everyType().get(0)),
            json("{'chosen':['z']}")))
            .hasMessageContaining("does not offer");
    }

    @Test
    void aSingleChoiceQuestionTakesOneAnswer() {
        assertThatThrownBy(() -> types.validateResponse(json(everyType().get(0)),
            json("{'chosen':['a','b']}")))
            .hasMessageContaining("takes one answer");
    }

    @Test
    void anOrderingAnswerPlacesEveryItemOrNone() {
        assertThatThrownBy(() -> types.validateResponse(json(everyType().get(4)),
            json("{'order':['i1','i2']}")))
            .hasMessageContaining("every item or none");
    }

    // ------------------------------------------------------------ grading

    @Test
    void multipleChoiceCountsWrongPicksAgainstYou() {
        JsonNode question = json(everyType().get(1)); // correct: a, c of a/b/c

        assertThat(types.grade(question, json("{'chosen':['a','c']}"))).contains(new Correctness(2, 2));
        assertThat(types.grade(question, json("{'chosen':['a']}"))).contains(new Correctness(1, 2));
        // Selecting everything must not be full marks, or the count is not a measure of anything.
        assertThat(types.grade(question, json("{'chosen':['a','b','c']}")))
            .contains(new Correctness(1, 2));
        assertThat(types.grade(question, json("{'chosen':['b']}"))).contains(new Correctness(0, 2));
    }

    @Test
    void orderingCreditsAbsolutePositions() {
        JsonNode question = json(everyType().get(4)); // i2, i1, i3

        assertThat(types.grade(question, json("{'order':['i2','i1','i3']}")))
            .contains(new Correctness(3, 3));
        // Everything shifted by one has every PAIR right and every position wrong, and reporting
        // that as nearly full marks would be the arithmetic disagreeing with the question.
        assertThat(types.grade(question, json("{'order':['i1','i3','i2']}")))
            .contains(new Correctness(0, 3));
        assertThat(types.grade(question, json("{'order':['i2','i3','i1']}")))
            .contains(new Correctness(1, 3));
    }

    @Test
    void matchingCreditsThePairsThatMatched() {
        JsonNode question = json(everyType().get(3));

        assertThat(types.grade(question, json("""
            {"pairs":[{"left":"l1","right":"r1"},{"left":"l2","right":"r1"}]}""")))
            .contains(new Correctness(1, 2));
    }

    @Test
    void fillInIgnoresCaseAndSurroundingSpace() {
        JsonNode question = json(everyType().get(5));

        assertThat(types.grade(question, json("{'filled':{'b1':'  ANKARA '}}")))
            .contains(new Correctness(1, 1));
        assertThat(types.grade(question, json("{'filled':{'b1':'Angora'}}")))
            .as("the accepted list is for genuine variants, which only an author can judge")
            .contains(new Correctness(1, 1));
        assertThat(types.grade(question, json("{'filled':{'b1':'Istanbul'}}")))
            .contains(new Correctness(0, 1));
    }

    @Test
    void numericAcceptsWithinTheStatedTolerance() {
        JsonNode question = json(everyType().get(6)); // 6.35 +- 0.05

        assertThat(types.grade(question, json("{'value':6.35}"))).contains(Correctness.of(true));
        assertThat(types.grade(question, json("{'value':6.4}"))).contains(Correctness.of(true));
        assertThat(types.grade(question, json("{'value':6.5}"))).contains(Correctness.of(false));
        assertThat(types.grade(question, json("{}"))).contains(Correctness.of(false));
    }

    /** The one thing these two types answer, and the reason T-6.7 can ask instead of listing. */
    @Test
    void anEssayAndAnUploadSayAHumanHasToLook() {
        assertThat(types.grade(json(everyType().get(8)), json("{'text':'...'}"))).isEmpty();
        assertThat(types.grade(json(everyType().get(9)), json("{'uploadId':'u-1'}"))).isEmpty();
    }

    /**
     * A fixture as JSON.
     *
     * <p>Single quotes are accepted and turned into double (written as code points, because a
     * literal apostrophe next to a literal quote in this line is unreadable), which is why the
     * short documents in
     * this file read as {@code {'chosen':['a']}}. A one-line text block is not valid Java and
     * escaping every quote makes a response shape unreadable, which defeats the point of having
     * the documents in the test at all.
     */
    private static JsonNode json(String document) {
        return JSON.readTree(document.replace((char) 39, (char) 34));
    }
}
