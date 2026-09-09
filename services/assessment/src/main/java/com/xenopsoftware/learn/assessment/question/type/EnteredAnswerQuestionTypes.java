package com.xenopsoftware.learn.assessment.question.type;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JsonNode;

/**
 * The two types where the learner types the answer (T-6.3): fill-in and numeric.
 *
 * <pre>
 * fill-in  options   {"blanks":[{"id":"b1","text":"the capital"}]}
 *          answerKey {"accepted":{"b1":["Ankara","Angora"]}}
 *          response  {"filled":{"b1":"ankara"}}     // a missing blank is unanswered
 *
 * numeric  options   {"unit":"kg"}                   // optional, shown next to the field
 *          answerKey {"value":3.5,"tolerance":0.1}
 *          response  {"value":3.45}
 * </pre>
 *
 * <h2>Fill-in compares case-insensitively, and the accepted list is for spelling</h2>
 *
 * <p>Trimmed and lower-cased on both sides. An author who has to list "Ankara" and "ankara" and
 * "ANKARA" to be safe will list two of the three and be surprised by a learner who typed the
 * third, and the surprise arrives as a wrong mark on a right answer.
 *
 * <p>What the accepted list IS for is genuine variants -- "Angora", "CO2" and "carbon dioxide" --
 * which is a judgement only the author can make. A case-sensitive variant would be a different
 * type or a flag on this one, and either is T-6.4's to decide if anybody ever asks.
 *
 * <h2>Numeric has a tolerance because a float comparison is a bug waiting</h2>
 *
 * <p>{@code tolerance} is required and may be zero. Requiring it means an author has said what
 * "close enough" means for their question, rather than inheriting whatever a comparison happens to
 * do -- and 0.1 + 0.2 is famously not 0.3, so a question about grams would mark a right answer
 * wrong for reasons no author could debug.
 */
@Configuration(proxyBeanMethods = false)
public class EnteredAnswerQuestionTypes {

    @Bean
    QuestionTypeDefinition fillInQuestionType() {
        return new FillIn();
    }

    @Bean
    QuestionTypeDefinition numericQuestionType() {
        return new Numeric();
    }

    private static final class FillIn implements QuestionTypeDefinition {

        @Override
        public String code() {
            return "fill-in";
        }

        @Override
        public String displayName() {
            return "Fill in the blank";
        }

        @Override
        public void validateAsked(JsonNode options, JsonNode answerKey) {
            List<String> blanks = Shapes.ids(
                Shapes.array(options, "blanks", "A fill-in question", 1), "blank");
            JsonNode accepted = Shapes.object(
                answerKey == null ? null : answerKey.get("accepted"),
                "A fill-in answer key's 'accepted'");

            for (String blank : blanks) {
                JsonNode answers = accepted.get(blank);
                if (answers == null || !answers.isArray() || answers.isEmpty()) {
                    throw new IllegalArgumentException("The blank '" + blank
                        + "' has no accepted answers. A blank nobody can fill correctly is a mark "
                        + "nobody can earn.");
                }
                for (JsonNode answer : answers) {
                    if (!answer.isTextual() || answer.asString().isBlank()) {
                        throw new IllegalArgumentException(
                            "The blank '" + blank + "' accepts non-empty strings.");
                    }
                }
            }
            for (String named : names(accepted)) {
                if (!blanks.contains(named)) {
                    throw new IllegalArgumentException("The answer key accepts answers for '"
                        + named + "', which is not a blank in this question. It has: "
                        + String.join(", ", blanks));
                }
            }
        }

        @Override
        public void validateResponse(JsonNode options, JsonNode response) {
            List<String> blanks = Shapes.ids(
                Shapes.array(options, "blanks", "A fill-in question", 1), "blank");
            JsonNode filled = Shapes.object(
                response == null ? null : response.get("filled"), "A fill-in response's 'filled'");
            for (String named : names(filled)) {
                if (!blanks.contains(named)) {
                    throw new IllegalArgumentException(
                        "A response fills '" + named + "', which is not a blank in this question.");
                }
                if (!filled.get(named).isTextual()) {
                    throw new IllegalArgumentException("The blank '" + named + "' takes text.");
                }
            }
        }

