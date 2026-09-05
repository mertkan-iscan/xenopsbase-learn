package com.xenopsoftware.learn.catalog.gate;

import static org.assertj.core.api.Assertions.assertThat;

import com.xenopsoftware.learn.catalog.PostgresTestHarness;
import com.xenopsoftware.learn.common.messaging.OutboxMessage;
import com.xenopsoftware.learn.common.tenancy.TenantContext;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * What arrives when streaming decides somebody finished a video (T-3.7, T-9.8, T-5.3).
 *
 * <p>The gates shipped able to require "complete Module 1" and with nothing that could ever record
 * one. This is the other end of that: the event catalog folds into its own projection, so a gate
 * evaluation stays one query against a local table rather than a call into whichever module
 * happened to observe the evidence.
 */
@SpringBootTest
class NodeCompletionTest extends PostgresTestHarness {

    private static final String TENANT = "acme";
    private static final UUID LEARNER = UUID.randomUUID();

    @Autowired
    private NodeCompletionHandler handler;
    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;
    private UUID node;

    @BeforeEach
    void aCourseWithOneNode() {
        jdbc = new JdbcTemplate(dataSource);
        emptyEveryTable(dataSource);
        UUID item = UUID.randomUUID();
        UUID course = UUID.randomUUID();
        UUID module = UUID.randomUUID();
        node = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO content_item (id, tenant_id, type, title, state, created_at, updated_at)
            VALUES (?, ?, 'video', 'Fire safety', 'PUBLISHED', now(), now())
            """, item, TENANT);
        jdbc.update("""
            INSERT INTO course (id, tenant_id, title, created_at, updated_at)
            VALUES (?, ?, 'Onboarding', now(), now())
            """, course, TENANT);
        jdbc.update("""
            INSERT INTO course_module (id, tenant_id, course_id, title, ordinal, created_at,
                    updated_at)
            VALUES (?, ?, ?, 'Module 1', 1, now(), now())
            """, module, TENANT, course);
        jdbc.update("""
            INSERT INTO course_node (id, tenant_id, module_id, content_item_id, ordinal, required,
                    created_at, updated_at)
            VALUES (?, ?, ?, ?, 1, true, now(), now())
            """, node, TENANT, module, item);
    }

    @AfterEach
    void tidy() {
        emptyEveryTable(dataSource);
    }

    @Test
    void aDerivedCompletionBecomesTheStateAGateReads() {
        Instant completedAt = Instant.parse("2026-09-05T10:00:00Z");
        handler.handle(completion(node, completedAt));

        assertThat(states()).containsExactly("COMPLETED");
        assertThat(jdbc.queryForObject(
            "SELECT recorded_at FROM node_completion WHERE node_id = ?", Instant.class, node))
            .as("recorded when the learner crossed the threshold, not when the bus got round to "
                + "telling us -- a completion that sat in a backlog did not happen late")
            .isEqualTo(completedAt);

        // And the read a gate actually makes finds it.
        assertThat(TenantContext.callWithUnchecked(TENANT, () ->
            new NodeCompletionRepository(dataSource).statesOf(TENANT, LEARNER, java.util.List.of(node))))
            .containsEntry(node, java.util.EnumSet.of(RequiredState.COMPLETED));
    }

    @Test
    void theSameCompletionTwiceIsOneRow() {
        OutboxMessage message = completion(node, Instant.parse("2026-09-05T10:00:00Z"));
        handler.handle(message);
        handler.handle(message);

        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM node_completion WHERE node_id = ?", Long.class, node))
            .as("the bus re-sends by design, and the unique key is what survives it -- not a "
                + "lookup, which has a window where two deliveries both find nothing")
            .isEqualTo(1);
    }

    @Test
    void aCompletionForANodeThisCompanyDoesNotHaveIsDroppedRatherThanRetriedForever() {
        handler.handle(completion(UUID.randomUUID(), Instant.now()));

        // The node was deleted between the learner finishing it and this arriving. A failing
        // insert would put a poison message at the head of the queue and hold up every other
        // learner's completion behind it.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM node_completion", Long.class))
            .isZero();
    }

    private OutboxMessage completion(UUID nodeId, Instant completedAt) {
        String payload = """
            {"tenantId":"%s","learnerId":"%s","nodeId":"%s","videoAssetId":"%s",
             "coveredSeconds":540,"extentSeconds":600,"thresholdPercent":90,"approximate":false,
             "source":"DERIVED","completedAt":"%s"}
            """.formatted(TENANT, LEARNER, nodeId, UUID.randomUUID(), completedAt);
        return new OutboxMessage(UUID.randomUUID(), TENANT, "streaming.node.completed",
            "NodeCompleted", payload, null, Instant.now());
    }

    private java.util.List<String> states() {
        return jdbc.queryForList("SELECT state FROM node_completion WHERE node_id = ?",
            String.class, node);
    }
}
