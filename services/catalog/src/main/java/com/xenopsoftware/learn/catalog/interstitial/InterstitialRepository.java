package com.xenopsoftware.learn.catalog.interstitial;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InterstitialRepository extends JpaRepository<Interstitial, UUID> {

    /** Everything on a node, in the order a player meets it. */
    List<Interstitial> findByNodeIdOrderByPositionSecondsAsc(UUID nodeId);

    /** The same for a whole course's worth of nodes, in one query rather than one per node. */
    List<Interstitial> findByNodeIdInOrderByNodeIdAscPositionSecondsAsc(
        java.util.Collection<UUID> nodeIds);
}