        @Override
        public Optional<Correctness> grade(JsonNode options, JsonNode answerKey, JsonNode response) {
            JsonNode accepted = answerKey == null ? null : answerKey.get("accepted");
            JsonNode filled = response == null ? null : response.get("filled");
            if (accepted == null || !accepted.isObject()) {
                return Optional.of(new Correctness(0, 1));
            }
            List<String> blanks = names(accepted);
            int credited = 0;
            for (String blank : blanks) {
                JsonNode given = filled == null ? null : filled.get(blank);
                if (given != null && given.isTextual() && accepts(accepted.get(blank), given.asString())) {
                    credited++;
                }
            }
            return Optional.of(new Correctness(credited, Math.max(1, blanks.size())));
        }

        private static boolean accepts(JsonNode answers, String given) {
            String normalised = normalise(given);
            for (JsonNode answer : answers) {
                if (answer.isTextual() && normalise(answer.asString()).equals(normalised)) {
                    return true;
                }
            }
            return false;
        }

        private static String normalise(String value) {
            return value.strip().toLowerCase(Locale.ROOT);
        }
    }

    private static final class Numeric implements QuestionTypeDefinition {

        @Override
        public String code() {
            return "numeric";
        }

        @Override
        public String displayName() {
            return "Numeric";
        }

        @Override
        public void validateAsked(JsonNode options, JsonNode answerKey) {
            number(answerKey, "value", "A numeric answer key");
            double tolerance = number(answerKey, "tolerance", "A numeric answer key");
            if (tolerance < 0) {
                throw new IllegalArgumentException(
                    "A tolerance of " + tolerance + " accepts nothing at all.");
            }
        }

        @Override
        public void validateResponse(JsonNode options, JsonNode response) {
            JsonNode value = response == null ? null : response.get("value");
            if (value == null || value.isNull()) {
                // Unanswered, which is a learner's prerogative.
                return;
            }
            if (!value.isNumber()) {
                throw new IllegalArgumentException("A numeric response's 'value' is a number. "
                    + "A learner who typed words needs to be told that, not marked wrong for it.");
            }
        }

        /**
         * Compared as DECIMALS, not as doubles, and this file predicted the bug before it wrote
         * it: the first version used {@code Math.abs(given - expected) <= tolerance} and marked
         * 6.4 wrong against 6.35 with a tolerance of 0.05, because that subtraction is
         * 0.05000000000000071 in binary floating point.
         *
         * <p>Which is the exact failure the javadoc above says a tolerance exists to prevent -- a
         * right answer marked wrong for a reason no author could debug -- reproduced by the
         * mechanism meant to prevent it. JSON carries these as decimal literals, so comparing
         * them as decimals is both exact and what the author wrote.
         */
        @Override
        public Optional<Correctness> grade(JsonNode options, JsonNode answerKey, JsonNode response) {
            JsonNode given = response == null ? null : response.get("value");
            if (given == null || !given.isNumber()) {
                return Optional.of(Correctness.of(false));
            }
            BigDecimal expected = answerKey.get("value").decimalValue();
            BigDecimal tolerance = answerKey.get("tolerance").decimalValue();
            BigDecimal off = given.decimalValue().subtract(expected).abs();
            return Optional.of(Correctness.of(off.compareTo(tolerance) <= 0));
        }

        private static double number(JsonNode parent, String field, String what) {
            JsonNode value = parent == null ? null : parent.get(field);
            if (value == null || !value.isNumber()) {
                throw new IllegalArgumentException(what + " needs a numeric '" + field + "'");
            }
            return value.asDouble();
        }
    }

    /** An object's field names, in order. */
    private static List<String> names(JsonNode object) {
        List<String> names = new ArrayList<>();
        object.propertyNames().forEach(names::add);
        return names;
    }
}
