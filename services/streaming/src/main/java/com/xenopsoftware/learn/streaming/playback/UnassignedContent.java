package com.xenopsoftware.learn.streaming.playback;

import jakarta.annotation.PostConstruct;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Catalog does not exist yet, so nothing is assigned to anybody (T-5.1/T-5.3/T-5.5).
 *
 * <p>The counterpart of T-2.4's {@code UngrantedResolver}, and it exists for the same reason:
 * the safe stand-in for an authorization question with no source of truth is the one that
 * refuses. Every other option is worse in a way that only shows up later — a permissive default
 * makes every test pass and every learner entitled to everything, and a stubbed "yes" in main
 * code is a permissive default wearing a different name.
 *
 * <p>Main code rather than test code, matching {@code FakeMediaProvider}'s reasoning inverted:
 * {@code make run S=streaming} must start and its playback endpoint must answer, and what it
 * must answer today is "no". The startup warning is the honest version of that, because a
 * platform where nobody can watch anything is a fact an operator should read in the log rather
 * than deduce from 404s.
 *
 * <p>Unconditional, on purpose. A {@code @ConditionalOnMissingBean} here would read as "a real
 * catalog adapter can slide in beside this", and what should actually happen the day catalog
 * exists is that this class is DELETED -- leaving a fail-closed stand-in registered next to a
 * working implementation is how a refusal nobody expected gets debugged for an afternoon.
 */
@Component
public class UnassignedContent implements ContentEntitlement {

    private static final Logger LOG = LoggerFactory.getLogger(UnassignedContent.class);

    @PostConstruct
    void warnLoudly() {
        LOG.warn("No catalog is wired to this service, so EVERY playback token request is "
            + "refused as unassigned. This is the fail-closed default, not a fault; courses, "
            + "gates and assignments arrive with T-5.1/T-5.3/T-5.5.");
    }

    @Override
    public Optional<NodeEntitlement> lookUp(UUID nodeId, Viewer viewer) {
        return Optional.empty();
    }
}
