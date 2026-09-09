package com.xenopsoftware.learn.assessment.question;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The two directions a question body travels: JSON in, column out, and back (T-6.2).
 *
 * <p>Catalog's {@code ContentPayloads} carries the full reasoning and this is deliberately its
 * twin. The two points that matter here:
 *
 * <p><b>Its own mapper, not the application's.</b> What is in this column is the record of what a
 * learner was asked, read back by versions of this service that have not been written yet. It must
 * not change shape because somebody tuned how the API serialises.
 *
 * <p><b>Jackson 3 ({@code tools.jackson}), not Jackson 2.</b> Boot 4 puts both on the classpath
 * and its HTTP message converter is the Jackson 3 one, so a DTO carrying a Jackson 2
 * {@code JsonNode} compiles, wires, and then fails every request with a type definition error
 * naming a class that is plainly present.
 *
 * <p>{@link #equal} is here rather than in the service because it is the same decision as the
 * other two: what counts as the same body is a property of the document, not of the caller.
 */
@Component
public class QuestionBodies {

    private final JsonMapper json = JsonMapper.builder().build();

    /** A body on its way to the column. */
    public String write(JsonNode body) {
        if (body == null || body.isNull()) {
            // Not an empty object, unlike a content payload: a question with nothing in it is a
            // question nobody could answer, and accepting one here would put a row in the history
            // that renders as a blank page three months from now.
            throw new IllegalArgumentException("A question version needs a body: it is what was asked");
        }
        return json.writeValueAsString(body);
    }

    /** A body on its way back out, so a client parses one document rather than two. */
    public JsonNode read(String stored) {
        return json.readTree(stored);
    }

    /**
     * Whether two bodies say the same thing.
     *
     * <p>Compared as documents rather than as strings: {@code {"a":1,"b":2}} and
     * {@code {"b":2,"a":1}} are the same question, and a client that serialises its object in a
     * different order has not edited anything. Getting this wrong would produce a new version —
     * permanent, and visible to every analyst reading item statistics — for a change nobody made.
     */
    public boolean equal(String stored, JsonNode candidate) {
        return read(stored).equals(candidate);
    }
}
