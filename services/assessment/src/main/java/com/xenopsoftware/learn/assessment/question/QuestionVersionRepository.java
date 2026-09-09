package com.xenopsoftware.learn.assessment.question;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The history of what was asked, per question. Tenant-filtered like every other repository here. */
public interface QuestionVersionRepository extends JpaRepository<QuestionVersion, UUID> {

    List<QuestionVersion> findByQuestionIdOrderByVersionDesc(UUID questionId);

    Optional<QuestionVersion> findByQuestionIdAndId(UUID questionId, UUID id);

    boolean existsByQuestionIdAndFirstServedAtIsNotNull(UUID questionId);

    void deleteByQuestionId(UUID questionId);

    /**
     * Mark a version served, once, whoever gets there first.
     *
     * <p>One conditional statement rather than read-then-write: two learners can be handed the
     * same question in the same millisecond, and with a read-then-write the loser's UPDATE meets a
     * row whose {@code first_served_at} is already set and is refused by the trigger — a failed
     * request for a learner who did nothing wrong. Written this way the loser updates no rows and
     * the caller is told so.
     *
     * @return 1 if this call was the one that made the version history, 0 if it already was
     */
    @Modifying
    @Query("UPDATE QuestionVersion v SET v.firstServedAt = :at "
        + "WHERE v.id = :id AND v.firstServedAt IS NULL")
    int markServed(@Param("id") UUID id, @Param("at") Instant at);
}
