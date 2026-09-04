package com.xenopsoftware.learn.streaming.playback;

import java.util.Optional;
import java.util.UUID;

/**
 * Identity resolving the caller, without identity. Fixed rather than generated per call, so a
 * test can assert that the refusal names the person it was actually about.
 */
public class StubViewerDirectory implements ViewerDirectory {

    /** One learner, one id — the audit assertions compare against this. */
    public static final UUID LEARNER_ID = UUID.fromString("00000000-0000-4000-8000-00000000abcd");

    private volatile UUID appUserId = LEARNER_ID;

    /** Null is the real failure mode: identity unreachable while a refusal is being written. */
    public void resolvesTo(UUID appUserId) {
        this.appUserId = appUserId;
    }

    @Override
    public Optional<UUID> currentAppUserId() {
        return Optional.ofNullable(appUserId);
    }
}
