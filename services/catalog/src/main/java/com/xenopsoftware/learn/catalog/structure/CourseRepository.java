package com.xenopsoftware.learn.catalog.structure;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Courses, tenant-filtered by the discriminator (T-1.1) rather than by a clause written here.
 */
public interface CourseRepository extends JpaRepository<Course, UUID> {

    List<Course> findAllByOrderByUpdatedAtDesc();
}
