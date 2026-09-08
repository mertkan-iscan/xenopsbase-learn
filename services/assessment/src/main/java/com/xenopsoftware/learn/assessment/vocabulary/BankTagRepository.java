package com.xenopsoftware.learn.assessment.vocabulary;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** This tenant's tag vocabulary, tenant-filtered by {@code @TenantId} (T-1.1). */
public interface BankTagRepository extends JpaRepository<BankTag, UUID> {

    List<BankTag> findAllByOrderByTagAsc();

    Optional<BankTag> findByTagIgnoreCase(String tag);
}
