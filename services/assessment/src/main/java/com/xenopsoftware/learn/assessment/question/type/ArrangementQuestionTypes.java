package com.xenopsoftware.learn.assessment.question.type;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JsonNode;

/**
 * The two types that ask somebody to arrange things (T-6.3): matching and ordering.
 *
 * <pre>
 * matching  options   {"left":[{"id":"l1","text":"CO2"}],"right":[{"id":"r1","text":"Electrical"}]}
 *           answerKey {"pairs":[{"left":"l1","right":"r1"}]}
 *           response  {"pairs":[...]}          // [] is unanswered
 *
 * ordering  options   {"items":[{"id":"i1","text":"Close the valve"}, ...]}
 *           answerKey {"order":["i2","i1", ...]}   // every item, once
 *           response  {"order":[...]}               // [] is unanswered
 * </pre>
 *
 * <h2>These are the two types the accessibility criterion is about</h2>
 *
 * <p>T-6.3 says hotspot and ordering "need keyboard-operable alternatives or they cannot be used at
 * all by some learners". A frontend cannot build one out of nothing: dragging is only replaceable
 * by "move 'Close the valve' up" if the item HAS a name. So {@code Shapes.ids} requires text on
 * every entry, and that requirement is here rather than in a style guide because a schema is the
 * only place a rule like this survives.
 *
 * <p>The same reasoning gives matching its shape: two named lists and pairs of ids, rather than
 * coordinates or indices. An index-based key would make inserting a left-hand item silently
 * re-point every pair after it.
 */
@Configuration(proxyBeanMethods = false)
public class ArrangementQuestionTypes {

    @Bean
    QuestionTypeDefinition matchingQuestionType() {
        return new Matching();
    }

    @Bean
    QuestionTypeDefinition orderingQuestionType() {
        return new Ordering();
    }

    private static final class Matching implements QuestionTypeDefinition {

        @Override
        public String code() {
            return "matching";
        }

        @Override
        public String displayName() {
            return "Matching";
        }

        @Override
        public void validateAsked(JsonNode options, JsonNode answerKey) {
            List<String> left = Shapes.ids(
                Shapes.array(options, "left", "A matching question", 2), "left");
            List<String> right = Shapes.ids(
                Shapes.array(options, "right", "A matching question", 2), "right");

            Map<String, String> pairs = pairs(answerKey, left, right, 1);
            if (pairs.size() != left.size()) {
                // Every left-hand item needs a home, or the learner is asked to match something
                // whose answer the key does not contain -- and they cannot tell which.
                throw new IllegalArgumentException(
                    "The answer key pairs " + pairs.size() + " of " + left.size()
                    + " left-hand items. Every one needs a pair, or a learner is asked to match "
                    + "something that cannot be marked.");
            }
        }

        @Override
        public void validateResponse(JsonNode options, JsonNode response) {
            List<String> left = Shapes.ids(
                Shapes.array(options, "left", "A matching question", 2), "left");
            List<String> right = Shapes.ids(
                Shapes.array(options, "right", "A matching question", 2), "right");
            pairs(response, left, right, 0);
        }

        @Override
        public Optional<Correctness> grade(JsonNode options, JsonNode answerKey, JsonNode response) {
            Map<String, String> correct = storedPairs(answerKey);
            Map<String, String> given = storedPairs(response);
            int credited = 0;
            for (Map.Entry<String, String> pair : correct.entrySet()) {
                if (pair.getValue().equals(given.get(pair.getKey()))) {
                    credited++;
                }
            }
            return Optional.of(new Correctness(credited, correct.size()));
        }

