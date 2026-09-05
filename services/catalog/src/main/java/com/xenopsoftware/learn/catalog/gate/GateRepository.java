package com.xenopsoftware.learn.catalog.gate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GateRepository extends JpaRepository<Gate, UUID> {

    /** Every gate in a course, in ONE query -- reachability evaluates the whole course at once. */
    List<Gate> findByCourseId(UUID courseId);

    /** Every gate on a set of courses, for the screen that evaluates all of them at once (T-5.8). */
    List<Gate> findByCourseIdIn(java.util.Collection<UUID> courseIds);

    Optional<Gate> findByTargetId(UUID targetId);
}
