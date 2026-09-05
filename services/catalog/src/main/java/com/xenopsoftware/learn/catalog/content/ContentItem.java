package com.xenopsoftware.learn.catalog.content;

import com.xenopsoftware.learn.common.tenancy.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A thing a learner can be given: a video, a package, a document, a test (T-5.1).
 *
 * <p>Everything downstream points HERE rather than at a video or a package — structure (T-5.2),
 * assignment (T-5.5), gating (T-5.3), progress and reporting. That is the whole reason this table
 * exists: without it each of those grows a branch per content type, and the branch that gets
 * forgotten when a sixth type arrives is found by a customer.
 */
@Entity
@Table(name = "content_item")
public class ContentItem extends TenantOwned {

    @Id
    private UUID id;

    /** The registry's code, not an enum: the set of types is open (see {@link ContentTypes}). */
    @Column(nullable = false, length = 32)
    private String type;

    @Column(nullable = false, length = 512)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ContentState state;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false, columnDefinition = "text[]")
    private String[] tags;

    @Column(nullable = false)
    private boolean shared;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ContentItem() {
        // Hibernate.
    }

    /**
     * A new item, always DRAFT.
     *
     * <p>No parameter chooses the starting state, and that is deliberate: an API that could
     * create something already PUBLISHED is an API through which unfinished work reaches learners
     * in one request, and publishing is the moment worth being a separate, auditable act.
     */
    public static ContentItem draft(String type, String title, String description, String payload,
            Set<String> tags) {
        ContentItem item = new ContentItem();
        item.id = UUID.randomUUID();
        item.type = type;
        item.state = ContentState.DRAFT;
        item.payload = payload == null ? "{}" : payload;
        item.shared = false;
        item.createdAt = Instant.now();
        item.rename(title, description);
        item.retag(tags);
        return item;
    }

    public void rename(String newTitle, String newDescription) {
        if (newTitle == null || newTitle.isBlank()) {
            throw new IllegalArgumentException("An item needs a title");
        }
        this.title = newTitle.strip();
        this.description = newDescription == null || newDescription.isBlank()
            ? null : newDescription.strip();
        touch();
    }

    /** Replaces the payload wholesale. Validation is {@link ContentTypes}', before this is called. */
    public void repayload(String newPayload) {
        this.payload = newPayload == null ? "{}" : newPayload;
        touch();
    }

    /**
     * Tags, normalised on the way in: trimmed, lowercased, de-duplicated, blanks dropped.
     *
     * <p>Normalising here rather than at the query is what makes {@code Onboarding} and
     * {@code onboarding} the same tag. The alternative — case-insensitive matching at read time —
     * means the same label appears twice in every tag list a screen renders, which is the visible
     * half of the same bug.
     */
    public void retag(Set<String> newTags) {
        Set<String> normalised = new LinkedHashSet<>();
        if (newTags != null) {
            for (String tag : newTags) {
                if (tag != null && !tag.isBlank()) {
                    normalised.add(tag.strip().toLowerCase(Locale.ROOT));
                }
            }
        }
        this.tags = normalised.toArray(String[]::new);
        touch();
    }

    /**
     * Moves to another state, or refuses.
     *
     * <p>The refusal names both states rather than saying "invalid transition", because the
     * caller is usually an author who pressed a button and the useful information is which
     * button was wrong.
     */
    public void moveTo(ContentState next) {
        if (state == next) {
            return;
        }
        if (!state.canBecome(next)) {
            throw new IllegalArgumentException(
                "A " + state + " item cannot become " + next
                + (state == ContentState.PUBLISHED && next == ContentState.DRAFT
                    ? ". Something may already point at it and a learner may be part-way through "
                      + "it; editing published content is a new version (T-5.7)."
                    : "."));
        }
        this.state = next;
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getType() {
        return type;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public ContentState getState() {
        return state;
    }

    public String getPayload() {
        return payload;
    }

    public List<String> getTags() {
        return tags == null ? List.of() : Arrays.asList(tags);
    }

    /** Whether the platform offers this item to other tenants (T-5.1). Nothing reads it yet. */
    public boolean isShared() {
        return shared;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
