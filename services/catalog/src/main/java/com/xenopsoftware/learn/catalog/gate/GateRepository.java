package com.xenopsoftware.learn.catalog.gate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GateRepository extends JpaRepository<Gate, UUID> {

    /** Every gate in a course, in ONE query -- reachability evaluates the whole course at once. */
    List<Gate> findByCourseId(UUID courseId);

    Optional<Gate> findByTargetId(UUID targetId);
}
