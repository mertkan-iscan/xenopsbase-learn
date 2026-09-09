package com.xenopsoftware.learn.assessment.question.type;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * The checks every type would otherwise write four times (T-6.3).
 *
 * <p>Every method throws {@link IllegalArgumentException} with a sentence addressed to an author,
 * because that is who reads it: {@code QuestionTypes} turns it into a 400 without rewording, since
 * only the type knows what it meant.
 *
 * <p><b>Ids are how everything here refers to everything else.</b> An option has an id, a key names
 * ids, a response names ids. That is what makes a key that does not match its options detectable at
 * all — and it is why {@link #ids} refuses duplicates: two choices called {@code a} make a key
 * ambiguous rather than wrong, which is worse.
 */
final class Shapes {

    private Shapes() {
    }

    static JsonNode object(JsonNode node, String what) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException(what + " must be an object");
        }
        return node;
    }

    static JsonNode array(JsonNode parent, String field, String what, int atLeast) {
        JsonNode value = parent == null ? null : parent.get(field);
        if (value == null || !value.isArray()) {
            throw new IllegalArgumentException(what + " needs a '" + field + "' array");
        }
        if (value.size() < atLeast) {
            throw new IllegalArgumentException(
                what + "'s '" + field + "' needs at least " + atLeast + " entries, not " + value.size());
        }
        return value;
    }

    static String text(JsonNode parent, String field, String what) {
        JsonNode value = parent == null ? null : parent.get(field);
        if (value == null || !value.isTextual() || value.asString().isBlank()) {
            throw new IllegalArgumentException(what + " needs a non-empty '" + field + "'");
        }
        return value.asString();
    }

    /**
     * The ids of a list of {@code {id, text}} entries.
     *
     * <p><b>Text is required on every entry, not only an id</b>, and that is the accessibility
     * criterion rather than a nicety. An ordering question whose items are known only by id can be
     * presented as draggable boxes and as nothing else; with text, a keyboard user can be offered
     * "move 'Close the valve' up". A type that let the text be optional would be a type that is
     * unusable by some learners in a way no test would catch.
     */
    static List<String> ids(JsonNode entries, String what) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> ordered = new ArrayList<>();
        for (JsonNode entry : entries) {
            object(entry, "Each " + what);
            String id = text(entry, "id", "Each " + what);
            text(entry, "text", "Each " + what + " (id '" + id + "')");
            if (!seen.add(id)) {
                throw new IllegalArgumentException(
                    "Two " + what + " entries share the id '" + id + "'. A key naming it would be "
                    + "ambiguous rather than wrong, which is harder to notice.");
            }
            ordered.add(id);
        }
        return ordered;
    }

    /**
     * Every entry of an array of ids, refusing anything the options do not offer.
     *
     * @param atLeast 1 for a key -- a question with no right answer cannot be marked -- and 0 for
     *                a response, because an unanswered question is an ordinary thing for a learner
     *                to submit and refusing it would turn "I do not know" into a failed request
     */
    static List<String> namedIds(JsonNode parent, String field, String what, List<String> offered,
            int atLeast) {
        List<String> named = new ArrayList<>();
        for (JsonNode entry : array(parent, field, what, atLeast)) {
            if (!entry.isTextual()) {
                throw new IllegalArgumentException(what + "'s '" + field + "' holds ids, as strings");
            }
            String id = entry.asString();
            if (!offered.contains(id)) {
                // The whole point of the issue, in one message: the two halves disagree, and this
                // is the moment where saying so is still cheap.
                throw new IllegalArgumentException(
                    what + " names '" + id + "', which this question does not offer. It has: "
                    + String.join(", ", offered));
            }
            named.add(id);
        }
        return named;
    }

    /**
     * A reference to something catalog owns, never a URL.
     *
     * <p>T-6.3 asks for media in stems to travel as {@code content_item} references. A URL in a
     * question body would be a copy of a fact catalog owns, and the first time an asset moves --
     * a re-encode, a new provider (ADR-0101) -- every question that embedded it renders a broken
     * image, with no way to find them but a text search.
     */
    static void reference(JsonNode parent, String field, String what) {
        String value = text(parent, field, what);
        if (value.contains("://") || value.startsWith("/")) {
            throw new IllegalArgumentException(
                what + "'s '" + field + "' must be a content item id, not a URL. Media belongs to "
                + "catalog (T-5.1); a URL copied here breaks the first time the asset moves.");
        }
    }
}
