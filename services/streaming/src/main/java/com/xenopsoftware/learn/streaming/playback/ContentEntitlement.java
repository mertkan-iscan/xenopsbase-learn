package com.xenopsoftware.learn.streaming.playback;

import java.util.Optional;
import java.util.UUID;

/**
 * The seam between "who may watch" and "what a course says about this learner" (T-3.4).
 *
 * <p>A port for the same reason {@code MediaProvider} is one, and for a sharper one: the module
 * that answers this — catalog, with content items (T-5.1), gates (T-5.3) and assignments
 * (T-5.5) — <b>does not exist yet</b>. The entitlement decision is still the thing T-3.4 owes,
 * and it can be built, ordered, audited, rate-limited and tested in full against a port whose
 * only implementation today refuses. What must not happen is the decision being written later,
 * somewhere else, once there is finally something to ask.
 *
 * <p>The default implementation is {@link UnassignedContent} and it denies everything. That
 * direction is not an accident: an entitlement check whose "not wired up yet" state is
 * <em>allow</em> is a check that ships permissive and is discovered by a customer.
 */
public interface ContentEntitlement {

    /**
     * What catalog says about this node for this viewer, or empty when there is no such node
     * within the viewer's tenant.
     *
     * <p>Empty and "exists but not assigned" are different answers on purpose — the caller
     * renders both as the same 404 but audits them apart, and only catalog can tell them apart.
     */
    Optional<NodeEntitlement> lookUp(UUID nodeId, Viewer viewer);
}
