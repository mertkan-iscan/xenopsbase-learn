package com.xenopsoftware.learn.streaming.playback;

import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

/**
 * Asks identity what the caller can reach, over the service-to-service seam (T-9.11).
 *
 * <p>It calls the endpoint a UI already calls — {@code GET /api/v1/me/reach/content/view} —
 * rather than a new "check this for me" endpoint, and that is the important part: scope
 * resolution stays in the one place that implements it (T-2.3's {@code ScopeResolver}). A
 * second service that decided for itself what a grant reaches would be a second implementation
 * of authorization, drifting quietly from the first.
 *
 * <p>The caller's own token is forwarded unchanged by the service-call machinery, so identity
 * answers for the person, not for this service — which is why the answer can be trusted without this
 * service asserting anything about who is behind the request.
 */
@Component
public class IdentityViewerPermissions implements ViewerPermissions {

    private static final Logger LOG = LoggerFactory.getLogger(IdentityViewerPermissions.class);

    private record ReachView(String permission, boolean wholeTenant, Set<UUID> groupIds,
                             Set<UUID> courseIds) {

        boolean reachesAnything() {
            return wholeTenant
                || (groupIds != null && !groupIds.isEmpty())
                || (courseIds != null && !courseIds.isEmpty());
        }
    }

    private final IdentityCalls identity;

    public IdentityViewerPermissions(IdentityCalls identity) {
        this.identity = identity;
    }

    @Override
    public boolean mayViewContent() {
        try {
            ReachView reach = identity.client()
                .get()
                .uri("/api/v1/me/reach/{resource}/{action}", "content", "view")
                .retrieve()
                .body(ReachView.class);
            return reach != null && reach.reachesAnything();
        } catch (RestClientException identityUnreachable) {
            // Closed, and loudly. The comparison to make is not "is refusing harsh" but "what
            // does the permissive version do" -- and the permissive version turns any identity
            // outage into every learner being entitled to every video in their tenant, for as
            // long as nobody notices.
            LOG.warn("Could not ask identity what this caller may view; refusing the playback "
                + "token. The entitlement decision is the boundary and it fails closed.",
                identityUnreachable);
            return false;
        }
    }
}
