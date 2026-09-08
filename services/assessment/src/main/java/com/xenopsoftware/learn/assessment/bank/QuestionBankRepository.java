package com.xenopsoftware.learn.assessment.bank;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * This tenant's banks, and only this tenant's.
 *
 * <p>No method here writes a {@code WHERE tenant_id} clause and none can forget one: Hibernate's
 * {@code @TenantId} filters every query these derive (T-1.1). That is also why there is no
 * "find every shared bank" method — a shared bank belongs to the platform's tenant, so it is
 * invisible from here by design, and reading one is the deliberate cross-tenant act
 * {@link PlatformBanks} performs.
 */
public interface QuestionBankRepository extends JpaRepository<QuestionBank, UUID> {

    List<QuestionBank> findAllByOrderByNameAsc();

    Optional<QuestionBank> findByNameIgnoreCase(String name);
}
