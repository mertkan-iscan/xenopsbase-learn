package com.xenopsoftware.learn.assessment.question;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * This tenant's questions, and only this tenant's.
 *
 * <p>No method writes a {@code WHERE tenant_id} clause and none can forget one — Hibernate's
 * {@code @TenantId} filters every derived query (T-1.1). A question from another company is
 * therefore absent rather than refused, which is the same 404 either way (ADR-0102).
 */
public interface QuestionRepository extends JpaRepository<Question, UUID> {

    /** An authoring list: this bank's questions, retired ones excluded, by the name shown. */
    List<Question> findByBankIdAndRetiredAtIsNullOrderByInternalNameAsc(UUID bankId);

    Optional<Question> findByIdAndRetiredAtIsNull(UUID id);
}
