package com.xenopsoftware.learn.assessment.question;

import com.xenopsoftware.learn.common.tenancy.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * What was asked, and how it was marked (T-6.2, ADR-0106).
 *
 * <p>This is the row an attempt points at, and after {@link #getFirstServedAt()} is set it never
 * changes again — not by this class, not by the repository, not by a support fix applied in SQL.
 * The trigger in {@code V2__question.sql} is what makes that last clause true; this class is
 * merely shaped so nothing here tries.
 *
 * <p><b>There is no setter for the body once it has been served, and no method that could add
 * one.</b> {@link #editDraft} exists for the case ADR-0106 carves out — a version nobody has been
 * served is a draft, and correcting a typo in it should leave no trace, because there is nothing
 * to trace. {@link QuestionService} is what knows which of the two a version is; the trigger is
 * what catches everything else that ever writes here.
 *
 * <h2>The body is one document</h2>
 *
 * <p>Stem, options, answer key, weights — one {@code jsonb} column, opaque until T-6.3 gives the
 * question types their shapes. The alternative, a column per field, would have meant the
 * immutability rule was a list of column names that T-6.3 and T-6.4 each have to remember to
 * extend, and forgetting is silent in exactly the way ADR-0106 exists to prevent.
 */
@Entity
@Table(name = "question_version")
public class QuestionVersion extends TenantOwned {

    @Id
    private UUID id;

    @Column(name = "question_id", nullable = false)
    private UUID questionId;

    @Column(nullable = false)
    private int version;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String body;

    @Column(name = "first_served_at")
    private Instant firstServedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected QuestionVersion() {
        // Hibernate.
    }

    static QuestionVersion create(UUID questionId, int version, String body) {
        QuestionVersion asked = new QuestionVersion();
        asked.id = UUID.randomUUID();
        asked.questionId = questionId;
        asked.version = version;
        asked.body = body;
        asked.createdAt = Instant.now();
        return asked;
    }

    /**
     * Correct a version nobody has been served.
     *
     * <p>Package-private and unguarded, deliberately. A check here would be a second statement of
     * a rule the database already enforces, and the two would eventually disagree — the pattern
     * {@code BankService} names for duplicate names. What stops a served version reaching this
     * method is that {@link QuestionService} branches before calling it, and what stops everything
     * else is the trigger.
     */
    void editDraft(String body) {
        this.body = body;
    }

    /** True while editing this version updates it in place rather than producing a new one. */
    public boolean isDraft() {
        return firstServedAt == null;
    }

    public UUID getId() {
        return id;
    }

    public UUID getQuestionId() {
        return questionId;
    }

    public int getVersion() {
        return version;
    }

    public String getBody() {
        return body;
    }

    public Instant getFirstServedAt() {
        return firstServedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
