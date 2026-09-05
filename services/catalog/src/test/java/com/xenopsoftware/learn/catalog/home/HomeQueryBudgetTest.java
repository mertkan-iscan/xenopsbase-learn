package com.xenopsoftware.learn.catalog.home;

import static org.assertj.core.api.Assertions.assertThat;

import com.xenopsoftware.learn.catalog.PostgresTestHarness;
import com.xenopsoftware.learn.common.tenancy.TenantContext;
import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * THE CRITERION THIS TASK IS REALLY ABOUT: the home screen costs the same whether somebody has one
 * assignment or twenty (T-5.8).
 *
 * <p>Assembled naively the screen is a query per assignment, another per module inside each of
 * them, and another per node for progress. Every one of those is invisible on a demo tenant with
 * three courses and is the thing that falls over first at a customer with five thousand people —
 * which is precisely why the issue asks for a measurement rather than a claim.
 *
 * <p>Two things are asserted here and neither is a benchmark of this laptop:
 *
 * <ul>
 *   <li><b>The statement count does not grow with the assignment count.</b> Hibernate's own JDBC
 *       counter, for the reason {@code DeepCourseTest} gives: {@code pg_stat_statements} needs
 *       {@code shared_preload_libraries} and is not in {@code postgres:17-alpine}.</li>
 *   <li><b>The wall-clock figure is printed with its conditions</b> and copied into
 *       {@code docs/slos.md}, where a number without its conditions is not a measurement.</li>
 * </ul>
 *
 * <p>The queries this counter cannot see — the four plain-JDBC reads for group reach, profile,
 * completions and progress — are fixed by construction: each is called once per assembly, over the
 * whole learner at a time. They are counted in the figure below, because the clock does not care
 * which API issued the statement.
 */
@SpringBootTest
class HomeQueryBudgetTest extends PostgresTestHarness {

    private static final UUID LEARNER = UUID.fromString("00000000-0000-4000-8000-000000000002");

    /** The shape the figure is stated at: twenty courses of five modules of five nodes. */
    private static final int COURSES = 20;
    private static final int MODULES_PER_COURSE = 5;
    private static final int NODES_PER_MODULE = 5;

    @Autowired
    private HomeService home;
    @Autowired
    private EntityManagerFactory entityManagerFactory;
    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;
    private final List<UUID> courses = new ArrayList<>();

    @BeforeEach
    void aCompanyWithARealAmountOfContent() {
        jdbc = new JdbcTemplate(dataSource);
        emptyEveryTable(dataSource);
        courses.clear();
    }

    @AfterEach
    void leaveNothingForTheNextClass() {
        emptyEveryTable(dataSource);
    }

    @Test
    void theScreenCostsTheSameWithTwentyAssignmentsAsWithOne() {
        buildCourses(COURSES);
        assign(courses.getFirst());

        long forOne = statementsForOneScreen();

        for (int index = 1; index < COURSES; index++) {
            assign(courses.get(index));
        }
        long forTwenty = statementsForOneScreen();

        System.out.printf("home screen statements: 1 assignment=%d, %d assignments=%d%n",
            forOne, COURSES, forTwenty);

        assertThat(forTwenty)
            .as("a query per assignment would be twenty more; a query per module, a hundred more")
            .isEqualTo(forOne);
        assertThat(forTwenty)
            .as("and the whole screen is a handful of statements, which is the number worth "
                + "pinning: it is easy to lose one bulk read in a one-line change")
            .isLessThanOrEqualTo(16);
    }

    /**
     * The figure {@code docs/slos.md} carries.
     *
     * <p>Assembly only — no cache, because a cached screen measures Valkey rather than this — with
     * a learner who has genuinely done some of the work, so the completion and progress reads are
     * not answering about an empty set.
     */
    @Test
    void aFullScreenIsAssembledInOneRead() {
        buildCourses(COURSES);
        for (UUID course : courses) {
            assign(course);
        }
        someProgress();

        // Warm: the first assembly of a fresh context pays for statement preparation and the JIT,
        // and reporting that as the number would be measuring the test rather than the query.
        for (int run = 0; run < 3; run++) {
            assemble();
        }
        List<Long> runs = new ArrayList<>();
        for (int run = 0; run < 10; run++) {
            long startedAt = System.nanoTime();
            HomeView view = assemble();
            runs.add((System.nanoTime() - startedAt) / 1_000_000);
            assertThat(view.courses()).hasSize(COURSES);
        }
        runs.sort(Long::compareTo);

        System.out.printf("home screen assembled: %d courses, %d modules, %d nodes, "
            + "%d assignments -- median %dms, worst %dms%n",
            COURSES, COURSES * MODULES_PER_COURSE, COURSES * MODULES_PER_COURSE * NODES_PER_MODULE,
            COURSES, runs.get(runs.size() / 2), runs.getLast());

        assertThat(runs.get(runs.size() / 2))
            .as("not a benchmark of this machine -- a bound loose enough to survive a busy laptop "
                + "and tight enough to catch the day somebody puts a query in a loop")
            .isLessThan(1_000);
    }

