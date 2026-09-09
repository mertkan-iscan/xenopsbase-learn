package com.xenopsoftware.learn.assessment.question.type;

import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JsonNode;

/**
 * Click the right part of the picture (T-6.3).
 *
 * <pre>
 * options   {"image":"b3f1....",                          // a content item id
 *            "regions":[{"id":"r1","text":"The CO2 extinguisher","shape":{...}}]}
 * answerKey {"correct":["r1"]}
 * response  {"selected":["r1"]}       // [] is unanswered
 * </pre>
 *
 * <h2>A region has a name, and that is the accessibility criterion</h2>
 *
 * <p>T-6.3 says hotspot "needs a keyboard-operable alternative or cannot be used at all by some
 * learners". The alternative is a list of the regions, chosen the way a multiple-choice question is
 * chosen -- and that only exists if each region carries text a person can read. So it is required
 * here, at the schema, and not left to a frontend to wish for.
 *
 * <p>The consequence is worth stating: <b>the response shape is identical to a choice question's,
 * apart from the field name.</b> That is not a coincidence, it is the point. The keyboard path and
 * the pointer path submit the same thing, so there is one thing to grade and one thing to store,
 * and the accessible route cannot rot separately from the visual one.
 *
 * <h2>The shape of a region is deliberately not specified</h2>
 *
 * <p>{@code shape} is passed through unvalidated: rectangles, polygons and circles are a renderer's
 * vocabulary, and the first pass at the player will discover which it wants (T-10.3). What IS
 * validated is everything grading depends on -- the ids, the names, and that the key names regions
 * this question actually has -- so a wrong guess about geometry costs a frontend change and not a
 * migration.
 */
@Configuration(proxyBeanMethods = false)
public class HotspotQuestionType {

    /**
     * NOT called {@code hotspotQuestionType}, and the name is load-bearing.
     *
     * <p>Component scanning binds this {@code @Configuration} under its own decapitalised class
     * name, so a {@code @Bean} method spelled the same way is a
     * {@code BeanDefinitionOverrideException} -- not a preference, a refusal to start. This
     * repository has paid for that shape twice: once in a test configuration, and once in
     * {@code ProblemDocumentation}, where all three services failed to start while every unit test
     * stayed green, because a test that calls the method never registers it.
     */
    @Bean
    QuestionTypeDefinition clickTheRightPartOfThePicture() {
        return new Hotspot();
    }

    private static final class Hotspot implements QuestionTypeDefinition {

        @Override
        public String code() {
            return "hotspot";
        }

        @Override
        public String displayName() {
            return "Hotspot";
        }

        @Override
        public void validateAsked(JsonNode options, JsonNode answerKey) {
            Shapes.object(options, "A hotspot question's options");
            // The image is catalog's, by id. A URL here would break the first time the asset moves
            // and leave no way to find the questions that embedded it.
            Shapes.reference(options, "image", "A hotspot question");

            List<String> regions = Shapes.ids(
                Shapes.array(options, "regions", "A hotspot question", 1), "region");
            List<String> correct =
                Shapes.namedIds(answerKey, "correct", "The answer key", regions, 1);

            if (correct.size() == regions.size()) {
                throw new IllegalArgumentException(
                    "Every region is marked correct, so there is nothing to get wrong.");
            }
        }

        @Override
        public void validateResponse(JsonNode options, JsonNode response) {
            List<String> regions = Shapes.ids(
                Shapes.array(options, "regions", "A hotspot question", 1), "region");
            Shapes.namedIds(response, "selected", "A response", regions, 0);
        }

        /**
         * Right selections minus wrong ones, as multiple choice counts them and for the same
         * reason: selecting every region must not be full marks.
         */
        @Override
        public Optional<Correctness> grade(JsonNode options, JsonNode answerKey, JsonNode response) {
            List<String> correct = ids(answerKey, "correct");
            List<String> selected = ids(response, "selected");
            long right = selected.stream().filter(correct::contains).count();
            long wrong = selected.size() - right;
            return Optional.of(new Correctness((int) Math.max(0, right - wrong), correct.size()));
        }

        private static List<String> ids(JsonNode parent, String field) {
            JsonNode array = parent == null ? null : parent.get(field);
            if (array == null || !array.isArray()) {
                return List.of();
            }
            return array.valueStream().filter(JsonNode::isTextual).map(JsonNode::asString).toList();
        }
    }
}
