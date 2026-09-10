package com.xenopsoftware.learn.assessment.question;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A question body on its way to a learner (T-6.9).
 *
 * <p>Extracted from {@code ReviewService}, which had it private, because a second reader arrived:
 * an attempt in progress now carries the body of each item so the learner can be shown the
 * question at all. Two copies of this would be two places to forget the same field.
 */
public final class ServedQuestion {

    private ServedQuestion() {}

    /**
     * The question as served, with the answer key removed.
     *
     * <p><b>A copy with the field removed, not a flag telling a renderer to hide it.</b> A key that
     * travels and is hidden is a key in the learner's browser, in their network tab, and in
     * whatever caches it on the way.
     *
     * <p>{@code feedback} goes with it: the author's explanation of why an answer is right is the
     * answer in prose, and a learner mid-attempt has no more business with it than with the key.
     *
     * @param served the version's body exactly as it was stored
     * @return a copy without {@code answerKey} or {@code feedback}
     */
    public static JsonNode withoutTheKey(JsonNode served) {
        ObjectNode redacted = (ObjectNode) served.deepCopy();
        redacted.remove("answerKey");
        redacted.remove("feedback");
        return redacted;
    }
}
