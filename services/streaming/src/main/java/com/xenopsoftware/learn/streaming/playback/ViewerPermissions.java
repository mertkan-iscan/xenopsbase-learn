package com.xenopsoftware.learn.streaming.playback;

/**
 * Whether the caller may view content at all (T-2.1's catalog, checked from another service).
 *
 * <p>A port because the answer belongs to identity and the question is asked on this service's
 * hot path — the two implementations that matter are "ask identity" and, in a test, "say yes"
 * — and because it keeps the four checks of T-3.4 four separate, individually testable things.
 *
 * <p>Note what this deliberately does NOT answer: <em>which</em> content. Holding
 * {@code content:view} means a person is the kind of user who watches things; whether they may
 * watch <em>this</em> node is the assignment check, and whether they may watch it <em>yet</em>
 * is the gate. Collapsing the three into one call is how a permission ends up meaning nothing.
 */
public interface ViewerPermissions {

    /**
     * Whether the current request's caller holds {@code content:view} anywhere in their tenant.
     *
     * <p>Implementations must fail closed. This is the boundary, not a fast path: an identity
     * outage means the entitlement decision cannot be made, and a decision that cannot be made
     * is a refusal. That is not in tension with ADR-0101 — T-3.10's property is that playback
     * already under way survives our services stopping, and it survives precisely because the
     * token outlives us. Minting a NEW one always required us to be up.
     */
    boolean mayViewContent();
}
