package com.xenopsoftware.learn.assessment.attempt;

import com.xenopsoftware.learn.assessment.grading.Grading;
import com.xenopsoftware.learn.common.tenancy.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * One learner sitting one test (T-6.6).
 *
 * <h2>The clock is the server's, and it keeps running</h2>
 *
 * <p>{@link #getExpiresAt()} is computed once, at start, from the test's limit and the server's
 * clock. Nothing moves it afterwards — there is no endpoint that could, and the entity offers no
 * method.
 *
 * <p><b>Resume is allowed and the clock does not pause.</b> That is one rule, chosen rather than
 * configured, and the reason is the criterion two lines above it: a pausing clock has to move
 * {@code expires_at}, and the only thing that could tell us when to pause is the browser saying it
 * went away — which is precisely the client honesty the whole design refuses to depend on. "Sixty
 * minutes from when you start" is also a rule a person can hold in their head.
 *
 * <p>The cost, stated: a learner whose laptop dies loses that time. What they do not lose is their
 * work, because answers are saved as they are given.
 */
@Entity
@Table(name = "attempt")
public class Attempt extends TenantOwned {

    /**
     * Where an attempt can be.
     *
     * <p><b>{@link #EXPIRED} does not mean ungraded.</b> It means the clock ran out. Answers are
     * refused after the deadline, so nothing in an expired attempt was written late, and throwing
     * the work away because the submit request was slow would punish a network for a rule about
     * time to think.
     */
    public enum State {
        IN_PROGRESS,
        SUBMITTED,
        /** The clock ran out. Still marked (T-6.7). */
        EXPIRED,
        /** An untimed attempt nobody came back to. Reached on a schedule, never by staying open. */
        ABANDONED;

        public boolean terminal() {
            return this != IN_PROGRESS;
        }
    }

    @Id
    private UUID id;

    @Column(name = "test_id", nullable = false)
    private UUID testId;

    @Column(name = "learner_id", nullable = false)
    private UUID learnerId;

    @Column(name = "attempt_number", nullable = false)
    private short attemptNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private State state;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    /**
     * Where marking is — a second axis, not a fifth {@link State} (T-6.7).
     *
     * <p>{@code state} says how the attempt ended; this says whether anybody has marked it. An
     * attempt that expired <em>and</em> is waiting on an essay is both, and one enum would have to
     * lose one of those facts.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Grading grading;

    @Column(name = "score_raw")
    private BigDecimal scoreRaw;

    @Column(name = "score_scaled")
    private BigDecimal scoreScaled;

    @Column(name = "score_percent")
    private Short scorePercent;

    /**
     * Whether they passed, or null because nobody has said yet.
     *
     * <p><b>Null does not mean failed.</b> A gate that reads it as false is the bug T-6.7 exists to
     * prevent, which is why {@link #getGrading()} is beside it and why nothing downstream may read
     * one without the other — {@link #isSettled()} is the question a gate should actually ask.
     */
    @Column
    private Boolean passed;

    @Column(name = "graded_at")
    private Instant gradedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Attempt() {
        // Hibernate.
    }

    /**
     * @param timeLimit null when the test is untimed, in which case there is <b>no</b>
     *                  {@code expires_at} rather than a very distant one — "no deadline" and "a
     *                  deadline in the year 3000" are different facts and only the first is true
     */
    public static Attempt starting(UUID testId, UUID learnerId, int attemptNumber,
            Duration timeLimit, Instant now) {
        Attempt attempt = new Attempt();
        attempt.id = UUID.randomUUID();
        attempt.testId = testId;
        attempt.learnerId = learnerId;
        attempt.attemptNumber = (short) attemptNumber;
        attempt.state = State.IN_PROGRESS;
        attempt.grading = Grading.NOT_GRADED;
        attempt.startedAt = now;
        attempt.expiresAt = timeLimit == null ? null : now.plus(timeLimit);
        attempt.createdAt = now;
        attempt.updatedAt = now;
        return attempt;
    }

    /** Whether the clock has run out. False for an untimed attempt, always. */
    public boolean isPastDeadline(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    /** How long is left, or null when untimed. Never negative: past the deadline is zero. */
    public Duration remaining(Instant now) {
        if (expiresAt == null) {
            return null;
        }
        Duration left = Duration.between(now, expiresAt);
        return left.isNegative() ? Duration.ZERO : left;
    }

    /**
     * Ends it.
     *
     * <p>Only ever called by {@code AttemptService} after a conditional UPDATE has already won the
     * race, so this sets the fields the winner is entitled to set. It deliberately cannot decide
     * whether it may run — a check here would be a second answer to "has this been submitted", and
     * the database's is the one that arbitrates concurrent submits.
     */
    void ended(State terminal, Instant now) {
        this.state = terminal;
        this.submittedAt = now;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTestId() {
        return testId;
    }

    public UUID getLearnerId() {
        return learnerId;
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public State getState() {
        return state;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    /**
     * Records what marking decided (T-6.7).
     *
     * <p>Public only because {@code GradingService} is in another package, and called by nothing
     * else. It records a verdict; it does not decide one -- there is no arithmetic here, so a
     * second caller would have to bring its own score and would be a second answer to "what did
     * they get", which is the shape T-6.4 exists to keep singular.
     *
     * <p>Called after the score has been composed — a regrade calls
     * it again, and the previous verdict survives in {@code grade_event} rather than here. These
     * columns are a cache of the newest audit row.
     */
    public void graded(Grading nowGrading,
            com.xenopsoftware.learn.assessment.scoring.TestScore score, Instant now) {
        this.grading = nowGrading;
        this.scoreRaw = score.raw();
        this.scoreScaled = score.scaled();
        this.scorePercent = (short) score.percent();
        // Only a settled attempt carries a verdict. A provisional one leaves this null, which is
        // what stops a gate reading "not yet" as "no".
        this.passed = nowGrading == Grading.GRADED ? score.passed() : null;
        this.gradedAt = now;
        this.updatedAt = now;
    }

    public Grading getGrading() {
        return grading;
    }

    /** Whether anybody may act on this result yet. The question a gate should ask. */
    public boolean isSettled() {
        return grading == Grading.GRADED;
    }

    public BigDecimal getScoreRaw() {
        return scoreRaw;
    }

    public BigDecimal getScoreScaled() {
        return scoreScaled;
    }

    public Integer getScorePercent() {
        return scorePercent == null ? null : (int) scorePercent;
    }

    public Boolean getPassed() {
        return passed;
    }

    public Instant getGradedAt() {
        return gradedAt;
    }
}
