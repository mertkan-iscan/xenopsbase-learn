package com.xenopsoftware.learn.assessment.vocabulary;

import com.xenopsoftware.learn.common.tenancy.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One difficulty level in this company's own scale (T-6.1).
 *
 * <p><b>Difficulty is ordered and tags are not, which is the whole reason it is a second
 * vocabulary rather than a reserved tag prefix.</b> A draw says "medium or harder" (T-6.5) and
 * item analysis asks whether a question was harder than the one it replaced (T-7.7). Both need an
 * order, and neither can get one from a string.
 *
 * <p>The names are the customer's — three levels or seven, in their language. Only the ordering
 * is ours, and it is carried by {@link #getRank()} rather than by the alphabet, because "Easy,
 * Hard, Medium" is what sorting the names gives you.
 */
@Entity
@Table(name = "bank_difficulty")
public class BankDifficulty extends TenantOwned {

    @Id
    private UUID id;

    @Column(nullable = false, length = 32)
    private String code;

    /** Higher is harder. Unique per tenant: two levels at one rank make "or harder" ambiguous. */
    @Column(nullable = false)
    private short rank;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected BankDifficulty() {
        // Hibernate.
    }

    public static BankDifficulty of(String code, int rank) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("A difficulty needs a code");
        }
        if (rank < 0 || rank > Short.MAX_VALUE) {
            throw new IllegalArgumentException("A difficulty rank must fit a smallint, got " + rank);
        }
        BankDifficulty level = new BankDifficulty();
        level.id = UUID.randomUUID();
        level.code = code.strip();
        level.rank = (short) rank;
        level.createdAt = Instant.now();
        return level;
    }

    public UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public short getRank() {
        return rank;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
