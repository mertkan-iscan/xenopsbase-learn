package com.xenopsoftware.learn.assessment.question;

import com.xenopsoftware.learn.common.tenancy.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A question as an identity: which bank it is in, what the author calls it, and which version is
 * current (T-6.2, ADR-0106).
 *
 * <p><b>Nothing on this class is what a learner read.</b> That is the whole split — everything
 * here is editable forever, because none of it is a claim about what was asked. The stem, the
 * options, the answer key and the weights live on {@link QuestionVersion}, which is frozen the
 * moment it is served.
 *
 * <p>So the two things an author does most often cost nothing: renaming a question and moving it
 * between banks are ordinary updates to this row, and every attempt ever recorded still renders
 * exactly as it did. ADR-0106 says moving a question between banks is not an edit to what was
 * asked, and this is where that becomes true rather than remembered.
 *
 * <p><b>{@code retiredAt} is what "delete" means</b> for any question that has ever been served: it
 * leaves authoring and every future draw, and it takes nothing with it. {@link QuestionService}
 * decides between retiring and deleting outright, because which one is possible is a fact about
 * the data rather than a choice the caller makes.
 */
@Entity
@Table(name = "question")
public class Question extends TenantOwned {

    @Id
    private UUID id;

    @Column(name = "bank_id", nullable = false)
    private UUID bankId;

    /**
     * The version an author edits and a draw would pick up.
     *
     * <p>An id rather than a mapped association, so that reading a question never drags a version
     * along with it. The two travel together through authoring and delivery anyway (ADR-0106 says
     * so plainly), and making that explicit at every call site is better than a lazy association
     * that loads a document nobody asked for.
     *
     * <p>Null only between the insert of this row and the insert of its first version.
     */
    @Column(name = "current_version_id")
    private UUID currentVersionId;

    @Column(name = "internal_name", nullable = false, length = 256)
    private String internalName;

    @Column(name = "retired_at")
    private Instant retiredAt;

    /**
     * How hard it is, from the company's own ordered vocabulary (T-6.1), or null.
     *
     * <p>On the question rather than on a version, which is ADR-0106's rule applied: an author
     * deciding a question is harder than they first thought has not changed what anybody was
     * asked. A section draws on it by rank range, so a company adding a level in the middle of its
     * scale does not silently narrow every existing draw (T-6.5).
     */
    @Column(name = "difficulty_id")
    private UUID difficultyId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Question() {
        // Hibernate.
    }

    static Question create(UUID bankId, String internalName) {
        Question question = new Question();
        question.id = UUID.randomUUID();
        question.bankId = bankId;
        question.createdAt = Instant.now();
        question.rename(internalName);
        return question;
    }

    void rename(String internalName) {
        if (internalName == null || internalName.isBlank()) {
            throw new IllegalArgumentException("A question needs a name for its author to find it by");
        }
        this.internalName = internalName.strip();
        this.updatedAt = Instant.now();
    }

    /**
     * Move to another bank.
     *
     * <p>An ordinary update to this row, and ADR-0106 says why it may be one: bank membership is
     * not what anybody was asked, so a move produces no version and disturbs no attempt. It is the
     * clearest thing the two-table split buys — in a single-table model this would either freeze
     * along with the content or quietly rewrite history.
     */
    void moveTo(UUID bankId) {
        this.bankId = bankId;
        this.updatedAt = Instant.now();
    }

    /** Point at a newly created version, whether it is the first or the fifth. */
    void currentVersionIs(QuestionVersion version) {
        this.currentVersionId = version.getId();
        this.updatedAt = Instant.now();
    }

    /**
     * Let go of the current version, so a question that was never served can be deleted.
     *
     * <p>Only reachable from {@link QuestionService#delete}: the foreign key from here to
     * {@code question_version} has to be released before the versions can go, and a question that
     * has been served never travels this path at all.
     */
    void clearCurrentVersion() {
        this.currentVersionId = null;
        this.updatedAt = Instant.now();
    }

    /** Null clears it, which is what an author does when they stop grading their own questions. */
    void difficultyIs(UUID newDifficultyId) {
        this.difficultyId = newDifficultyId;
        this.updatedAt = Instant.now();
    }

    void retire() {
        if (retiredAt == null) {
            this.retiredAt = Instant.now();
            this.updatedAt = this.retiredAt;
        }
    }

    public boolean isRetired() {
        return retiredAt != null;
    }

    public UUID getDifficultyId() {
        return difficultyId;
    }

    public UUID getId() {
        return id;
    }

    public UUID getBankId() {
        return bankId;
    }

    public UUID getCurrentVersionId() {
        return currentVersionId;
    }

    public String getInternalName() {
        return internalName;
    }

    public Instant getRetiredAt() {
        return retiredAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
