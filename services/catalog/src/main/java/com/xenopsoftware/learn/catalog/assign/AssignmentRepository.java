package com.xenopsoftware.learn.catalog.assign;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AssignmentRepository extends JpaRepository<Assignment, UUID> {

    /**
     * Everything live that reaches this learner, in ONE query (T-5.5's second criterion).
     *
     * <p>The three target kinds are three branches of one {@code WHERE}, not three queries and
     * certainly not a row per member. A group of five thousand contributes one row to this table
     * and one id to {@code groupIds}; the cost of reading is the size of what was ASSIGNED, never
     * the size of the audience.
     *
     * <p>{@code groupIds} always contains at least one value — the caller substitutes a sentinel
     * for a learner in no groups, because an empty collection parameter is rejected by JPQL and
     * "in no groups" must not become "in every group".
     */
    @Query("""
        SELECT a FROM Assignment a
         WHERE a.revokedAt IS NULL
           AND (a.targetType = com.xenopsoftware.learn.catalog.assign.TargetKind.TENANT
             OR (a.targetType = com.xenopsoftware.learn.catalog.assign.TargetKind.USER
                 AND a.targetId = :learnerId)
             OR (a.targetType = com.xenopsoftware.learn.catalog.assign.TargetKind.GROUP
                 AND a.targetId IN :groupIds))
         ORDER BY a.assignedAt ASC
        """)
    List<Assignment> reaching(@Param("learnerId") UUID learnerId,
                              @Param("groupIds") Collection<UUID> groupIds);

    /** Live assignments pointing at one thing -- the compliance direction (T-7.6). */
    List<Assignment> findByReferenceTypeAndReferenceIdAndRevokedAtIsNull(
        ReferenceKind referenceType, UUID referenceId);

    List<Assignment> findByRevokedAtIsNullOrderByAssignedAtDesc();

    /** Live assignments that carry a deadline -- what a reminder pass considers (T-5.6). */
    List<Assignment> findByRevokedAtIsNullAndDueKindNot(
        com.xenopsoftware.learn.catalog.due.DueKind dueKind);
}
