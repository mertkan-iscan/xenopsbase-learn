package com.xenopsoftware.learn.catalog.assign;

import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Which groups reach a learner, from catalog's own projection (T-5.5).
 *
 * <p><b>Reach, not membership, and the difference is where the tree walk happens.</b> A group
 * assignment reaches the members of that group and of everything inside it — containment is what
 * the tree means, and it is the rule role assignments already follow (T-2.3). Identity owns the
 * tree, so identity does the walk and publishes who is reached; catalog stores the result and
 * never needs to know the shape. That also keeps the depth limit
 * ({@code GroupHierarchy.MAX_DEPTH}) in the one module that can enforce it.
 *
 * <p><b>Not a synchronous call to identity, deliberately.</b> This is read on the screen a learner
 * opens first, and a home screen that fails when identity is slow is a home screen that fails.
 * ADR-0109 says the same thing about gates.
 *
 * <p><b>Nothing writes it yet.</b> T-1.3's membership events and T-9.8's bus are what fill it.
 * Until then a group assignment reaches nobody — the correct answer for a platform holding no
 * membership data, rather than an approximation that reaches everybody.
 */
@Component
public class LearnerGroupReach {

    /**
     * Stands in for "this learner is in no groups".
     *
     * <p>JPQL rejects an empty collection parameter outright, and the failure mode of papering
     * over that with an omitted clause is the one that matters: "in no groups" would silently
     * become "in every group", and every learner would be assigned everything.
     */
    static final UUID NONE = new UUID(0, 0);

    private final JdbcTemplate jdbc;

    public LearnerGroupReach(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** Every group that reaches this learner, or the sentinel when there are none. */
    public List<UUID> of(String tenantId, UUID learnerId) {
        List<UUID> reaching = jdbc.queryForList("""
            SELECT group_id FROM learner_group_reach WHERE tenant_id = ? AND learner_id = ?
            """, UUID.class, tenantId, learnerId);
        return reaching.isEmpty() ? List.of(NONE) : reaching;
    }
}
