package com.xenopsoftware.learn.assessment.question.type;

import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * One kind of question, and the only thing that knows what its three shapes mean (T-6.3).
 *
 * <h2>Three shapes, not one, and that is the whole issue</h2>
 *
 * <p>A question version's body is a {@code jsonb} column (T-6.2), so the database will not catch a
 * malformed one. The specific malformation this exists to refuse is <b>an answer key that does not
 * match its options</b> — a key naming choice {@code d} on a question with three choices. Nothing
 * fails at save time; it fails when a learner cannot be scored, which is after the exam.
 *
 * <p>So the shapes are validated together rather than one at a time:
 *
 * <ul>
 *   <li>{@link #validateAsked} — the options and the key, cross-checked against each other, at
 *       authoring time;
 *   <li>{@link #validateResponse} — what a learner submitted, against the options they were shown;
 *   <li>{@link #grade} — whether it was right, per type, in the one place that knows.
 * </ul>
 *
 * <h2>An interface with one implementation per type, rather than an enum</h2>
 *
 * <p>The same choice catalog's {@code ContentTypeDefinition} made, for the same reason: the
 * acceptance criterion is that adding a type is a bounded change, and an enum makes "add a
 * constant" a change to the file every other type lives in, with a switch somewhere that now has
 * a missing case. Here a new type is a new bean; {@link QuestionTypes} finds it, and a test proves
 * the claim by adding an eleventh and asserting nothing else needed touching.
 *
 * <p>The permission catalog IS an enum (T-2.1) and that is not a contradiction: a permission is
 * only real if code checks it, so its closed set is the point.
 */
public interface QuestionTypeDefinition {

    /** Stored in the body's {@code type}. Stable forever once shipped: rows carry it. */
    String code();

    /** What an author sees in a type picker. */
    String displayName();

    /**
     * Refuses options and a key that cannot mean anything together.
     *
     * <p>Throws {@link IllegalArgumentException} with a sentence an author could act on. Called
     * before a version is written and again on every edit, because a type's rules can tighten and
     * a row saved under the old ones must not be re-saved under them.
     */
    void validateAsked(JsonNode options, JsonNode answerKey);

    /**
     * Refuses a response this type cannot mean, against the options it was asked with.
     *
     * <p>Nothing submits responses yet — the attempt is T-6.6's. This exists now because the
     * shape a learner sends is decided by the same file that decides the shape they were shown,
     * and deciding them apart is how the two stop agreeing.
     */
    void validateResponse(JsonNode options, JsonNode response);

    /**
     * How much of this response was right, or empty when no machine can say.
     *
     * <p><b>A fraction rather than a boolean</b>, because multiple choice, matching and ordering
     * all have a meaningful partial answer and a boolean would force every type into
     * all-or-nothing. What is done with the fraction — all-or-nothing, partial credit, negative
     * marking — is a scoring mode, and that is T-6.4's decision, not this one's.
     *
     * <p><b>Empty means a human has to look</b>: an essay and a file upload are not gradable by
     * comparison at all, and saying so here is what lets T-6.7 build a manual queue without
     * asking every type whether it belongs in one.
     */
    Optional<Correctness> grade(JsonNode options, JsonNode answerKey, JsonNode response);
}
