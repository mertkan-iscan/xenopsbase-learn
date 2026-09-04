package com.xenopsoftware.learn.streaming.playback;

import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

/**
 * Resolves the caller through identity's {@code GET /api/v1/me} — the endpoint whose own
 * documentation says other modules hold the id and resolve it here rather than copying names
 * into tables they would never keep current.
 */
@Component
public class IdentityViewerDirectory implements ViewerDirectory {

    private static final Logger LOG = LoggerFactory.getLogger(IdentityViewerDirectory.class);

    /** Only the field this service is allowed to keep; the rest of {@code /me} is not ours. */
    private record Me(UUID id) {}

    private final IdentityCalls identity;

    public IdentityViewerDirectory(IdentityCalls identity) {
        this.identity = identity;
    }

    @Override
    public Optional<UUID> currentAppUserId() {
        try {
            Me me = identity.client().get().uri("/api/v1/me").retrieve().body(Me.class);
            return Optional.ofNullable(me).map(Me::id);
        } catch (RestClientException identityUnreachable) {
            // The refusal still stands and is still recorded; it simply cannot name the person.
            // Losing the row entirely to name them would be the wrong trade.
            LOG.warn("Could not resolve the caller through identity while auditing a refused "
                + "playback token; the refusal is recorded without an actor.", identityUnreachable);
            return Optional.empty();
        }
    }
}
