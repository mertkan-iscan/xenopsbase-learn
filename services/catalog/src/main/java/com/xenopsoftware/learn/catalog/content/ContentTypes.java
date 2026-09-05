package com.xenopsoftware.learn.catalog.content;

import tools.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Every content type this build knows, found rather than listed (T-5.1).
 *
 * <p>The registry is what makes "adding a type touches the payload validation and the player, and
 * nothing else" structurally true instead of a hope. Nothing here enumerates the types; a
 * definition is a bean, and a build that has one more of them has one more type.
 *
 * <p><b>Duplicate codes fail startup.</b> Two definitions answering to {@code video} would make
 * which validator runs a matter of bean ordering — so it would work in development and reject a
 * customer's payload in production, or worse, accept one it should not. A refusal at startup is
 * the loudest available version of that information.
 */
@Component
public class ContentTypes {

    private final Map<String, ContentTypeDefinition> byCode;

    public ContentTypes(List<ContentTypeDefinition> definitions) {
        Map<String, ContentTypeDefinition> found = new LinkedHashMap<>();
        for (ContentTypeDefinition definition : definitions) {
            ContentTypeDefinition existing = found.put(definition.code(), definition);
            if (existing != null) {
                throw new IllegalStateException("Two content types both claim the code '"
                    + definition.code() + "': " + existing.getClass().getName() + " and "
                    + definition.getClass().getName() + ". Which validator runs would be decided "
                    + "by bean ordering, which is not a decision anybody made.");
            }
        }
        this.byCode = Map.copyOf(found);
    }

    /** Every code this build accepts, for the type picker and for the API docs. */
    public List<ContentTypeDefinition> all() {
        return List.copyOf(byCode.values());
    }

    public Optional<ContentTypeDefinition> find(String code) {
        return Optional.ofNullable(byCode.get(code));
    }

    /**
     * Validates a payload against its type, or refuses the request.
     *
     * <p>A 400 rather than a 500 for an unknown type: the code came from the caller, and a type
     * this build does not have is a thing they asked for that does not exist — not a bug here.
     * The message lists what does exist, because the alternative is a support ticket.
     */
    public void validate(String typeCode, JsonNode payload) {
        ContentTypeDefinition definition = find(typeCode).orElseThrow(() ->
            new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "No content type '" + typeCode + "'. This build has: "
                + String.join(", ", byCode.keySet())));
        try {
            definition.validate(payload == null ? tools.jackson.databind.node.NullNode.getInstance() : payload);
        } catch (IllegalArgumentException e) {
            // The definition speaks to an author; this turns that into a status code without
            // rewording it, because the definition is the only thing that knows what it meant.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
}
