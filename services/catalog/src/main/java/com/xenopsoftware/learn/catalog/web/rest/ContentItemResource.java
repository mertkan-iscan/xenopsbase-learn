package com.xenopsoftware.learn.catalog.web.rest;

import tools.jackson.databind.JsonNode;
import com.xenopsoftware.learn.catalog.content.ContentItem;
import com.xenopsoftware.learn.catalog.content.ContentItemService;
import com.xenopsoftware.learn.catalog.content.ContentPayloads;
import com.xenopsoftware.learn.catalog.content.ContentState;
import com.xenopsoftware.learn.catalog.content.ContentTypes;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Content items over HTTP (T-5.1).
 *
 * <p>Authentication-only for now, like every endpoint in {@code streaming}: the permission
 * catalog and its evaluator live in {@code identity}, and a cross-service permission check needs
 * grants to travel (T-9.11 carries the identity, not yet the grants). {@code CatalogCoverageTest}
 * lists each endpoint with that reason, so the gap is recorded rather than discovered.
 */
@RestController
@RequestMapping("/api/v1/content-items")
public class ContentItemResource {

    private final ContentItemService items;
    private final ContentTypes types;
    private final ContentPayloads payloads;

    public ContentItemResource(ContentItemService items, ContentTypes types,
            ContentPayloads payloads) {
        this.items = items;
        this.types = types;
        this.payloads = payloads;
    }

    public record CreateRequest(String type, String title, String description, JsonNode payload,
                                Set<String> tags) {}

    public record UpdateRequest(String title, String description, JsonNode payload,
                                Set<String> tags) {}

    public record StateRequest(String state) {}

    /** What a type picker renders. */
    public record TypeView(String code, String displayName) {}

    /**
     * @param payload returned as JSON rather than a string, so a client parses the response once
     *                rather than parsing a field out of it
     */
    public record ItemView(UUID id, String type, String title, String description, String state,
                           JsonNode payload, List<String> tags, boolean shared,
                           Instant createdAt, Instant updatedAt) {}

    /** Every type this build knows. Derived from the registry, so it cannot drift from it. */
    @GetMapping("/types")
    public List<TypeView> types() {
        return types.all().stream()
            .map(type -> new TypeView(type.code(), type.displayName()))
            .toList();
    }

    @GetMapping
    public List<ItemView> search(@RequestParam(required = false) String type,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Set<String> tag) {
        return items.search(type, parseState(state), q, tag).stream().map(this::view).toList();
    }

    @GetMapping("/{id}")
    public ItemView item(@PathVariable UUID id) {
        return view(items.get(id));
    }

    @PostMapping
    public ItemView create(@RequestBody CreateRequest request) {
        return view(items.create(request.type(), request.title(), request.description(),
            request.payload(), request.tags()));
    }

    @PutMapping("/{id}")
    public ItemView update(@PathVariable UUID id, @RequestBody UpdateRequest request) {
        return view(items.update(id, request.title(), request.description(), request.payload(),
            request.tags()));
    }

    /**
     * Publishing and archiving. A PUT of the state rather than {@code POST /publish}, because
     * there are three states and four legal moves between them: an endpoint per move is four
     * endpoints that each have to know the rules, and the rules are {@code ContentState}'s.
     */
    @PutMapping("/{id}/state")
    public ItemView state(@PathVariable UUID id, @RequestBody StateRequest request) {
        return view(items.moveTo(id, request.state()));
    }

    private static ContentState parseState(String state) {
        if (state == null || state.isBlank()) {
            return null;
        }
        try {
            return ContentState.valueOf(state.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "state must be DRAFT, PUBLISHED or ARCHIVED");
        }
    }

    private ItemView view(ContentItem item) {
        return new ItemView(item.getId(), item.getType(), item.getTitle(), item.getDescription(),
            item.getState().name(), payloads.read(item.getPayload()), item.getTags(), item.isShared(),
            item.getCreatedAt(), item.getUpdatedAt());
    }
}
