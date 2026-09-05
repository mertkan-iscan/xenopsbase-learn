package com.xenopsoftware.learn.catalog.content;

/**
 * The lifecycle of a content item, and — more importantly — what may point at each state (T-5.1).
 *
 * <p><b>The reference rule lives here rather than at each call site.</b> Structure (T-5.2),
 * assignment (T-5.5) and gating (T-5.3) all have to decide whether an item may be pointed at, and
 * three copies of that decision is two that go stale. They ask {@link #acceptsNewReferences()}.
 */
public enum ContentState {

    /** Being written. Visible to authors, referenced by nothing. */
    DRAFT,

    /** Finished and referenceable. The only state a NEW reference may point at. */
    PUBLISHED,

    /**
     * Withdrawn from further use, still honoured for references that already exist.
     *
     * <p>Not deleted, and the difference matters to a learner: someone halfway through a course
     * whose third node was archived this morning must not find a hole where it was. Existing
     * references keep working; new ones are refused.
     */
    ARCHIVED;

    /** Whether something may point at an item in this state for the FIRST time. */
    public boolean acceptsNewReferences() {
        return this == PUBLISHED;
    }

    /**
     * Whether this transition is allowed.
     *
     * <p><b>PUBLISHED never returns to DRAFT.</b> By the time an item is published something may
     * reference it and a learner may be part-way through it, and un-publishing would change what
     * they are in the middle of, silently. Editing a published item is a new version, which is
     * T-5.7's — and that task exists precisely because this transition is refused here.
     */
    public boolean canBecome(ContentState next) {
        return switch (this) {
            case DRAFT -> next == PUBLISHED || next == ARCHIVED;
            case PUBLISHED -> next == ARCHIVED;
            case ARCHIVED -> next == PUBLISHED;
        };
    }
}
