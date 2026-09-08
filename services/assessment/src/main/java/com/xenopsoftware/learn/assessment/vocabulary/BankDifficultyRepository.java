package com.xenopsoftware.learn.assessment.vocabulary;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * This tenant's difficulty scale, in order.
 *
 * <p>Ordered by rank rather than by code, which is the entire point of having a rank: sorting the
 * names gives "Easy, Hard, Medium".
 */
public interface BankDifficultyRepository extends JpaRepository<BankDifficulty, UUID> {

    List<BankDifficulty> findAllByOrderByRankAsc();

    Optional<BankDifficulty> findByCodeIgnoreCase(String code);

    Optional<BankDifficulty> findByRank(short rank);
}
