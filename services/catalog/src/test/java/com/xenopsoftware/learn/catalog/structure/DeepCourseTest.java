package com.xenopsoftware.learn.catalog.structure;

import static org.assertj.core.api.Assertions.assertThat;

import com.xenopsoftware.learn.catalog.PostgresTestHarness;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * T-5.2's last criterion: <b>deep course fixtures, so the structure queries are exercised at a
 * realistic size.</b>
 *
 * <p>The failure this exists to catch is invisible at two modules. Reading a course by walking its
 * modules and asking for each one's nodes costs one query per module, which is indistinguishable
 * from correct in a small test and is forty-one round trips on a real course. So the tree is built
 * at a size where an N+1 shows up, and the query count is asserted rather than the wall clock —
 * a timing assertion on a laptop that is also running Postgres in Docker measures the laptop.
 *
 * <p>Built through the service rather than over HTTP: this is about the queries, and 900 HTTP
 * round trips would make the test slow for a reason that has nothing to do with what it checks.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class DeepCourseTest extends PostgresTestHarness {

    /** Deliberately past the point where a per-module query would still look fine. */
    private static final int MODULES = 30;
    private static final int NODES_PER_MODULE = 30;

    @Autowired
    private CourseService courses;

    @Autowired
    private jakarta.persistence.EntityManagerFactory entityManagerFactory;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    @BeforeEach
    void emptyEverything() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM course_node");
        jdbc.update("DELETE FROM course_module");
        jdbc.update("DELETE FROM course");
        jdbc.update("DELETE FROM content_item");
    }

    @Test
    void aNineHundredNodeCourseIsReadInTwoQueries() {
        UUID course = com.xenopsoftware.learn.common.tenancy.TenantContext
            .callWithUnchecked("acme", this::aDeepCourse);

        statistics().clear();
        CourseService.CourseTree tree = com.xenopsoftware.learn.common.tenancy.TenantContext
            .callWithUnchecked("acme", () -> courses.tree(course));
        long queries = statistics().getPrepareStatementCount();

        assertThat(tree.modules()).hasSize(MODULES);
        assertThat(tree.modules().stream().mapToInt(module -> module.nodes().size()).sum())
            .isEqualTo(MODULES * NODES_PER_MODULE);

        // Three: the course, its modules, and every node in one go. A per-module read would be
        // thirty-two here and four hundred on a big customer's course.
        assertThat(queries)
            .as("reading a course must not cost a query per module")
            .isLessThanOrEqualTo(3);
    }

    @Test
    void theTreeComesBackInOrderAtDepth() {
        UUID course = com.xenopsoftware.learn.common.tenancy.TenantContext
            .callWithUnchecked("acme", this::aDeepCourse);

        CourseService.CourseTree tree = com.xenopsoftware.learn.common.tenancy.TenantContext
            .callWithUnchecked("acme", () -> courses.tree(course));

        assertThat(tree.modules().stream().map(module -> module.module().getOrdinal()).toList())
            .isSorted();
        for (CourseService.ModuleTree module : tree.modules()) {
            assertThat(module.nodes().stream().map(CourseNode::getOrdinal).toList())
                .as("nodes of module %s", module.module().getTitle())
                .isSorted();
        }
    }

    @Test
    void aReorderInADeepCourseStillWritesOneRow() {
        UUID course = com.xenopsoftware.learn.common.tenancy.TenantContext
            .callWithUnchecked("acme", this::aDeepCourse);
        CourseService.CourseTree tree = com.xenopsoftware.learn.common.tenancy.TenantContext
            .callWithUnchecked("acme", () -> courses.tree(course));
        CourseService.ModuleTree module = tree.modules().getLast();
        UUID moving = module.nodes().getLast().getId();

        jdbc.update("UPDATE course_node SET updated_at = now() - interval '1 hour'");
        Instant started = Instant.now();
        com.xenopsoftware.learn.common.tenancy.TenantContext.callWithUnchecked("acme",
            () -> courses.moveNode(moving, null, null));

        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM course_node WHERE updated_at > now() - interval '1 minute'",
            Long.class))
            .as("still one row at 900 nodes -- the property dense integers cannot have")
            .isEqualTo(1);
        assertThat(Duration.between(started, Instant.now()))
            .as("and it does not get slower with depth, loosely bounded because a laptop running "
                + "Postgres in Docker is not a benchmark")
            .isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void requiredNodesAreASubsetAndOptionalOnesAreExcluded() {
        UUID course = com.xenopsoftware.learn.common.tenancy.TenantContext
            .callWithUnchecked("acme", this::aDeepCourse);

        List<CourseNode> required = com.xenopsoftware.learn.common.tenancy.TenantContext
            .callWithUnchecked("acme", () -> courses.requiredNodes(course));

        // Every third node was made optional by the fixture.
        assertThat(required).hasSize(MODULES * NODES_PER_MODULE - (MODULES * NODES_PER_MODULE / 3));
        assertThat(required).allMatch(CourseNode::isRequired);
    }

    /**
     * Thirty modules of thirty nodes, every third node optional.
     *
     * <p>Written in SQL rather than through the service on purpose: the service would validate a
     * content item per node and this fixture is about SHAPE, not about re-testing T-5.1. It is
     * also the difference between a fixture that takes a second and one that takes a minute, and
     * a slow test is a test someone eventually stops running.
     */
    private UUID aDeepCourse() {
        UUID courseId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO course (id, tenant_id, title, created_at, updated_at)
            VALUES (?, 'acme', 'A realistic course', now(), now())
            """, courseId);

        UUID item = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO content_item (id, tenant_id, type, title, state, payload, tags, created_at, updated_at)
            VALUES (?, 'acme', 'video', 'Shared', 'PUBLISHED', '{"assetId":"a"}'::jsonb, '{}', now(), now())
            """, item);

        List<Object[]> moduleRows = new ArrayList<>();
        List<Object[]> nodeRows = new ArrayList<>();
        for (int m = 0; m < MODULES; m++) {
            UUID moduleId = UUID.randomUUID();
            moduleRows.add(new Object[] {moduleId, courseId, "Module " + m,
                BigDecimal.valueOf((m + 1) * 1000L)});
            for (int n = 0; n < NODES_PER_MODULE; n++) {
                nodeRows.add(new Object[] {UUID.randomUUID(), moduleId, item,
                    BigDecimal.valueOf((n + 1) * 1000L), (m * NODES_PER_MODULE + n) % 3 != 0});
            }
        }
        jdbc.batchUpdate("""
            INSERT INTO course_module (id, tenant_id, course_id, title, ordinal, created_at, updated_at)
            VALUES (?, 'acme', ?, ?, ?, now(), now())
            """, moduleRows);
        jdbc.batchUpdate("""
            INSERT INTO course_node (id, tenant_id, module_id, content_item_id, ordinal, required,
                                     created_at, updated_at)
            VALUES (?, 'acme', ?, ?, ?, ?, now(), now())
            """, nodeRows);
        return courseId;
    }

    /**
     * Hibernate's own JDBC statement counter.
     *
     * <p>Postgres could answer this too, through {@code pg_stat_statements} — but that extension
     * needs {@code shared_preload_libraries} set at server start and is not in a stock
     * {@code postgres:17-alpine}, so the query would fail rather than measure. Hibernate counts
     * what it prepares, which is exactly the round trips this test is about: the reads here all
     * go through it.
     */
    private org.hibernate.stat.Statistics statistics() {
        return entityManagerFactory.unwrap(org.hibernate.SessionFactory.class).getStatistics();
    }
}
