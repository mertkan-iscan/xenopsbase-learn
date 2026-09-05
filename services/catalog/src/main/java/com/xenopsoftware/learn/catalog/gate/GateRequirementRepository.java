package com.xenopsoftware.learn.catalog.gate;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GateRequirementRepository extends JpaRepository<GateRequirement, UUID> {

    /**
     * Every requirement of every gate named, in one query.
     *
     * <p>A requirement-per-gate read would be the N+1 that makes a forty-gate course cost forty
     * round trips on the screen a learner opens first.
     */
    List<GateRequirement> findByGateIdIn(Collection<UUID> gateIds);

    void deleteByGateId(UUID gateId);
}
