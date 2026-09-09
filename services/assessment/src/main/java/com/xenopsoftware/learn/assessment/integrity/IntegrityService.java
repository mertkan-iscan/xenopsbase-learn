package com.xenopsoftware.learn.assessment.integrity;

import com.xenopsoftware.learn.assessment.attempt.Attempt;
import com.xenopsoftware.learn.assessment.attempt.AttemptNotFound;
import com.xenopsoftware.learn.assessment.attempt.AttemptRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Recording what a browser says happened, and showing it to a person (T-6.8).
 *
 * <h2>The whole of what this class refuses to do</h2>
 *
 * <p>It does not score, it does not fail, it does not end an attempt, and it does not warn anybody.
 * "No automatic failure, score reduction or termination from any signal" is the criterion, and it
 * is kept structurally rather than by care: nothing in {@code grading} or {@code scoring} may depend
 * on this package, and an ArchUnit rule fails the build if that changes.
 *
 * <p>That rule matters more than it looks. The promise is easy to keep until the week somebody is
 * asked to catch a cheat, and the change that breaks it is one import in a service that already
 * computes scores.
 *
 * <h2>Signals stop at the end of the attempt</h2>
 *
 * <p>A submitted attempt records nothing further. Everything after it is a person using the
 * product, not sitting an exam, and collecting it would be collecting behaviour for no reason at
 * all — which is the test any of this has to pass.
 */
@Service
public class IntegrityService {

    private final AttemptRepository attempts;
    private final AttemptEvents events;
    private final IntegrityProperties properties;
    private final Clock clock;

    public IntegrityService(AttemptRepository attempts, AttemptEvents events,
            IntegrityProperties properties, Clock clock) {
        this.attempts = attempts;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Records one signal for the caller's own attempt.
     *
     * @return false when it was dropped -- the attempt is over, or it has already produced more
     *         than the cap allows. The caller answers the client normally either way: a refusal
     *         would make a player retry, which is the opposite of what a flood needs, and a learner
     *         is not owed an error for telemetry they did not ask to send
     */
    @Transactional
    public boolean record(UUID attemptId, UUID learnerId, IntegritySignal kind, Instant reportedAt,
            JsonNode detail) {
        Attempt attempt = attempts.findById(attemptId).orElseThrow(AttemptNotFound::new);
        if (!attempt.getLearnerId().equals(learnerId)) {
            // The same 404 as a missing one (T-2.4). "It exists but is not yours" is a fact about
            // somebody else's exam.
            throw new AttemptNotFound();
        }
        if (attempt.getState() != Attempt.State.IN_PROGRESS) {
            return false;
        }
        return events.record(attemptId, kind, reportedAt, detail, clock.instant(),
            properties.maxPerAttempt());
    }

    /**
     * What a reviewer sees beside an attempt.
     *
     * <p>Read-only and never summarised into a number. A "suspicion score" would be exactly the
     * thing this task exists to not build: it would be acted on, and it would be acted on hardest
     * against the learners whose innocent explanations are the most common.
     */
    @Transactional(readOnly = true)
    public List<AttemptEvents.Event> of(UUID attemptId) {
        return events.of(attemptId);
    }

    /** How much longer these are kept — the clock retention is measured against. */
    public Instant retentionCutoff() {
        return clock.instant().minus(properties.retention());
    }
}
