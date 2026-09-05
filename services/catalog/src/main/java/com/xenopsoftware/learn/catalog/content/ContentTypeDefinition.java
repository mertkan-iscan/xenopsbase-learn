package com.xenopsoftware.learn.catalog.content;

import tools.jackson.databind.JsonNode;

/**
 * One kind of content, and the only thing that knows what its payload means (T-5.1).
 *
 * <p><b>An interface with one implementation per type, rather than an enum.</b> The catalog of
 * PERMISSIONS is an enum on purpose (T-2.1): a permission is only real if code checks it, so the
 * closed set is the point. A content type is the opposite — the acceptance criterion is that
 * adding one touches the payload validation and the player and nothing else, and an enum makes
 * "add a constant" a change to a file every other type also lives in, with a switch somewhere
 * that now has a missing case.
 *
 * <p>Here a new type is a new class. {@code ContentTypes} finds it, and a test proves the claim
 * by registering a sixth type and asserting nothing else needed touching.
 */
public interface ContentTypeDefinition {

    /** Stored in {@code content_item.type}. Stable forever once shipped: rows carry it. */
    String code();

    /** What an author sees in a type picker. */
    String displayName();

    /**
     * Refuses a payload this type cannot mean.
     *
     * <p>Throws {@link IllegalArgumentException} with a sentence an author could act on. Called
     * before the row is written and again on every update, because a type's rules can tighten
     * and a row saved under the old ones must not be re-saved under them.
     */
    void validate(JsonNode payload);
}
