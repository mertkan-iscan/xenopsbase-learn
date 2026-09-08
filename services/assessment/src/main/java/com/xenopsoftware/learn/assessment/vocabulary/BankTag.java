package com.xenopsoftware.learn.assessment.vocabulary;

import com.xenopsoftware.learn.common.tenancy.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One tag a question in this company may carry (T-6.1).
 *
 * <p><b>A controlled vocabulary, where {@code content_item.tags} is free text, and the difference
 * is who reads them.</b> A content tag is read by a person filtering a list, so a typo makes one
 * item harder to find and they try again. A question tag is read by a <i>draw</i> (T-6.5): "five
 * medium questions tagged fire-safety" resolves against whatever matches, so {@code fire-safety}
 * and {@code fire safety} are two populations. The author who mistypes one gets no error — they
 * get a shorter exam, silently, discovered after it has been sat.
 *
 * <p>This table is what turns that into a rejected write at authoring time. It is per tenant
 * because it is the customer's language, and it starts empty: an untagged bank is a working bank.
 */
@Entity
@Table(name = "bank_tag")
public class BankTag extends TenantOwned {

    @Id
    private UUID id;

    @Column(nullable = false, length = 64)
    private String tag;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected BankTag() {
        // Hibernate.
    }

    public static BankTag of(String tag) {
        if (tag == null || tag.isBlank()) {
            throw new IllegalArgumentException("A tag needs a value");
        }
        BankTag entry = new BankTag();
        entry.id = UUID.randomUUID();
        entry.tag = tag.strip();
        entry.createdAt = Instant.now();
        return entry;
    }

    public UUID getId() {
        return id;
    }

    public String getTag() {
        return tag;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
