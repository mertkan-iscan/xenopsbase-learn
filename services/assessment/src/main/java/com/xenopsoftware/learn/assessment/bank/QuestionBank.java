package com.xenopsoftware.learn.assessment.bank;

import com.xenopsoftware.learn.common.tenancy.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A named collection of questions: the unit of authoring permission, and the population a
 * section draws from (T-6.1).
 *
 * <p>Both of those matter, and the second is the one that cannot be added later. A permission
 * boundary can be tightened whenever somebody asks; a draw whose population was never a
 * first-class thing has no stable answer to "what was this test drawn from", which T-6.5 has to
 * record for every attempt that was ever sat.
 *
 * <p><b>A platform bank is an ordinary row.</b> It lives in the platform's reserved tenant with
 * {@link #isShared()} set — the answer T-5.1 already gave for shared content — so there is no
 * nullable tenant anywhere and no second entity. What a customer may do with one is decided by
 * {@link BankService}, not by this class: read it, copy it, never edit it.
 */
@Entity
@Table(name = "question_bank")
public class QuestionBank extends TenantOwned {

    @Id
    private UUID id;

    @Column(nullable = false, length = 256)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Column(nullable = false)
    private boolean shared;

    /**
     * Where a copy came from, as a record and not a link.
     *
     * <p>No foreign key, deliberately: the source may later be edited, archived or withdrawn, and
     * none of that may reach the copy. It answers "where did this come from" for a person, and
     * nothing joins on it.
     */
    @Column(name = "copied_from_bank_id")
    private UUID copiedFromBankId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected QuestionBank() {
        // Hibernate.
    }

    /**
     * A new bank owned by the calling tenant.
     *
     * <p>No parameter chooses {@code shared}. Offering a bank across tenants is a platform act on
     * a platform-tenant row, and an API through which a customer could mark their own bank as
     * shared would be an API through which one customer's questions reach another's authors.
     */
    public static QuestionBank create(String name, String description) {
        QuestionBank bank = new QuestionBank();
        bank.id = UUID.randomUUID();
        bank.shared = false;
        bank.createdAt = Instant.now();
        bank.rename(name, description);
        return bank;
    }

    /**
     * Record where this bank was copied from.
     *
     * <p>Separate from {@link #create} rather than a parameter on it, because provenance is the
     * only thing a copy inherits and making it a constructor argument invites the next one — an
     * owner, a version, a link back. It is set once, at creation, and there is no method that
     * clears it: "copied from" is a fact about how the row came to exist, not a state.
     */
    void recordCopiedFrom(UUID sourceBankId) {
        this.copiedFromBankId = sourceBankId;
    }

    public void rename(String name, String description) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A bank needs a name");
        }
        this.name = name.strip();
        this.description = description == null || description.isBlank() ? null : description.strip();
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public boolean isShared() {
        return shared;
    }

    public UUID getCopiedFromBankId() {
        return copiedFromBankId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
