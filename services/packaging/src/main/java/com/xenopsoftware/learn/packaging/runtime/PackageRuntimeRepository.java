package com.xenopsoftware.learn.packaging.runtime;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * A learner's runtime for one package in one place in a course.
 *
 * <p>Tenant-filtered by the persistence layer, like everything else here — so "this learner" can
 * only ever be somebody in the caller's own company, and the query below cannot be made to reach
 * across by passing an id from elsewhere.
 */
public interface PackageRuntimeRepository extends JpaRepository<PackageRuntime, UUID> {

    /**
     * The row for this learner, package and node.
     *
     * <p><b>Written out rather than derived, because of the null.</b> A derived
     * {@code findByLearnerIdAndPackageIdAndNodeId} generates {@code node_id = ?}, and SQL's
     * {@code = NULL} is never true — so a preview launch (no node) would find nothing every time,
     * create a second row every time, and never resume. {@code IS NULL} is the only way to ask the
     * question the unique constraint answers, and the constraint is
     * {@code UNIQUE NULLS NOT DISTINCT} for exactly the same reason.
     */
    @Query("""
        SELECT r FROM PackageRuntime r
         WHERE r.learnerId = :learnerId
           AND r.packageId = :packageId
           AND (:nodeId IS NULL AND r.nodeId IS NULL OR r.nodeId = :nodeId)
        """)
    Optional<PackageRuntime> find(@Param("learnerId") UUID learnerId,
            @Param("packageId") UUID packageId, @Param("nodeId") UUID nodeId);
}
