package com.xenopsoftware.learn.catalog.gate;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * What a learner has finished, read from catalog's own projection (T-5.3).
 *
 * <p><b>A copy, and it says so.</b> The module that observes the evidence owns the record of it
 * (ADR-0109): streaming derives completion from watched intervals (ADR-0107), assessment from a
 * submitted attempt. Catalog keeps this so a gate can be answered without three synchronous calls
 * on the screen a learner looks at most — a gate evaluated by calling three services is a gate
 * that fails whenever any of them is slow.
 *
 * <p><b>Nothing writes it yet.</b> T-3.7 and T-9.8 are what fill it, by event. Until then this
 * answers as it would for a learner who has completed nothing, which is the correct answer for a
 * platform where nobody has finished anything — and deliberately not an approximation that lets
 * everything through. There is no endpoint that writes it either: an API letting a client declare
 * itself complete is precisely the hole ADR-0107 exists to close.
 */
@Component
public class NodeCompletionRepository {

    private final JdbcTemplate jdbc;

    public NodeCompletionRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /**
     * Everything this learner has reached among these nodes, as node id to the states it is in.
     *
     * <p>One query for the whole course rather than one per node, which is what keeps evaluation
     * bounded no matter how deep the course is (T-5.3's last criterion).
     *
     * <p>Plain SQL naming its tenant, because this table has no entity: it is a projection read
     * in bulk and never edited, and giving it one would invite somebody to write through it.
     */
    public Map<UUID, java.util.Set<RequiredState>> statesOf(String tenantId, UUID learnerId,
            Collection<UUID> nodeIds) {
        if (nodeIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(nodeIds.size(), "?"));
        Object[] arguments = new Object[nodeIds.size() + 2];
        arguments[0] = tenantId;
        arguments[1] = learnerId;
        int index = 2;
        for (UUID nodeId : nodeIds) {
            arguments[index++] = nodeId;
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT node_id, state FROM node_completion
             WHERE tenant_id = ? AND learner_id = ? AND node_id IN (
            """ + placeholders + ")", arguments);

        Map<UUID, java.util.Set<RequiredState>> states = new java.util.HashMap<>();
        for (Map<String, Object> row : rows) {
            states.computeIfAbsent((UUID) row.get("node_id"), any -> java.util.EnumSet.noneOf(
                RequiredState.class)).add(RequiredState.valueOf((String) row.get("state")));
        }
        return states;
    }
}
