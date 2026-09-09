package com.xenopsoftware.learn.assessment.question.type;

import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JsonNode;

/**
 * The three types that ask somebody to pick (T-6.3): single choice, multiple choice, true/false.
 *
 * <pre>
 * options   {"choices":[{"id":"a","text":"Water"},{"id":"b","text":"CO2"}]}
 * answerKey {"correct":["b"]}
 * response  {"chosen":["b"]}          // [] is an unanswered question
 * </pre>
 *
 * <p>True/false is the same machinery with the choices supplied rather than authored, which is why
 * it lives here rather than in a file of its own: an author writes a stem and a key, and the two
 * choices are ours. Giving it its own shape would mean a second response format for the same
 * question a learner cannot tell apart.
 *
 * <h2>Multiple choice counts wrong picks against you, and that is not a scoring mode</h2>
 *
 * <p>{@code credited} is right picks minus wrong ones, floored at zero. The obvious alternative --
 * count the correct ones a learner found -- gives full marks for selecting every choice, so it is
 * not a measure of correctness at all, and no scoring mode on top of it could recover the
 * information.
 *
 * <p>What T-6.4 decides is whether partial credit is USED: whether 2 of 3 is two thirds of the
 * marks, all of them, or none. That is a mode. This is arithmetic.
 */
@Configuration(proxyBeanMethods = false)
public class ChoiceQuestionTypes {

    @Bean
    QuestionTypeDefinition singleChoiceQuestionType() {
        return new PickFrom("single-choice", "Single choice", true);
    }

    @Bean
    QuestionTypeDefinition multipleChoiceQuestionType() {
        return new PickFrom("multiple-choice", "Multiple choice", false);
    }

    @Bean
    QuestionTypeDefinition trueFalseQuestionType() {
        return new TrueFalse();
    }

    /** One implementation, two types: the difference is how many picks are allowed. */
    private record PickFrom(String code, String displayName, boolean exactlyOne)
            implements QuestionTypeDefinition {

        @Override
        public void validateAsked(JsonNode options, JsonNode answerKey) {
            List<String> offered = Shapes.ids(
                Shapes.array(options, "choices", "A " + displayName.toLowerCase() + " question", 2),
                "choice");
            List<String> correct =
                Shapes.namedIds(answerKey, "correct", "The answer key", offered, 1);

            if (exactlyOne && correct.size() != 1) {
                // Not a style rule: two correct answers on a single-choice question is a question
                // a learner cannot answer correctly, and it is only discoverable by sitting it.
                throw new IllegalArgumentException(
                    "A single-choice question has exactly one correct choice, not " + correct.size()
                    + ". Use multiple-choice if more than one is right.");
            }
            if (correct.size() == offered.size()) {
                throw new IllegalArgumentException(
                    "Every choice is marked correct, so there is nothing to get wrong.");
            }
        }

        @Override
        public void validateResponse(JsonNode options, JsonNode response) {
            List<String> offered = Shapes.ids(
                Shapes.array(options, "choices", "A " + displayName.toLowerCase() + " question", 2),
                "choice");
            List<String> chosen = Shapes.namedIds(response, "chosen", "A response", offered, 0);
            if (exactlyOne && chosen.size() > 1) {
                throw new IllegalArgumentException(
                    "This question takes one answer; " + chosen.size() + " were sent.");
            }
        }

        @Override
        public Optional<Correctness> grade(JsonNode options, JsonNode answerKey, JsonNode response) {
            List<String> correct = ids(answerKey, "correct");
            List<String> chosen = ids(response, "chosen");
            long right = chosen.stream().filter(correct::contains).count();
            long wrong = chosen.size() - right;
            int credited = (int) Math.max(0, right - wrong);
            return Optional.of(new Correctness(credited, correct.size()));
        }
    }

    /**
     * True/false, whose choices are not the author's to write.
     *
     * <p>The response is still a list of chosen ids, so a client that renders choices renders this
     * one too, and {@code []} still means unanswered. A boolean would have been shorter and would
     * have given the frontend a second shape to special-case, which is the branching T-6.3 asks
     * for one dispatch point to avoid.
     */
    private static final class TrueFalse implements QuestionTypeDefinition {

        private static final List<String> CHOICES = List.of("true", "false");

        @Override
        public String code() {
            return "true-false";
        }

        @Override
        public String displayName() {
            return "True or false";
        }

        @Override
        public void validateAsked(JsonNode options, JsonNode answerKey) {
            // No options to check: an author writes the stem and the key. An options object is
            // accepted and ignored rather than refused, so a client that sends `{}` for every type
            // does not have to know this one is different.
            List<String> correct = Shapes.namedIds(answerKey, "correct", "The answer key", CHOICES, 1);
            if (correct.size() != 1) {
                throw new IllegalArgumentException(
                    "A true/false question is either true or false, not both.");
            }
        }

        @Override
        public void validateResponse(JsonNode options, JsonNode response) {
            List<String> chosen = Shapes.namedIds(response, "chosen", "A response", CHOICES, 0);
            if (chosen.size() > 1) {
                throw new IllegalArgumentException("This question takes one answer.");
            }
        }

        @Override
        public Optional<Correctness> grade(JsonNode options, JsonNode answerKey, JsonNode response) {
            List<String> correct = ids(answerKey, "correct");
            List<String> chosen = ids(response, "chosen");
            return Optional.of(Correctness.of(chosen.equals(correct)));
        }
    }

    /**
     * Ids out of an already-validated document.
     *
     * <p>No checking, deliberately: grading runs on a response that was validated before it was
     * stored, so a malformed one here is a bug in this service and not a caller's mistake. Throwing
     * a validation error would blame a learner for it.
     */
    private static List<String> ids(JsonNode parent, String field) {
        JsonNode array = parent == null ? null : parent.get(field);
        if (array == null || !array.isArray()) {
            return List.of();
        }
        return array.valueStream().filter(JsonNode::isTextual).map(JsonNode::asString).toList();
    }
}