    // ---------------------------------------------------------------- plumbing

    private HomeView assemble() {
        return TenantContext.callWithUnchecked("acme",
            () -> home.forLearner(LEARNER, Instant.now()));
    }

    private long statementsForOneScreen() {
        statistics().clear();
        assemble();
        return statistics().getPrepareStatementCount();
    }

    private org.hibernate.stat.Statistics statistics() {
        return entityManagerFactory.unwrap(org.hibernate.SessionFactory.class).getStatistics();
    }

    /**
     * Courses, modules, nodes and items, written in SQL.
     *
     * <p>Through the API this fixture is 520 requests and half a minute; the endpoints that build
     * it have their own tests, and what this class measures is the read.
     */
    private void buildCourses(int howMany) {
        Instant now = Instant.now();
        for (int c = 0; c < howMany; c++) {
            UUID courseId = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO course (id, tenant_id, title, created_at, updated_at)
                VALUES (?, 'acme', ?, ?, ?)
                """, courseId, "Course " + c, java.sql.Timestamp.from(now),
                java.sql.Timestamp.from(now));
            for (int m = 0; m < MODULES_PER_COURSE; m++) {
                UUID moduleId = UUID.randomUUID();
                jdbc.update("""
                    INSERT INTO course_module (id, tenant_id, course_id, title, ordinal, created_at,
                            updated_at)
                    VALUES (?, 'acme', ?, ?, ?, ?, ?)
                    """, moduleId, courseId, "Module " + m, m, java.sql.Timestamp.from(now),
                    java.sql.Timestamp.from(now));
                for (int n = 0; n < NODES_PER_MODULE; n++) {
                    UUID itemId = UUID.randomUUID();
                    jdbc.update("""
                        INSERT INTO content_item (id, tenant_id, type, title, state, created_at,
                                updated_at)
                        VALUES (?, 'acme', 'video', ?, 'PUBLISHED', ?, ?)
                        """, itemId, "Step " + c + "." + m + "." + n,
                        java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));
                    jdbc.update("""
                        INSERT INTO course_node (id, tenant_id, module_id, content_item_id, ordinal,
                                required, created_at, updated_at)
                        VALUES (?, 'acme', ?, ?, ?, true, ?, ?)
                        """, UUID.randomUUID(), moduleId, itemId, n,
                        java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));
                }
            }
            // Published, because an assignment pins a version (T-5.7) and a draft cannot be
            // assigned at all.
            jdbc.update("""
                INSERT INTO course_version (id, tenant_id, course_id, version, notes, published_by,
                        published_at, snapshot)
                VALUES (?, 'acme', ?, 1, 'ready', ?, ?, '{}'::jsonb)
                """, UUID.randomUUID(), courseId, UUID.randomUUID(),
                java.sql.Timestamp.from(now));
            courses.add(courseId);
        }
    }

    private void assign(UUID courseId) {
        UUID assignmentId = UUID.randomUUID();
        LocalDate due = LocalDate.now().plusDays(14);
        jdbc.update("""
            INSERT INTO assignment (id, tenant_id, target_type, target_id, reference_type,
                    reference_id, pinned_version, assigned_by, assigned_at, due_kind, due_on)
            VALUES (?, 'acme', 'USER', ?, 'COURSE', ?, 1, ?, now(), 'ABSOLUTE', ?)
            """, assignmentId, LEARNER, courseId, UUID.randomUUID(), java.sql.Date.valueOf(due));
        // Cycle 1, as making the assignment through the service would have (T-5.6). Without it
        // the first read opens one per assignment, and a measurement of the READ would be
        // measuring twenty one-off writes that only ever happen once.
        jdbc.update("""
            INSERT INTO assignment_cycle (id, tenant_id, assignment_id, cycle_number, opens_at,
                    due_on, created_at)
            VALUES (?, 'acme', ?, 1, now(), ?, now())
            """, UUID.randomUUID(), assignmentId, java.sql.Date.valueOf(due));
    }

    /** A learner part-way through: some nodes finished, some in progress. */
    private void someProgress() {
        List<UUID> nodeIds = jdbc.queryForList("SELECT id FROM course_node", UUID.class);
        int index = 0;
        for (UUID nodeId : nodeIds) {
            if (index % 3 == 0) {
                jdbc.update("""
                    INSERT INTO node_completion (id, tenant_id, learner_id, node_id, state,
                            recorded_at)
                    VALUES (?, 'acme', ?, ?, 'COMPLETED', now())
                    """, UUID.randomUUID(), LEARNER, nodeId);
            } else if (index % 3 == 1) {
                jdbc.update("""
                    INSERT INTO node_progress (tenant_id, learner_id, node_id, percent,
                            resume_second, completed, updated_at)
                    VALUES ('acme', ?, ?, 40, 120, false, now())
                    """, LEARNER, nodeId);
            }
            index++;
        }
    }
}
