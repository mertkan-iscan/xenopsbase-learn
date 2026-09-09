package com.xenopsoftware.learn.assessment.exam;

import com.xenopsoftware.learn.assessment.review.ReviewTiming;
import com.xenopsoftware.learn.assessment.review.ReviewVisibility;
import com.xenopsoftware.learn.assessment.scoring.QuestionScoring;
import com.xenopsoftware.learn.assessment.scoring.ScoringMode;
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
 * A test, and what its result means (T-6.4).
 *
 * <p><b>Not called {@code Test}, and not in a package called {@code test}.</b> Both would be legal
 * and both would be a nuisance for the life of the codebase: every test class that touched one
 * would have to disambiguate it from {@code org.junit.jupiter.api.Test}, by fully qualifying one or
 * the other, in a file where the annotation appears on every method. The table is {@code test},
 * because that is the domain word and T-6.5's {@code test_section} and {@code test_form} are named
 * from it.
 *
 * <p><b>What is here is the scoring policy and nothing else.</b> What is asked — sections, pools,
 * shuffling — is T-6.5's (#64), and the attempt that produces a score is T-6.6's (#65). This row
 * exists now because a pass mark has to live somewhere before there is anything to compare with it,
 * and because the pass mark's <em>type</em> is a decision this task owes an answer for.
 */
@Entity
@Table(name = "test")
public class TestDefinition extends TenantOwned {

    @Id
    private UUID id;

    @Column(nullable = false, length = 512)
    private String title;

    @Column
    private String description;

    /**
     * The pass mark, as a whole percent.
     *
     * <p>A whole percent rather than a fraction, so that the number a learner is shown and the
     * number the verdict is decided by are the same number — see {@code TestScore}. It is the
     * column this table exists for.
     */
    @Column(name = "pass_mark_percent", nullable = false)
    private short passMarkPercent;

    @Column(name = "negative_marking", nullable = false)
    private boolean negativeMarking;

    @Column(name = "default_points", nullable = false)
    private BigDecimal defaultPoints;

    @Enumerated(EnumType.STRING)
    @Column(name = "default_mode", nullable = false, length = 24)
    private ScoringMode defaultMode;

    @Column(name = "penalty_points", nullable = false)
    private BigDecimal penaltyPoints;

    /**
     * How many times one learner may sit it, or null for unlimited (T-6.6).
     *
     * <p>Null rather than a large number, because "as often as you like" is what a practice quiz
     * means and a limit of 999 is a limit somebody eventually hits and cannot explain.
     */
    @Column(name = "attempts_allowed")
    private Short attemptsAllowed;

    /**
     * The time limit in seconds, or null for untimed.
     *
     * <p>Null means an attempt gets <b>no</b> {@code expires_at} at all rather than a very distant
     * one: "no deadline" and "a deadline in the year 3000" are different facts, and only the first
     * one is true.
     */
    @Column(name = "time_limit_seconds")
    private Integer timeLimitSeconds;

    /**
     * How much of their own paper a learner may see back (T-6.9).
     *
     * <p>{@code SCORE_ONLY} by default. Opening it up is a deliberate act, and this is the axis
     * that carries the risk: showing full answers on a certification exam drawn from a bank hands
     * the bank to anybody willing to sit it once.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "review_visibility", nullable = false, length = 24)
    private ReviewVisibility reviewVisibility;

    /**
     * When what the visibility permits actually opens.
     *
     * <p>{@code IMMEDIATELY} by default, and that is not a relaxation: under {@code SCORE_ONLY}
     * there is nothing to gate, and a learner seeing their own score the moment they finish
     * discloses nothing.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "review_timing", nullable = false, length = 24)
    private ReviewTiming reviewTiming;

    @Column(name = "review_after")
    private Instant reviewAfter;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TestDefinition() {
        // Hibernate.
    }

    public static TestDefinition called(String title, String description, int passMarkPercent) {
        TestDefinition test = new TestDefinition();
        test.id = UUID.randomUUID();
        test.title = title;
        test.description = description;
        test.passMarkPercent = (short) passMarkPercent;
        test.negativeMarking = false;
        test.defaultPoints = BigDecimal.ONE;
        test.defaultMode = ScoringMode.ALL_OR_NOTHING;
        test.penaltyPoints = BigDecimal.ZERO;
        test.reviewVisibility = ReviewVisibility.SCORE_ONLY;
        test.reviewTiming = ReviewTiming.IMMEDIATELY;
        test.createdAt = Instant.now();
        test.updatedAt = test.createdAt;
        return test;
    }

    public void rename(String newTitle, String newDescription) {
        this.title = newTitle;
        this.description = newDescription;
        touch();
    }

    /**
     * Changes what a result means.
     *
     * <p>One method rather than five setters, because these five numbers are one decision: turning
     * negative marking on without setting a penalty does nothing, and setting a penalty without
     * turning it on does nothing either. A caller that has to supply all of them cannot leave the
     * policy half-changed and wonder why the scores did not move.
     *
     * <p><b>Editing this does not rescore anything.</b> Attempts already sat keep the marks they
     * were given; rescoring is a deliberate, audited act (T-6.4's last criterion, waiting on the
     * attempt itself in T-6.6). A policy edit that silently rewrote history would change a
     * compliance record nobody was told about.
     */
    public void scoredAs(int newPassMarkPercent, boolean nowNegativeMarking, BigDecimal points,
            ScoringMode mode, BigDecimal penalty) {
        // Validated by building the value type the arithmetic actually uses, rather than by a
        // second copy of its rules here -- there is one definition of a legal scoring and it is
        // QuestionScoring's.
        QuestionScoring checked = new QuestionScoring(points, mode, penalty);
        if (newPassMarkPercent < 0 || newPassMarkPercent > 100) {
            throw new IllegalArgumentException(
                "A pass mark is a whole percent between 0 and 100, not " + newPassMarkPercent);
        }
        this.passMarkPercent = (short) newPassMarkPercent;
        this.negativeMarking = nowNegativeMarking;
        this.defaultPoints = checked.points();
        this.defaultMode = checked.mode();
        this.penaltyPoints = checked.penalty();
        touch();
    }

    /**
     * How it may be sat: how many times, and for how long (T-6.6).
     *
     * <p>Separate from {@link #scoredAs}, because they are different decisions by different people
     * -- what a result means against how the exam is invigilated -- and because changing one must
     * not require restating the other.
     *
     * <p><b>Changing this does not touch an attempt already under way.</b> {@code expires_at} is
     * computed once, at start, from the limit in force then; shortening a test's limit cannot take
     * time off somebody who is mid-exam, and lengthening it cannot give them more.
     */
    public void satAs(Integer newAttemptsAllowed, Duration newTimeLimit) {
        if (newAttemptsAllowed != null && newAttemptsAllowed < 1) {
            throw new IllegalArgumentException(
                "A test nobody may sit is not a limit, it is a withdrawal: " + newAttemptsAllowed);
        }
        if (newTimeLimit != null && (newTimeLimit.isZero() || newTimeLimit.isNegative())) {
            throw new IllegalArgumentException(
                "A time limit is a positive duration, or none at all: " + newTimeLimit);
        }
        this.attemptsAllowed = newAttemptsAllowed == null ? null : newAttemptsAllowed.shortValue();
        this.timeLimitSeconds = newTimeLimit == null ? null : (int) newTimeLimit.toSeconds();
        touch();
    }

    public Integer getAttemptsAllowed() {
        return attemptsAllowed == null ? null : (int) attemptsAllowed;
    }

    /** The limit as a duration, or null when this test is untimed. */
    public Duration getTimeLimit() {
        return timeLimitSeconds == null ? null : Duration.ofSeconds(timeLimitSeconds);
    }

    /**
     * What a learner may see back, and when (T-6.9).
     *
     * <p>One method for both, because they are one decision: a timing without a visibility gates
     * nothing, and a visibility without a timing is a visibility that is always open.
     *
     * @throws IllegalArgumentException when the pairing cannot mean anything — see the two refusals
     */
    public void reviewedAs(ReviewVisibility visibility, ReviewTiming timing, Instant after,
            Integer attemptsAllowedNow) {
        if (visibility == null || timing == null) {
            throw new IllegalArgumentException("A review policy is a visibility and a timing.");
        }
        if (timing == ReviewTiming.AFTER_DATE && after == null) {
            throw new IllegalArgumentException(
                "A review that opens after a date needs the date. Without one it opens at "
                + "whichever moment a null comparison happens to fall.");
        }
        if (timing != ReviewTiming.AFTER_DATE && after != null) {
            throw new IllegalArgumentException(
                "Only a review timed to a date has a date. Leaving one on another timing is a "
                + "value nothing reads, which is the kind that is later believed.");
        }
        if (timing == ReviewTiming.AFTER_ALL_ATTEMPTS && attemptsAllowedNow == null) {
            // The trap this refusal exists for: on a test anybody may sit any number of times,
            // "after all attempts" means never, and the learner who can never see their paper is
            // the one who finds out.
            throw new IllegalArgumentException(
                "This test has no attempt limit, so \"after all attempts\" would mean never. Set "
                + "a limit, or choose another timing.");
        }
        this.reviewVisibility = visibility;
        this.reviewTiming = timing;
        this.reviewAfter = after;
        touch();
    }

    public ReviewVisibility getReviewVisibility() {
        return reviewVisibility;
    }

    public ReviewTiming getReviewTiming() {
        return reviewTiming;
    }

    public Instant getReviewAfter() {
        return reviewAfter;
    }

    /** What a question in this test is worth unless a section says otherwise (T-6.5). */
    public QuestionScoring defaultScoring() {
        return new QuestionScoring(defaultPoints, defaultMode, penaltyPoints);
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public int getPassMarkPercent() {
        return passMarkPercent;
    }

    public boolean isNegativeMarking() {
        return negativeMarking;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
