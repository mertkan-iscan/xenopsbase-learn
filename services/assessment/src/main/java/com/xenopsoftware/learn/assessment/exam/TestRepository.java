package com.xenopsoftware.learn.assessment.exam;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TestRepository extends JpaRepository<TestDefinition, UUID> {

    List<TestDefinition> findAllByOrderByUpdatedAtDesc();
}
