package com.xenopsoftware.learn.catalog;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * A real Postgres for every test that touches the database — the identity module's harness,
 * carried by the template (T-9.10). The image tag matches docker-compose.yml's pin; this is now
 * the third declaration of that version, which is the drift T-9.10 still owes a single source
 * for.
 */
public abstract class PostgresTestHarness {

    /**
     * Every table, in foreign-key order, newest dependency first.
     *
     * <p>ONE list, here, rather than one per test class -- which is what this repository had until
     * a class that created course rows met a class that only knew how to delete content items, and
     * the failure surfaced as {@code course_node_content_item_id_fkey} inside a test that has
     * never heard of courses. The container is shared by every class in the module, so a class
     * that leaves rows behind is a class that breaks somebody else's setup, far from here.
     *
     * <p>Add a table to this list in the same commit that creates it.
     */
    private static final java.util.List<String> TABLES_IN_FK_ORDER = java.util.List.of(
        "reminder_sent", "assignment_reminder", "assignment_cycle",
        "assignment", "learner_group_reach", "learner_profile", "node_completion",
        "course_version",
        "gate_requirement", "gate",
        "course_node", "course_module", "course",
        "content_item");

    /** Empties the schema. Call it before AND after: before for a clean start, after out of manners. */
    protected static void emptyEveryTable(javax.sql.DataSource dataSource) {
        org.springframework.jdbc.core.JdbcTemplate jdbc =
            new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        for (String table : TABLES_IN_FK_ORDER) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Two connections, not the production ten. Spring caches a context per distinct
        // configuration and keeps them all alive, so every context holds a live pool against this
        // one Postgres -- identity hit the ceiling as "too many clients" inside Flyway, in
        // whichever class happened to load last, which reads as that class being broken. A test
        // class drives one request at a time.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 2);
        // Statement counting, for the tests that assert a read is bounded rather than per-row.
        // Set HERE rather than as a @SpringBootTest property on the classes that need it, because
        // a class declaring its own properties gets its own Spring context -- and a context is a
        // live connection pool against the one shared Postgres.
        registry.add("spring.jpa.properties.hibernate.generate_statistics", () -> true);
        // THE SCHEDULED RELAY AND SUBSCRIBER ARE PARKED, not disabled.
        //
        // Both are @Scheduled and both mutate the tables the messaging tests assert on -- the
        // relay marks rows published once a second, using whatever publisher is wired. A test
        // that wrote a row and then checked it was still unpublished was racing a background job
        // it never mentioned, and passed or failed on timing. Pushing the interval out to an hour
        // leaves every bean wired and constructed (so the wiring is still under test) while the
        // tests drive drain() themselves, which is the only way an assertion about the outbox can
        // mean anything.
        registry.add("platform.outbox.interval", () -> "PT1H");
        registry.add("platform.messaging.poll-interval", () -> "PT1H");
        registry.add("platform.outbox.metrics-interval", () -> "PT1H");
        // The reminder pass, parked for the same reason (T-5.6). It claims rows and sends mail on
        // a schedule; a test asserting that nothing has been sent yet would otherwise be racing
        // it. The tests drive sendFor() themselves with an explicit clock, which is the only way
        // an assertion about "not yet" or "eleven months from now" can mean anything.
        registry.add("catalog.due.interval", () -> "PT1H");
    }
}
