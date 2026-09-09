package com.xenopsoftware.learn.assessment.exam;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TestSectionRepository extends JpaRepository<TestSection, UUID> {

    /** A test's sections, in the order a learner meets them. */
    List<TestSection> findByTestIdOrderByOrdinalAscIdAsc(UUID testId);
}
