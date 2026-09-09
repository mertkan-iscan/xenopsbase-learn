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
 * A part of a test, and the instruction that fills it (T-6.5).
 *
 * <p>Two kinds in one table, because everything after selection is identical — weight, shuffling,
 * scoring, and the form the section produces. Two tables would duplicate all of that to express one
 * branch:
 *
 * <ul>
 *   <li>{@link Selection#FIXED} — the author named the questions, in order.
 *   <li>{@link Selection#POOL} — the author described a population, and {@code drawCount} of them
 *       are chosen for each learner.
 * </ul>
 *
 * <p><b>Difficulty is a rank range, not a set of levels.</b> "Medium or harder" is what an author
 * means, and a range keeps meaning it when the company inserts a new level in the middle of its
 * scale. A stored set of level ids would quietly stop including the new one, and nobody would be
 * told — which is the shape of every bug this issue is about.
 */
@Entity
@Table(name = "test_section")
public class TestSection extends TenantOwned {

    /** How the questions in this section are chosen. */
    public enum Selection { FIXED, POOL }

    @Id
    private UUID id;

    @Column(name = "test_id", nullable = false)
    private UUID testId;

    @Column(nullable = false, length = 512)
    private String title;

    @Column(nullable = false)
    private BigDecimal ordinal;

    @Column(nullable = false)
    private short weight;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Selection selection;

    @Column(name = "draw_count")
    private Short drawCount;

    @Column(name = "bank_id")
    private UUID bankId;

    @Column(name = "min_difficulty_rank")
    private Short minDifficultyRank;

    @Column(name = "max_difficulty_rank")
    private Short maxDifficultyRank;

    @Column
    private BigDecimal points;

    @Enumerated(EnumType.STRING)
    @Column(length = 24)
    private ScoringMode mode;

    @Column(name = "shuffle_questions", nullable = false)
    private boolean shuffleQuestions;

    @Column(name = "shuffle_options", nullable = false)
    private boolean shuffleOptions;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TestSection() {
        // Hibernate.
    }

    public static TestSection fixed(UUID testId, String title, BigDecimal ordinal) {
        TestSection section = blank(testId, title, ordinal);
        section.selection = Selection.FIXED;
        return section;
    }

    public static TestSection pool(UUID testId, String title, BigDecimal ordinal, int drawCount) {
        TestSection section = blank(testId, title, ordinal);
        section.selection = Selection.POOL;
        section.drawsFrom(null, null, null, drawCount);
        return section;
    }

    private static TestSection blank(UUID testId, String title, BigDecimal ordinal) {
        TestSection section = new TestSection();
        section.id = UUID.randomUUID();
        section.testId = testId;
        section.title = title;
        section.ordinal = ordinal;
        section.weight = 1;
        section.shuffleQuestions = false;
        section.shuffleOptions = false;
        section.createdAt = Instant.now();
        section.updatedAt = section.createdAt;
        return section;
    }

    /** Narrows the population a pool section draws from. Every filter is optional. */
    public void drawsFrom(UUID newBankId, Integer minRank, Integer maxRank, int newDrawCount) {
        if (selection != Selection.POOL) {
            throw new IllegalArgumentException(
                "A fixed section holds the questions the author named; it does not draw.");
        }
        if (newDrawCount < 1) {
            throw new IllegalArgumentException(
                "A section that draws nothing serves an empty form, which is the one outcome "
                + "nobody is told about. Draw at least one, or make it a fixed section.");
        }
        if (minRank != null && maxRank != null && minRank > maxRank) {
            throw new IllegalArgumentException(
                "The easiest level allowed (" + minRank + ") is harder than the hardest ("
                + maxRank + "), so this section can never match anything.");
        }
        this.bankId = newBankId;
        this.minDifficultyRank = minRank == null ? null : minRank.shortValue();
        this.maxDifficultyRank = maxRank == null ? null : maxRank.shortValue();
        this.drawCount = (short) newDrawCount;
        touch();
    }

    public void moveTo(BigDecimal newOrdinal) {
        this.ordinal = newOrdinal;
        touch();
    }

    public void rename(String newTitle) {
        this.title = newTitle;
        touch();
    }

    public void countsFor(int newWeight) {
        if (newWeight < 0) {
            throw new IllegalArgumentException("A section's weight is not negative: " + newWeight);
        }
        this.weight = (short) newWeight;
        touch();
    }

    /**
     * Overrides the test's scoring for this section, or clears the override with two nulls.
     *
     * <p>Null is not the same as a value that happens to match the test today: an author who
     * raises the test's default marks expects a section that said nothing to follow, and a section
     * that had silently copied the old number would not.
     */
    public void scoredAs(BigDecimal newPoints, ScoringMode newMode) {
        if (newPoints != null && newPoints.signum() <= 0) {
            throw new IllegalArgumentException(
                "A question is worth more than nothing, or it is not in the test: " + newPoints);
        }
        this.points = newPoints;
        this.mode = newMode;
        touch();
    }

    public void shuffles(boolean questions, boolean options) {
        this.shuffleQuestions = questions;
        this.shuffleOptions = options;
        touch();
    }

    /**
     * What a question in this section is worth, given what the test says.
     *
     * <p>The one place the override is resolved, so "which number applies" is not a question two
     * callers can answer differently.
     */
    public QuestionScoring scoringGiven(QuestionScoring testDefault) {
        return new QuestionScoring(points == null ? testDefault.points() : points,
            mode == null ? testDefault.mode() : mode, testDefault.penalty());
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getTestId() {
        return testId;
    }

    public String getTitle() {
        return title;
    }

    public BigDecimal getOrdinal() {
        return ordinal;
    }

    public int getWeight() {
        return weight;
    }

    public Selection getSelection() {
        return selection;
    }

    public Integer getDrawCount() {
        return drawCount == null ? null : (int) drawCount;
    }

    public UUID getBankId() {
        return bankId;
    }

    public Integer getMinDifficultyRank() {
        return minDifficultyRank == null ? null : (int) minDifficultyRank;
    }

    public Integer getMaxDifficultyRank() {
        return maxDifficultyRank == null ? null : (int) maxDifficultyRank;
    }

    public BigDecimal getPoints() {
        return points;
    }

    public ScoringMode getMode() {
        return mode;
    }

    public boolean isShuffleQuestions() {
        return shuffleQuestions;
    }

    public boolean isShuffleOptions() {
        return shuffleOptions;
    }
}
