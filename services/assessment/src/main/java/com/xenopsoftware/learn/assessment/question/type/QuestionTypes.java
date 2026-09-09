package com.xenopsoftware.learn.assessment.question.type;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

/**
 * The one dispatch point (T-6.3).
 *
 * <p>Every type this build knows, found rather than listed — the same registry catalog uses for
 * content types, and for the same reason: "adding a type is a bounded change" is structurally true
 * when nothing enumerates them, and a hope when something does.
 *
 * <p>It is also the criterion about branching. Rendering and grading both derive from the type,
 * and there is exactly one place that turns a type code into behaviour: this class. A {@code
 * switch} on a type code anywhere else would be a second place, and the second place is the one
 * that is missing a case.
 *
 * <h2>The body's shape, which this file owns</h2>
 *
 * <pre>
 * {
 *   "type":      "single-choice",
 *   "stem":      "Which extinguisher suits an electrical fire?",
 *   "media":     ["b3f1...."],            // content item ids, optional
 *   "options":   { ... per type ... },
 *   "answerKey": { ... per type ... }
 * }
 * </pre>
 *
 * <p>T-6.2 deliberately left this document opaque so that this issue could give it a shape without
 * a migration. Nothing outside this package needs to know the shape: authoring validates through
 * {@link #validateAsked}, delivery will validate through {@link #validateResponse}, and grading
 * through {@link #grade}.
 *
 * <p><b>Duplicate codes fail startup.</b> Two definitions answering to {@code numeric} would make
 * which validator runs a matter of bean ordering, so it would work in development and refuse a
 * customer's question in production — or accept one it should not.
 */
@Component
public class QuestionTypes {

    private final Map<String, QuestionTypeDefinition> byCode;

    public QuestionTypes(List<QuestionTypeDefinition> definitions) {
        Map<String, QuestionTypeDefinition> found = new LinkedHashMap<>();
        for (QuestionTypeDefinition definition : definitions) {
            QuestionTypeDefinition existing = found.put(definition.code(), definition);
            if (existing != null) {
                throw new IllegalStateException("Two question types both claim the code '"
                    + definition.code() + "': " + existing.getClass().getName() + " and "
                    + definition.getClass().getName() + ". Which validator runs would be decided "
                    + "by bean ordering, which is not a decision anybody made.");
            }
        }
        this.byCode = Map.copyOf(found);
    }

    /** Every type this build accepts, for the author's picker and for the API description. */
    public List<QuestionTypeDefinition> all() {
        return List.copyOf(byCode.values());
    }

    public Optional<QuestionTypeDefinition> find(String code) {
        return Optional.ofNullable(byCode.get(code));
    }

    /**
     * Refuses a question body this build cannot mean.
     *
     * <p>Called on every save and every edit (T-6.2's {@code QuestionService}), so a version that
     * reaches a learner has been through it — which is the difference between a malformed question
     * being a 400 for its author and being an unscoreable exam for a cohort.
     */
    public void validateAsked(JsonNode body) {
        QuestionTypeDefinition definition = typeOf(body);
        Shapes.text(body, "stem", "A question");
        validateMedia(body);
        try {
            definition.validateAsked(body.get("options"), body.get("answerKey"));
        } catch (IllegalArgumentException refused) {
            // The definition speaks to an author; this turns that into a status code without
            // rewording it, because the definition is the only thing that knows what it meant.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, refused.getMessage());
        }
    }

    /** Refuses a learner's response, against the body they were served. For T-6.6. */
    public void validateResponse(JsonNode body, JsonNode response) {
        QuestionTypeDefinition definition = typeOf(body);
        try {
            definition.validateResponse(body.get("options"), response);
        } catch (IllegalArgumentException refused) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, refused.getMessage());
        }
    }

    /**
     * How much of a response was right, or empty when a human has to look.
     *
     * <p>No exception translation here on purpose: by the time this runs, the response has been
     * validated and stored, and a malformed one is a bug in this service rather than a caller's
     * mistake. Turning it into a 400 would blame a learner for it.
     */
    public Optional<Correctness> grade(JsonNode body, JsonNode response) {
        return typeOf(body).grade(body.get("options"), body.get("answerKey"), response);
    }

    private QuestionTypeDefinition typeOf(JsonNode body) {
        Shapes.object(body, "A question body");
        String code = Shapes.text(body, "type", "A question");
        return find(code).orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "No question type '" + code + "'. This build has: " + String.join(", ", byCode.keySet())));
    }

    /**
     * Media in a stem is a list of content item ids.
     *
     * <p>Optional, because most questions are a sentence. Checked when present, because the failure
     * of getting it wrong is silent: a URL works until the asset moves, and then every question
     * that embedded one renders a broken image with no way to find them but a text search.
     */
    private static void validateMedia(JsonNode body) {
        JsonNode media = body.get("media");
        if (media == null || media.isNull()) {
            return;
        }
        if (!media.isArray()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "A question's 'media' is a list of content item ids");
        }
        for (JsonNode entry : media) {
            if (!entry.isTextual() || entry.asString().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A question's 'media' holds content item ids, as strings");
            }
            String id = entry.asString();
            if (id.contains("://") || id.startsWith("/")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A question's media must be content item ids, not URLs. Media belongs to "
                    + "catalog (T-5.1); a URL copied here breaks the first time the asset moves.");
            }
        }
    }
}
