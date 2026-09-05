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
    }
}
