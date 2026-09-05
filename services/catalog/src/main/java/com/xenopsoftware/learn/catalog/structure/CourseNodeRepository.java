package com.xenopsoftware.learn.catalog.structure;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CourseNodeRepository extends JpaRepository<CourseNode, UUID> {

    List<CourseNode> findByModuleIdOrderByOrdinalAscIdAsc(UUID moduleId);

    /**
     * Every node of a whole course, in ONE query.
     *
     * <p>The alternative -- a query per module -- is what makes a course screen cost forty round
     * trips at forty modules, and it is invisible in a test with two. {@code DeepCourseTest}
     * builds a realistic tree precisely so that this stays visible.
     */
    @Query("""
        SELECT n FROM CourseNode n
         WHERE n.moduleId IN (SELECT m.id FROM CourseModule m WHERE m.courseId = :courseId)
         ORDER BY n.ordinal ASC, n.id ASC
        """)
    List<CourseNode> findWholeCourse(@Param("courseId") UUID courseId);

    /** Which nodes point at an item -- asked before archiving one, and by the delete refusal. */
    List<CourseNode> findByContentItemId(UUID contentItemId);

    long countByContentItemId(UUID contentItemId);
}
