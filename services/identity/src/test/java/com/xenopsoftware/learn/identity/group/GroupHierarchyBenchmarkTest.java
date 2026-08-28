package com.xenopsoftware.learn.identity.group;

import static org.assertj.core.api.Assertions.assertThat;

import com.xenopsoftware.learn.common.tenancy.TenantContext;
import com.xenopsoftware.learn.identity.PostgresTestHarness;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The measurement T-1.3 asks for: the descendant query at a realistic depth and breadth.
 *
 * <p>The adjacency list buys a one-row move and pays for it on the read, and this is the read.
 * The shape is a large customer at the scale the backlog already names — 5,000 learners
 * (T-7.6) — over a five-level tree: 1 + 6 + 36 + 216 + 1296 = 1555 groups.
 *
 * <p>The assertion is deliberately loose (a CI container is not a benchmark rig) — its job is to
 * catch an order-of-magnitude regression, such as somebody dropping the parent index or
 * replacing this with a per-node walk. The number that matters is the one it prints, and it
 * goes in the issue rather than being remembered.
 */
@SpringBootTest
class GroupHierarchyBenchmarkTest extends PostgresTestHarness {

    private static final int BREADTH = 6;
    private static final int DEPTH = 4;
    private static final int LEARNERS = 5000;

    @Autowired
    private GroupHierarchy hierarchy;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;
    private UUID root;

    @BeforeEach
    void buildARealisticCompany() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM group_membership");
        jdbc.update("DELETE FROM user_group");
        jdbc.update("DELETE FROM app_user");

        List<Object[]> groupRows = new ArrayList<>();
        root = UUID.randomUUID();
        groupRows.add(new Object[] {root, "acme", null, "Company"});
        List<UUID> level = List.of(root);
        List<UUID> everyGroup = new ArrayList<>(List.of(root));
        for (int depth = 1; depth <= DEPTH; depth++) {
            List<UUID> next = new ArrayList<>();
            for (UUID parent : level) {
                for (int n = 0; n < BREADTH; n++) {
                    UUID id = UUID.randomUUID();
                    groupRows.add(new Object[] {id, "acme", parent, "g-" + depth + "-" + n + "-" + id});
                    next.add(id);
                }
            }
            everyGroup.addAll(next);
            level = next;
        }
        jdbc.batchUpdate("""
            INSERT INTO user_group (id, tenant_id, parent_id, name, created_at, updated_at)
            VALUES (?, ?, ?, ?, now(), now())
            """, groupRows);

        List<Object[]> userRows = new ArrayList<>();
        List<Object[]> membershipRows = new ArrayList<>();
        for (int n = 0; n < LEARNERS; n++) {
            UUID id = UUID.randomUUID();
            userRows.add(new Object[] {id, "acme", "learner" + n + "@acme.test", "Learner " + n});
            // Spread across the leaves, which is where a real company puts people.
            membershipRows.add(new Object[] {UUID.randomUUID(), "acme",
                everyGroup.get(everyGroup.size() - 1 - (n % (everyGroup.size() / 2))), id});
        }
        jdbc.batchUpdate("""
            INSERT INTO app_user (id, tenant_id, email, display_name, status, created_at, updated_at)
            VALUES (?, ?, ?, ?, 'ACTIVE', now(), now())
            """, userRows);
        jdbc.batchUpdate("""
            INSERT INTO group_membership (id, tenant_id, group_id, user_id, created_at)
            VALUES (?, ?, ?, ?, now())
            """, membershipRows);
    }

    /**
     * This class creates app_user rows, and group_membership now has a real foreign key to
     * them — so leaving them behind breaks any other class in the module that clears app_user.
     * The class that made the rows removes them, in foreign-key order.
     */
    @org.junit.jupiter.api.AfterEach
    void removeWhatThisClassCreated() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM group_membership");
        jdbc.update("DELETE FROM user_group");
        jdbc.update("DELETE FROM app_user");
    }

    @Test
    void theDescendantQueryHoldsUpAtARealisticShape() throws Exception {
        TenantContext.callWith("acme", () -> {
            int groupCount = 1 + 6 + 36 + 216 + 1296;
            assertThat(hierarchy.subtreeIds(root)).hasSize(groupCount);

            long subtreeNanos = timed(() -> hierarchy.subtreeIds(root));
            long reachNanos = timed(() -> hierarchy.reachableUserIds(Set.of(root)));

            System.out.printf("T-1.3 benchmark: %d groups, %d learners, depth %d, breadth %d%n"
                + "  subtreeIds(root)      %.1f ms%n"
                + "  reachableUserIds(root) %.1f ms%n",
                groupCount, LEARNERS, DEPTH, BREADTH,
                subtreeNanos / 1_000_000.0, reachNanos / 1_000_000.0);

            assertThat(hierarchy.reachableUserIds(Set.of(root))).hasSize(LEARNERS);
            // An order-of-magnitude tripwire, not a performance target.
            assertThat(subtreeNanos / 1_000_000).isLessThan(500);
            assertThat(reachNanos / 1_000_000).isLessThan(500);
            return null;
        });
    }

    @Test
    void aMoveIsOneRowNoMatterHowBigTheSubtree() throws Exception {
        TenantContext.callWith("acme", () -> {
            List<UUID> topLevel = jdbc.queryForList(
                "SELECT id FROM user_group WHERE parent_id = ? ORDER BY name", UUID.class, root);
            UUID from = topLevel.get(0);
            UUID to = topLevel.get(1);
            int movedSubtree = hierarchy.subtreeIds(from).size();

            long before = rowsUpdatedByAMove(from, to);

            assertThat(movedSubtree).isGreaterThan(200);
            assertThat(before)
                .as("a move of a %d-group subtree must still be one row", movedSubtree)
                .isEqualTo(1);
            // And the subtree really did move with it.
            assertThat(hierarchy.subtreeIds(to)).contains(from);
            return null;
        });
    }

    private long rowsUpdatedByAMove(UUID groupId, UUID newParentId) {
        return jdbc.update("UPDATE user_group SET parent_id = ?, updated_at = now() "
            + "WHERE id = ? AND tenant_id = 'acme'", newParentId, groupId);
    }

    private static long timed(Runnable work) {
        long start = System.nanoTime();
        work.run();
        return System.nanoTime() - start;
    }
}
