package com.xenopsoftware.learn.identity;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * A real Postgres for every test that touches the database (T-9.10's harness, arriving with
 * T-1.1, its first customer).
 *
 * <p>Real rather than in-memory, because the things these tests exist to prove — the tenant
 * discriminator, {@code NOT NULL} enforcement, Flyway's actual migrations — are Postgres
 * behaviour, and an H2 that accepts what Postgres would reject is a test of nothing.
 *
 * <p>One container, started once, shared by every test class that extends this: the singleton
 * pattern rather than a per-class {@code @Container}, because each JVM fork pays the startup cost
 * exactly once and the tests only ever create their own schemas inside it.
 *
 * <p>The image tag matches {@code docker-compose.yml}'s pin. Two declarations of the same version
 * is exactly the drift local-stack.md warns about; folding them into one source is still owed by
 * T-9.10 and gets more valuable with each service added.
 */
public abstract class PostgresTestHarness {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    static {
        POSTGRES.start();
        // No Ryuk tinkering, no manual stop: Testcontainers reaps the container when the JVM
        // exits, and a shared static container must not be stopped by any one test class.
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
