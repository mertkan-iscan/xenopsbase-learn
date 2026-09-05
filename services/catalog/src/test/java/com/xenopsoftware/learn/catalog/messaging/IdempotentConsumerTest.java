package com.xenopsoftware.learn.catalog.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.xenopsoftware.learn.catalog.PostgresTestHarness;
import com.xenopsoftware.learn.catalog.assign.GroupReachHandler;
import com.xenopsoftware.learn.common.messaging.ConsumedMessages;
import com.xenopsoftware.learn.common.messaging.OutboxMessage;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * T-9.8's fifth criterion: <b>the same message delivered twice, and one effect.</b>
 *
 * <p>Duplicate delivery is not an edge case here — the relay publishes and then marks, so a crash
 * between the two re-sends by design. Over a long enough period every consumer WILL see a message
 * twice, which makes idempotency a requirement on every consumer this platform will ever have
 * rather than a property of the bus.
 *
 * <p>Delivered through the same path the subscriber uses — claim, then handle, in one transaction —
 * rather than by calling the handler twice, because the claim is the half that makes it true.
 */
@SpringBootTest
class IdempotentConsumerTest extends PostgresTestHarness {

    private static final UUID LEARNER = UUID.randomUUID();

    @Autowired
    private GroupReachHandler handler;

    @Autowired
    private ConsumedMessages consumed;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    @BeforeEach
    void empty() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM consumed_message");
        jdbc.update("DELETE FROM learner_group_reach");
    }

    @Test
    void thesameMessageTwiceHasOneEffect() {
        UUID group = UUID.randomUUID();
        OutboxMessage message = reachMessage(UUID.randomUUID(), group);

        deliver(message);
        deliver(message);

        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM learner_group_reach WHERE learner_id = ?", Long.class, LEARNER))
            .as("one group, not two rows -- the relay re-sends by design and this is what survives it")
            .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM consumed_message", Long.class))
            .as("and the second delivery was recognised rather than merely harmless")
            .isEqualTo(1);
    }

    @Test
    void aSecondDeliveryIsRefusedByTheDatabaseRatherThanByALookup() {
        UUID messageId = UUID.randomUUID();

        boolean first = com.xenopsoftware.learn.common.tenancy.TenantContext.callWithUnchecked(
            "acme", () -> transactions.execute(status -> consumed.claim(messageId, "identity.group.reach")));
        boolean second = com.xenopsoftware.learn.common.tenancy.TenantContext.callWithUnchecked(
            "acme", () -> transactions.execute(status -> consumed.claim(messageId, "identity.group.reach")));

        // Checking "have I seen this" and then handling has a window where two deliveries both
        // find nothing and both handle. Inserting first and letting the primary key arbitrate has
        // no window, because the database decides.
        assertThat(first).isTrue();
        assertThat(second).isFalse();
    }

    @Test
    void aHandlerThatFailsIsNotRecordedAsHandled() {
        UUID messageId = UUID.randomUUID();

        try {
            com.xenopsoftware.learn.common.tenancy.TenantContext.callWithUnchecked("acme", () -> {
                transactions.executeWithoutResult(status -> {
                    consumed.claim(messageId, "identity.group.reach");
                    throw new IllegalStateException("the handler failed after being claimed");
                });
                return null;
            });
        } catch (IllegalStateException expected) {
            // The claim and the effect share one transaction, so both rolled back.
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM consumed_message", Long.class))
            .as("a message recorded as handled that did nothing is worse than handling it twice: "
                + "it would never be retried")
            .isZero();
    }

    @Test
    void aLaterMessageReplacesTheReachRatherThanMergingWithIt() {
        UUID engineering = UUID.randomUUID();
        UUID platform = UUID.randomUUID();
        deliver(reachMessage(UUID.randomUUID(), engineering, platform));
        assertThat(reachCount()).isEqualTo(2);

        // They leave the platform team. The event carries the WHOLE set, so applying it is
        // "make the rows equal this".
        deliver(reachMessage(UUID.randomUUID(), engineering));

        assertThat(reachCount())
            .as("merging would leave the old row behind forever, quietly assigning them work "
                + "from a department they are no longer in")
            .isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT group_id FROM learner_group_reach WHERE learner_id = ?", UUID.class, LEARNER))
            .isEqualTo(engineering);
    }

    @Test
    void anEmptyReachSetRemovesEveryRow() {
        deliver(reachMessage(UUID.randomUUID(), UUID.randomUUID()));
        assertThat(reachCount()).isEqualTo(1);

        deliver(reachMessage(UUID.randomUUID()));

        assertThat(reachCount())
            .as("somebody removed from every group is reached by nothing, which a delta event "
                + "could not express without a special case")
            .isZero();
    }

    /** The subscriber's path: claim, then handle, in one transaction. */
    private void deliver(OutboxMessage message) {
        // Exactly the subscriber's order: tenant bound before the transaction opens, because
        // Hibernate fixes the tenant identifier when the session does.
        com.xenopsoftware.learn.common.tenancy.TenantContext.callWithUnchecked(message.tenantId(),
            () -> {
                transactions.executeWithoutResult(status -> {
                    if (consumed.claim(message.id(), message.subject())) {
                        handler.handle(message);
                    }
                });
                return null;
            });
    }

    private long reachCount() {
        return jdbc.queryForObject("SELECT count(*) FROM learner_group_reach WHERE learner_id = ?",
            Long.class, LEARNER);
    }

    private static OutboxMessage reachMessage(UUID messageId, UUID... groups) {
        String ids = java.util.Arrays.stream(groups)
            .map(group -> "\"" + group + "\"")
            .collect(java.util.stream.Collectors.joining(","));
        return new OutboxMessage(messageId, "acme", "identity.group.reach", "group.reach.changed",
            "{\"tenantId\":\"acme\",\"learnerId\":\"" + LEARNER + "\",\"groupIds\":[" + ids + "]}",
            "req-1", Instant.now());
    }
}
