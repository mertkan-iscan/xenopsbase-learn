package com.xenopsoftware.learn.assessment.scoring;

import tools.jackson.databind.JsonNode;

/**
 * Whether a learner attempted a question at all (T-6.4).
 *
 * <p><b>Why this exists.</b> {@link com.xenopsoftware.learn.assessment.question.type.Correctness}
 * cannot answer it: {@code credited} is zero both for somebody who answered and got it wrong and
 * for somebody who left it blank, because T-6.3 floors it there deliberately. Negative marking has
 * to tell those apart. Penalising a blank converts "I do not know" into a worse outcome than a
 * guess, which is the exact opposite of what negative marking is for.
 *
 * <p><b>Why it is not a method on the question type.</b> It asks nothing about the type — no code,
 * no branch, no table of behaviours — so putting it behind T-6.3's one dispatch point would give
 * ten identical implementations and an eleventh for every type added. The rule below is a property
 * of the response documents this product produces, and every one of the ten types satisfies it by
 * construction: an unanswered question sends an empty array ({@code {"chosen":[]}}), an empty
 * object ({@code {"filled":{}}}), a blank string, or nothing at all.
 *
 * <p>A type for which that were false would be a type whose "I did not answer" is indistinguishable
 * from its "here is my answer", and it would have to fix that before it could be graded at all.
 */
public final class Responses {

    private Responses() {
    }

    /**
     * Whether there is anything in here a person could have meant.
     *
     * <p>Recursive, because a response is one level deep in most types and two in fill-in
     * ({@code {"filled":{"b1":"ankara"}}}), and "answered" has to mean the same thing in both.
     */
    public static boolean wasAnswered(JsonNode response) {
        if (response == null || response.isNull() || response.isMissingNode()) {
            return false;
        }
        if (response.isTextual()) {
            return !response.asString().isBlank();
        }
        if (response.isNumber() || response.isBoolean()) {
            // A number is an answer even when it is zero, and false is an answer to a true/false.
            return true;
        }
        if (response.isArray() || response.isObject()) {
            // valueStream() rather than iteration, which is what the question types already use to
            // walk a container (T-6.3) -- one way of reading these documents, not two.
            return response.valueStream().anyMatch(Responses::wasAnswered);
        }
        return false;
    }
}
