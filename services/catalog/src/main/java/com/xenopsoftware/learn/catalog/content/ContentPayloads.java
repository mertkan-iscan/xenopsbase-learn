package com.xenopsoftware.learn.catalog.content;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The two directions a typed payload travels: JSON in, column out, and back (T-5.1).
 *
 * <p><b>Its own mapper, not the application's</b> — the same choice {@code AuditLogger} and
 * {@code ValkeyPermissions} make in {@code identity}, and for the same reason. What is in this
 * column is a stored record read back by versions of this service that have not been written yet,
 * and it must not change shape because somebody tuned how the API serialises.
 *
 * <p><b>Jackson 3 ({@code tools.jackson}), not Jackson 2 ({@code com.fasterxml.jackson}).</b>
 * Boot 4 puts BOTH on the classpath and its HTTP message converter is the Jackson 3 one, so a
 * DTO carrying a Jackson 2 {@code JsonNode} compiles, wires and then fails every request with
 * "Type definition error: [simple type, class com.fasterxml.jackson.databind.JsonNode]" — which
 * names a class that is plainly present and says nothing about there being two of it. The
 * existing Jackson 2 uses elsewhere in this repository are internal to their own class and never
 * cross the web layer, which is why they work.
 *
 * <p>One component rather than a mapper in each of the service and the resource: two mappers is
 * two places for that decision to drift.
 */
@Component
public class ContentPayloads {

    private final JsonMapper json = JsonMapper.builder().build();

    /** A payload on its way to the column. A missing one is an empty object, never null. */
    public String write(JsonNode payload) {
        if (payload == null || payload.isNull()) {
            return "{}";
        }
        return json.writeValueAsString(payload);
    }

    /** A payload on its way back out, so a client parses one document rather than two. */
    public JsonNode read(String stored) {
        // Jackson 3 throws unchecked, so there is no catch here that would only rethrow: a
        // column that is not JSON is only reachable if something wrote it by hand, and the
        // JacksonException already names the column contents.
        return json.readTree(stored == null || stored.isBlank() ? "{}" : stored);
    }
}
