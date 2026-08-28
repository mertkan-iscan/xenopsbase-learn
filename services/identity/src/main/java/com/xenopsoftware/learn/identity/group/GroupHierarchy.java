package com.xenopsoftware.learn.identity.group;

import com.xenopsoftware.learn.common.tenancy.TenantContext;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * "Which groups are under this one, and who is in them" — implemented once (T-1.3's third
 * criterion), so authorization (T-2.3), assignment (T-5.5) and reporting (T-7.6) ask the same
 * code the same question and cannot drift into three different answers.
 *
 * <h2>Why the tenant is passed by hand</h2>
 *
 * These are native queries, and <b>Hibernate's {@code @TenantId} discriminator does not touch
 * native SQL</b>. Everything T-1.1 built — the filter that makes a forgotten {@code WHERE}
 * harmless — silently does not apply here. So every statement below carries
 * {@code tenant_id = ?} explicitly, from {@link TenantContext#require()}, and the boundary test
 * proves it rather than trusting this paragraph. A recursive CTE that walked without it would
 * cross tenants at the first shared parent id.
 *
 * <h2>Why recursive SQL and not a closure table</h2>
 *
 * The adjacency list keeps a move to one row (V4's comment). That trade puts the cost on this
 * side, so the cost is measured rather than argued: see {@code GroupHierarchyBenchmarkTest}.
 */
@Component
public class GroupHierarchy {

    /**
     * The tree may not be deeper than this. Enforced on every write, and repeated as a hard cap
     * inside each recursive query — a depth guard that lives only in the write path stops being
     * a guard the moment data arrives another way.
     */
    public static final int MAX_DEPTH = 8;

    private final JdbcTemplate jdbc;

    public GroupHierarchy(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /**
     * The group and everything beneath it — an admin's reach, in one query.
     *
     * <p>The {@code depth < MAX_DEPTH} clause is the termination guarantee the criterion asks
     * for: it bounds the walk by the tree's own rules, so no data state produces an infinite
     * walk. {@code UNION} rather than {@code UNION ALL} is the second guard, dropping a node
     * that somehow reappears.
     */
    public Set<UUID> subtreeIds(UUID rootId) {
        List<UUID> ids = jdbc.queryForList("""
            WITH RECURSIVE subtree(id, depth) AS (
                SELECT id, 0
                  FROM user_group
                 WHERE id = ? AND tenant_id = ?
                UNION
                SELECT child.id, parent.depth + 1
                  FROM user_group child
                  JOIN subtree parent ON child.parent_id = parent.id
                 WHERE child.tenant_id = ? AND parent.depth < ?
            )
            SELECT id FROM subtree
            """, UUID.class, rootId, tenant(), tenant(), MAX_DEPTH);
        return Set.copyOf(ids);
    }

    /** Everyone in any of these groups or their descendants — the reporting and assignment question. */
    public Set<UUID> reachableUserIds(Collection<UUID> groupIds) {
        if (groupIds.isEmpty()) {
            return Set.of();
        }
        // Placeholders rather than a SQL array parameter: uuid[] binding is driver-specific,
        // and the root set here is a handful of groups (what one admin is scoped to), never a
        // list long enough to make the parse cost matter.
        String roots = String.join(",", java.util.Collections.nCopies(groupIds.size(), "?"));
        Object[] args = new Object[groupIds.size() + 4];
        int at = 0;
        for (UUID groupId : groupIds) {
            args[at++] = groupId;
        }
        args[at++] = tenant();
        args[at++] = tenant();
        args[at++] = MAX_DEPTH;
        args[at] = tenant();
        List<UUID> ids = jdbc.queryForList("""
            WITH RECURSIVE subtree(id, depth) AS (
                SELECT id, 0
                  FROM user_group
                 WHERE id IN (%s) AND tenant_id = ?
                UNION
                SELECT child.id, parent.depth + 1
                  FROM user_group child
                  JOIN subtree parent ON child.parent_id = parent.id
                 WHERE child.tenant_id = ? AND parent.depth < ?
            )
            SELECT DISTINCT m.user_id
              FROM group_membership m
              JOIN subtree s ON m.group_id = s.id
             WHERE m.tenant_id = ?
            """.formatted(roots), UUID.class, args);
        return Set.copyOf(ids);
    }

    /** The chain from this group up to its root, nearest first. Bounded by {@link #MAX_DEPTH}. */
    public List<UUID> ancestorIds(UUID groupId) {
        return jdbc.queryForList("""
            WITH RECURSIVE chain(id, parent_id, depth) AS (
                SELECT id, parent_id, 0
                  FROM user_group
                 WHERE id = ? AND tenant_id = ?
                UNION
                SELECT parent.id, parent.parent_id, child.depth + 1
                  FROM user_group parent
                  JOIN chain child ON child.parent_id = parent.id
                 WHERE parent.tenant_id = ? AND child.depth < ?
            )
            SELECT id FROM chain WHERE id <> ? ORDER BY depth
            """, UUID.class, groupId, tenant(), tenant(), MAX_DEPTH + 1, groupId);
    }

    /** How many levels sit below this group; 0 for a leaf. */
    public int subtreeHeight(UUID rootId) {
        Integer height = jdbc.queryForObject("""
            WITH RECURSIVE subtree(id, depth) AS (
                SELECT id, 0
                  FROM user_group
                 WHERE id = ? AND tenant_id = ?
                UNION
                SELECT child.id, parent.depth + 1
                  FROM user_group child
                  JOIN subtree parent ON child.parent_id = parent.id
                 WHERE child.tenant_id = ? AND parent.depth < ?
            )
            SELECT coalesce(max(depth), 0) FROM subtree
            """, Integer.class, rootId, tenant(), tenant(), MAX_DEPTH);
        return height == null ? 0 : height;
    }

    private static String tenant() {
        return TenantContext.require();
    }
}
