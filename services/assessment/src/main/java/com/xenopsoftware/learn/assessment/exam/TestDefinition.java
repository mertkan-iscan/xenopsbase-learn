package com.xenopsoftware.learn.assessment.exam;

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
