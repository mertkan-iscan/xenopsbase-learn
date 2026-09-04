package com.xenopsoftware.learn.streaming.playback;

/**
 * Identity's answer, without identity (T-9.11 is the real hop). The check under test is that
 * the decision refuses when the answer is no — not how the answer travels.
 */
public class StubViewerPermissions implements ViewerPermissions {

    private volatile boolean allowed = true;

    public void allow(boolean allowed) {
        this.allowed = allowed;
    }

    @Override
    public boolean mayViewContent() {
        return allowed;
    }
}
