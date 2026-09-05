package com.xenopsoftware.learn.catalog.structure;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CourseModuleRepository extends JpaRepository<CourseModule, UUID> {

    /**
     * The modules of a course, in order.
     *
     * <p>Ordered by {@code (ordinal, id)}, and the id is not decoration. Two authors inserting
     * into the same gap at the same instant compute the same midpoint and both commit -- there is
     * deliberately no unique constraint to make one of them fail (T-5.2). Without the id, those
     * two rows would come back in whatever order the planner chose that day, so a list would
     * reshuffle between reads with nothing having changed.
     */
    List<CourseModule> findByCourseIdOrderByOrdinalAscIdAsc(UUID courseId);

    long countByCourseId(UUID courseId);
}
