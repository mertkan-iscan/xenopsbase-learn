package com.xenopsoftware.learn.assessment;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * A real Postgres for every test that touches the database — identity's harness, carried by the
 * template (T-9.10) and now in its fourth copy. The image tag matches docker-compose.yml's pin;
 * that is the fourth declaration of the same version, and the single source T-9.10 still owes.
 */
public abstract class PostgresTestHarness {

    /**
     * Every table this module owns.
     *
     * <p>ONE list, here, rather than one per test class — catalog's harness carries the story of
     * why: the container is shared by every class in the module, so a class that leaves rows behind
     * breaks somebody else's setup a long way from where the mess was made.
     *
     * <p>Add a table here in the same commit that creates it.
     */
    private static final java.util.List<String> EVERY_TABLE = java.util.List.of(
        "attempt_event",
        "grade_event", "response_criterion_mark", "rubric_criterion",
        "attempt_response", "attempt",
        "outbox", "consumed_message",
        "test_form_item", "test_form",
        "test_section_question", "test_section_tag", "test_section", "test",
        "question_tag", "question_version", "question",
        "bank_difficulty", "bank_tag", "question_bank");

    /**
     * Empties the schema. Call it before AND after: before for a clean start, after out of manners.
     *
     * <p><b>TRUNCATE rather than DELETE, and not for speed.</b> Two things in this schema make a
     * DELETE per table in dependency order impossible, and both are deliberate:
     *
     * <ul>
     *   <li>{@code question} and {@code question_version} reference each other (T-6.2), so there is
     *       no order — one of them always has to go first while the other still points at it.
     *   <li>{@code question_version} <b>refuses to be deleted once it has been served</b>. That is
     *       the ADR-0106 trigger doing its job, and a test suite that could get around it would be
     *       a test suite proving something weaker than the product does.
     * </ul>
     *
     * <p>TRUNCATE resolves the first with one statement and the second by not firing row triggers
     * at all — which is exactly the distinction being drawn: an application may not delete that
     * row, and resetting a schema between tests is not an application.
     */
    protected static void emptyEveryTable(javax.sql.DataSource dataSource) {
        new org.springframework.jdbc.core.JdbcTemplate(dataSource)
            .update("TRUNCATE TABLE " + String.join(", ", EVERY_TABLE) + " CASCADE");
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
        // one Postgres — identity hit the ceiling as "too many clients" inside Flyway, in
        // whichever class happened to load last, which reads as that class being broken.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 2);
        // THE ATTEMPT REAPER IS PARKED, not disabled -- catalog's reminder pass carries the same
        // note (T-6.6). It ends attempts on a schedule, so a test asserting that one is still open
        // would be racing a background job it never mentioned and would pass or fail on timing.
        // The tests drive sweep() themselves against a clock they move, which is the only way an
        // assertion about "not yet" can mean anything.
        registry.add("assessment.attempt.interval", () -> "PT1H");
        // The integrity-signal retention sweep, parked for the same reason (T-6.8): it deletes
        // rows the tests assert on, and a test checking that a signal is still there would be
        // racing it.
        registry.add("assessment.integrity.sweep-interval", () -> "PT24H");
        // THE RELAY AND SUBSCRIBER ARE PARKED, not disabled -- catalog's harness carries the
        // reasoning. Both are @Scheduled and both mutate the table the grading tests assert on, so
        // a test that published a row and then checked it was still unpublished would be racing a
        // background job it never mentioned. Every bean stays wired and constructed.
        registry.add("platform.outbox.interval", () -> "PT1H");
        registry.add("platform.messaging.poll-interval", () -> "PT1H");
        registry.add("platform.outbox.metrics-interval", () -> "PT1H");
    }
}