        /**
         * Pairs, with each left-hand item used at most once.
         *
         * <p>A right-hand item MAY repeat: "which of these are liquids" style questions map several
         * lefts onto one right, and forbidding it would be a rule about content dressed as a rule
         * about shape. Repeating a LEFT is different -- it is two answers to one question.
         */
        private static Map<String, String> pairs(JsonNode parent, List<String> left,
                List<String> right, int atLeast) {
            Map<String, String> pairs = new LinkedHashMap<>();
            for (JsonNode pair : Shapes.array(parent, "pairs", "A matching answer", atLeast)) {
                Shapes.object(pair, "Each pair");
                String from = Shapes.text(pair, "left", "Each pair");
                String to = Shapes.text(pair, "right", "Each pair");
                if (!left.contains(from)) {
                    throw new IllegalArgumentException("No left-hand item '" + from + "'. It has: "
                        + String.join(", ", left));
                }
                if (!right.contains(to)) {
                    throw new IllegalArgumentException("No right-hand item '" + to + "'. It has: "
                        + String.join(", ", right));
                }
                if (pairs.put(from, to) != null) {
                    throw new IllegalArgumentException(
                        "'" + from + "' is paired twice, which is two answers to one question.");
                }
            }
            return pairs;
        }

        private static Map<String, String> storedPairs(JsonNode parent) {
            Map<String, String> pairs = new LinkedHashMap<>();
            JsonNode array = parent == null ? null : parent.get("pairs");
            if (array == null || !array.isArray()) {
                return pairs;
            }
            for (JsonNode pair : array) {
                JsonNode from = pair.get("left");
                JsonNode to = pair.get("right");
                if (from != null && to != null && from.isTextual() && to.isTextual()) {
                    pairs.putIfAbsent(from.asString(), to.asString());
                }
            }
            return pairs;
        }
    }

    private static final class Ordering implements QuestionTypeDefinition {

        @Override
        public String code() {
            return "ordering";
        }

        @Override
        public String displayName() {
            return "Ordering";
        }

        @Override
        public void validateAsked(JsonNode options, JsonNode answerKey) {
            List<String> items = Shapes.ids(
                Shapes.array(options, "items", "An ordering question", 2), "item");
            List<String> order = Shapes.namedIds(answerKey, "order", "The answer key", items, 2);

            // A permutation, not a subset: an order that omits an item leaves a learner holding
            // something with nowhere to put it, and one that repeats an item cannot be satisfied.
            if (new HashSet<>(order).size() != order.size()) {
                throw new IllegalArgumentException("The answer key lists an item twice.");
            }
            if (order.size() != items.size()) {
                throw new IllegalArgumentException("The answer key orders " + order.size() + " of "
                    + items.size() + " items. Every item needs a position.");
            }
        }

        @Override
        public void validateResponse(JsonNode options, JsonNode response) {
            List<String> items = Shapes.ids(
                Shapes.array(options, "items", "An ordering question", 2), "item");
            List<String> order = Shapes.namedIds(response, "order", "A response", items, 0);
            if (new HashSet<>(order).size() != order.size()) {
                throw new IllegalArgumentException("A response places an item twice.");
            }
            if (!order.isEmpty() && order.size() != items.size()) {
                // A partial arrangement is not a partial answer -- it is an interface that let go
                // of an item. Storing it would make "3 of 5 in place" mean two different things.
                throw new IllegalArgumentException("An ordering answer places every item or none; "
                    + order.size() + " of " + items.size() + " were sent.");
            }
        }

        /**
         * Credit per item in its correct ABSOLUTE position.
         *
         * <p>The alternative -- credit for each correctly ordered PAIR -- is a better measure of
         * "nearly right" and is not what an author means by an order. A learner who shifts
         * everything by one has every pair right and every position wrong, and reporting that as
         * nearly full marks would be the arithmetic disagreeing with the question.
         */
        @Override
        public Optional<Correctness> grade(JsonNode options, JsonNode answerKey, JsonNode response) {
            List<String> correct = order(answerKey);
            List<String> given = order(response);
            int credited = 0;
            for (int position = 0; position < correct.size(); position++) {
                if (position < given.size() && correct.get(position).equals(given.get(position))) {
                    credited++;
                }
            }
            return Optional.of(new Correctness(credited, correct.size()));
        }

        private static List<String> order(JsonNode parent) {
            JsonNode array = parent == null ? null : parent.get("order");
            if (array == null || !array.isArray()) {
                return List.of();
            }
            List<String> order = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (JsonNode entry : array) {
                if (entry.isTextual() && seen.add(entry.asString())) {
                    order.add(entry.asString());
                }
            }
            return order;
        }
    }
}
