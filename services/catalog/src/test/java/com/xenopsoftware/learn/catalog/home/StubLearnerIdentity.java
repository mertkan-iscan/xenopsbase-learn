package com.xenopsoftware.learn.catalog.home;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

/**
 * Identity, without identity running.
 *
 * <p>Turning a token into an {@code app_user.id} is a service-to-service call (T-9.11) and belongs
 * to identity; what the home screen tests are about is everything that happens afterwards. Stubbing
 * the hop is the same choice {@code streaming}'s playback tests make for the same call.
 *
 * <p>It can also be told to answer with nothing, which is the state that matters on its own: a
 * screen that cannot name its learner must say so rather than show somebody else's.
 */
public class StubLearnerIdentity extends LearnerIdentity {

    private volatile UUID learnerId;

    public StubLearnerIdentity(UUID learnerId) {
        super(null, Clock.systemUTC());
        this.learnerId = learnerId;
    }

    public void resolvesTo(UUID learnerId) {
        this.learnerId = learnerId;
    }

    /** Identity is unreachable: the hop this class exists to stand in for has failed. */
    public void unreachable() {
        this.learnerId = null;
    }

    @Override
    public Optional<UUID> current(String tenantId, String subject) {
        return Optional.ofNullable(learnerId);
    }
}
