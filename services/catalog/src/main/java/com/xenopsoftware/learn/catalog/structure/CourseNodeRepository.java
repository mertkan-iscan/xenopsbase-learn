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

    /**
     * Every node of SEVERAL courses, still in one query (T-5.8).
     *
     * <p>The learner home screen builds a tree per assigned course, and the shape that ruins it is
     * one query per course rather than one per module — the same mistake one level up. Ordered by
     * module so the caller can group without sorting.
     */
    @Query("""
        SELECT n FROM CourseNode n
         WHERE n.moduleId IN (SELECT m.id FROM CourseModule m WHERE m.courseId IN :courseIds)
         ORDER BY n.moduleId ASC, n.ordinal ASC, n.id ASC
        """)
    List<CourseNode> findWholeCourses(@Param("courseIds") java.util.Collection<UUID> courseIds);

    /** Which nodes point at an item -- asked before archiving one, and by the delete refusal. */
    List<CourseNode> findByContentItemId(UUID contentItemId);

    long countByContentItemId(UUID contentItemId);
}
