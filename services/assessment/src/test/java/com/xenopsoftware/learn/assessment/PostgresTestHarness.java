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
     * Every table, in foreign-key order, newest dependency first.
     *
     * <p>ONE list, here, rather than one per test class — catalog's harness carries the story of
     * why: the container is shared by every class in the module, so a class that leaves rows
     * behind breaks somebody else's setup a long way from where the mess was made.
     *
     * <p>There are no foreign keys between these three yet, and the order is still written down,
     * because the first one that arrives will not come with a reminder to reorder this list.
     *
     * <p>Add a table here in the same commit that creates it.
     */
    private static final java.util.List<String> TABLES_IN_FK_ORDER = java.util.List.of(
        "bank_difficulty", "bank_tag", "question_bank");

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
        // one Postgres — identity hit the ceiling as "too many clients" inside Flyway, in
        // whichever class happened to load last, which reads as that class being broken.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 2);
    }
}
