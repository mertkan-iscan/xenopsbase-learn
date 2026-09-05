package com.xenopsoftware.learn.catalog.content;

import tools.jackson.databind.JsonNode;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Creating, editing and publishing content items (T-5.1).
 *
 * <p>Two rules live here rather than in the entity, because both need something the entity does
 * not have: the payload must be valid <em>for its type</em>, which needs the registry, and the
 * type must exist at all, which needs the same. Everything else — the state machine, tag
 * normalisation, what a title may be — is the entity's, where it cannot be bypassed by a second
 * caller.
 */
@Service
public class ContentItemService {

    private final ContentItemRepository items;
    private final ContentTypes types;
    private final ContentPayloads payloads;

    public ContentItemService(ContentItemRepository items, ContentTypes types,
            ContentPayloads payloads) {
        this.items = items;
        this.types = types;
        this.payloads = payloads;
    }

    @Transactional
    public ContentItem create(String type, String title, String description, JsonNode payload,
            Set<String> tags) {
        types.validate(type, payload);
        return items.save(ContentItem.draft(type, title, description, payloads.write(payload), tags));
    }

    /**
     * Edits an item in place.
     *
     * <p>The payload is re-validated even though it was valid when written: a type's rules can
     * tighten between one save and the next, and a row saved under the old ones must not be
     * silently re-saved under them. Re-validating is also what makes a type's validator the only
     * place its rules live.
     */
    @Transactional
    public ContentItem update(UUID id, String title, String description, JsonNode payload,
            Set<String> tags) {
        ContentItem item = require(id);
        if (payload != null) {
            types.validate(item.getType(), payload);
            item.repayload(payloads.write(payload));
        }
        if (title != null) {
            item.rename(title, description);
        }
        if (tags != null) {
            item.retag(tags);
        }
        return items.save(item);
    }

    /**
     * Moves an item's state, turning the entity's refusal into a 409.
     *
     * <p>409 rather than 400: the request was well formed and the caller is not wrong about what
     * they asked for, they are wrong about what state the thing is in — which is a conflict with
     * the current state of the resource, and is what a UI needs to distinguish in order to say
     * "someone else published this already" rather than "bad request".
     */
    @Transactional
    public ContentItem moveTo(UUID id, String state) {
        ContentItem item = require(id);
        ContentState next;
        try {
            next = ContentState.valueOf(state == null ? "" : state.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "state must be DRAFT, PUBLISHED or ARCHIVED");
        }
        try {
            item.moveTo(next);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
        return items.save(item);
    }

    @Transactional(readOnly = true)
    public ContentItem get(UUID id) {
        return require(id);
    }

    /**
     * Search across this company's items.
     *
     * <p><b>Tags are filtered in memory, and the reason is the tenant boundary.</b> Postgres
     * answers array containment with the GIN index the migration builds, but reaching it means a
     * native query — and a native query is not filtered by Hibernate's discriminator (T-1.1), so
     * it would have to name the tenant itself. One hand-written {@code WHERE tenant_id} is
     * exactly the clause the discriminator exists to make impossible to forget.
     *
     * <p>The trade is honest at this size: a company's library is hundreds to low thousands of
     * items, already narrowed by type, state and text before this runs. When a tenant's library
     * outgrows that, the fix is a native query that names its tenant explicitly and a test that
     * proves it — not a quiet change here.
     */
    @Transactional(readOnly = true)
    public List<ContentItem> search(String type, ContentState state, String text, Set<String> tags) {
        // Empty rather than null: see ContentItemRepository.search on why a null here becomes a
        // bytea in Postgres.
        String needle = text == null || text.isBlank() ? "" : text.strip().toLowerCase(Locale.ROOT);
        List<ContentItem> found = items.search(type, state, needle);
        if (tags == null || tags.isEmpty()) {
            return found;
        }
        Set<String> wanted = tags.stream()
            .filter(tag -> tag != null && !tag.isBlank())
            .map(tag -> tag.strip().toLowerCase(Locale.ROOT))
            .collect(java.util.stream.Collectors.toSet());
        // ALL of the requested tags, not any: a person narrowing by two tags is narrowing.
        return found.stream().filter(item -> item.getTags().containsAll(wanted)).toList();
    }

    /**
     * Whether something may point at this item for the first time (T-5.2, T-5.3, T-5.5 all ask).
     *
     * <p>Published here rather than left for each caller to write, so the reference rule has one
     * definition. A caller that asks a repository directly and compares states itself is a caller
     * that will disagree with this one the day ARCHIVED gains a nuance.
     */
    @Transactional(readOnly = true)
    public boolean acceptsNewReferences(UUID id) {
        return items.findById(id).map(item -> item.getState().acceptsNewReferences()).orElse(false);
    }

    private ContentItem require(UUID id) {
        // findById, tenant-filtered: another company's id is not found rather than refused, which
        // is the 404-not-403 shape ADR-0102 promises.
        return items.findById(id).orElseThrow(ContentItemNotFound::new);
    }
}
