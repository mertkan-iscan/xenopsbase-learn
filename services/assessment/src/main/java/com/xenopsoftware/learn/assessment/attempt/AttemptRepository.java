package com.xenopsoftware.learn.assessment.attempt;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AttemptRepository extends JpaRepository<Attempt, UUID> {

    Optional<Attempt> findByLearnerIdAndTestIdAndState(UUID learnerId, UUID testId,
        Attempt.State state);

    List<Attempt> findByLearnerIdAndTestIdOrderByAttemptNumberAsc(UUID learnerId, UUID testId);

    int countByLearnerIdAndTestId(UUID learnerId, UUID testId);

    /**
     * ENDS AN ATTEMPT, ONCE, WHOEVER ASKS FIRST (T-6.6).
     *
     * <p>The whole of "submission is idempotent; a double-click produces one graded attempt", and
     * it is one statement rather than a read followed by a write. Two submits arriving together
     * both find IN_PROGRESS if they look first; only one of them can change it here, and the
     * caller learns which it was from the row count.
     *
     * <p>It is also what lets the reaper and a learner race harmlessly: whichever reaches the row
     * first ends the attempt, and the other is told it changed nothing.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE Attempt a
           SET a.state = :terminal, a.submittedAt = :now, a.updatedAt = :now
         WHERE a.id = :id AND a.state = :from
        """)
    int end(@Param("id") UUID id, @Param("from") Attempt.State from,
        @Param("terminal") Attempt.State terminal, @Param("now") Instant now);

}
