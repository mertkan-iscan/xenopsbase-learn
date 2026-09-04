package com.xenopsoftware.learn.streaming.playback;

import java.util.Optional;
import java.util.UUID;

/**
 * Who the caller is, as {@code app_user.id} (ADR-0104).
 *
 * <p>This exists because of a rule the schema enforces rather than a preference: <b>no column in
 * this service may store an IdP {@code sub}</b>. A {@code sub} is a link identity may repair —
 * T-1.7's relink script exists precisely to repoint one — so a {@code sub} written into another
 * module's table is a reference that goes stale silently, and an audit trail that quietly stops
 * pointing at anybody is worse than none. The durable identifier is {@code app_user.id}, and only
 * identity can turn one into the other.
 *
 * <p>Asked only when a refusal is being audited, never on the way to a token. Refusals are rare
 * and audit is the one place the answer is needed, so the hop lands there instead of on the
 * learner hot path.
 */
public interface ViewerDirectory {

    /**
     * The current caller's {@code app_user.id}, or empty when identity could not be asked.
     *
     * <p>Empty rather than an exception: this is called while recording a refusal that has
     * already been decided, and failing to name the actor must not turn a refusal into a
     * grant — nor lose the record of it.
     */
    Optional<UUID> currentAppUserId();
}
