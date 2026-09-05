package com.xenopsoftware.learn.catalog.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.xenopsoftware.learn.catalog.PostgresTestHarness;
import com.xenopsoftware.learn.common.messaging.MessagePublisher;
import com.xenopsoftware.learn.common.messaging.Outbox;
import com.xenopsoftware.learn.common.messaging.OutboxMessage;
import com.xenopsoftware.learn.common.messaging.OutboxRelay;
import com.xenopsoftware.learn.common.tenancy.TenantContext;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The outbox and its relay, without a broker (T-9.8).
 *
 * <p>No NATS here on purpose. What these assert is the half that must hold whatever the transport
 * does: the row commits with the change or not at all, the relay publishes before it marks, and a
 * publisher that fails leaves the row for the next pass. The broker's own behaviour is
 * {@code BusDeliveryTest}'s.
 */
@SpringBootTest
class OutboxRelayTest extends PostgresTestHarness {

    @Autowired
    private Outbox outbox;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private TransactionTemplate transactions;

    private JdbcTemplate jdbc;

    @BeforeEach
    void empty() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM outbox");
        jdbc.update("DELETE FROM consumed_message");
    }

    @Test
    void aRolledBackChangeAnnouncesNothing() {
        // THE reason the pattern exists. Publishing from application code would have fired an
        // event for a transaction that then rolled back -- a completion event for something that
        // never happened, which triggers a gate, a notification and a report entry for a fiction.
        try {
            // Tenant bound OUTSIDE the transaction: Hibernate fixes the tenant identifier when
            // the session opens, which is when the transaction starts.
            TenantContext.callWithUnchecked("acme", () -> {
                transactions.executeWithoutResult(status -> {
                    outbox.publish("catalog.test", "test.happened", "{\"a\":1}");
                    throw new IllegalStateException("the change failed after announcing it");
                });
                return null;
            });
        } catch (IllegalStateException expected) {
            // The transaction rolled back, which is the point.
        }

        assertThat(count()).isZero();
    }

    @Test
    void aCommittedChangeLeavesExactlyOneRowToPublish() {
        TenantContext.callWithUnchecked("acme", () -> {
            transactions.executeWithoutResult(status ->
                outbox.publish("catalog.test", "test.happened", "{\"a\":1}"));
            return null;
        });

        assertThat(count()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM outbox WHERE published_at IS NULL", Long.class)).isEqualTo(1);
    }

    @Test
    void theRelayPublishesThenMarksAndAFailedPublishLeavesTheRowForNextTime() {
        write("catalog.test", "one");
        AtomicBoolean failing = new AtomicBoolean(true);
        List<OutboxMessage> delivered = new CopyOnWriteArrayList<>();
        MessagePublisher flaky = message -> {
            if (failing.get()) {
                throw new IllegalStateException("broker unavailable");
            }
            delivered.add(message);
        };
        OutboxRelay relay = new OutboxRelay(dataSource, flaky, 100);

        relay.drain();

        assertThat(delivered).isEmpty();
        assertThat(unpublished())
            .as("a publisher that throws must leave the row: marking it would lose the message "
                + "permanently while the row said it was sent")
            .isEqualTo(1);

        failing.set(false);
        relay.drain();

        assertThat(delivered).hasSize(1);
        assertThat(unpublished()).isZero();
    }

    @Test
    void theRelayStopsAtTheFirstFailureRatherThanSkippingAhead() {
        write("catalog.test", "first");
        write("catalog.test", "second");
        List<OutboxMessage> delivered = new CopyOnWriteArrayList<>();
        MessagePublisher refusesTheFirst = message -> {
            if (delivered.isEmpty() && message.payload().contains("first")) {
                throw new IllegalStateException("not this one");
            }
            delivered.add(message);
        };

        new OutboxRelay(dataSource, refusesTheFirst, 100).drain();

        assertThat(delivered)
            .as("order within a subject is worth more than throughput: publishing the second "
                + "would deliver \"revoked\" before \"made\"")
            .isEmpty();
        assertThat(unpublished()).isEqualTo(2);
    }

    @Test
    void aSecondRelayDoesNothingWhileTheFirstHoldsTheLock() throws Exception {
        for (int i = 0; i < 5; i++) {
            write("catalog.test", "message " + i);
        }
        List<OutboxMessage> first = new CopyOnWriteArrayList<>();
        List<OutboxMessage> second = new CopyOnWriteArrayList<>();

        // The lock is session-level, so two relays on two connections is the real arrangement:
        // two replicas draining at once would each publish the whole batch, doubling every
        // message for no benefit. At-least-once is a floor to survive, not a budget to spend.
        OutboxRelay one = new OutboxRelay(dataSource, first::add, 100);
        OutboxRelay two = new OutboxRelay(dataSource, message -> {
            second.add(message);
            return;
        }, 100);

        Thread other = new Thread(two::drain);
        MessagePublisher slow = message -> {
            first.add(message);
            if (first.size() == 1) {
                other.start();
                try {
                    other.join(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        new OutboxRelay(dataSource, slow, 100).drain();

        assertThat(first).hasSize(5);
        assertThat(second)
            .as("the second relay found the lock taken and published nothing")
            .isEmpty();
        assertThat(one).isNotNull();
    }

    @Test
    void everyRowCarriesTheCorrelationIdOfTheRequestThatCausedIt() {
        com.xenopsoftware.learn.common.messaging.Correlation.callWith("req-42", () ->
            TenantContext.callWithUnchecked("acme", () -> {
                transactions.executeWithoutResult(status ->
                    outbox.publish("catalog.test", "test.happened", "{}"));
                return null;
            }));

        assertThat(jdbc.queryForObject(
            "SELECT correlation_id FROM outbox", String.class))
            .as("one id spans a click and everything it caused, or tracing a consequence back to "
                + "its cause means matching timestamps across four logs")
            .isEqualTo("req-42");
    }

    @Test
    void theBacklogMetricMeasuresAgeRatherThanCount() {
        write("catalog.test", "waiting");
        jdbc.update("UPDATE outbox SET occurred_at = now() - interval '5 minutes'");

        var backlog = new OutboxRelay(dataSource, message -> { }, 100).backlog();

        // A stalled relay is otherwise completely silent: nothing errors, no request fails, and
        // the first symptom is a report a day behind. Count alone would not say it -- a busy
        // service always has rows in flight.
        assertThat(backlog.get("pending").longValue()).isEqualTo(1);
        assertThat(backlog.get("oldestSeconds").doubleValue()).isGreaterThan(250);
    }

    private void write(String subject, String note) {
        TenantContext.callWithUnchecked("acme", () -> {
            transactions.executeWithoutResult(status ->
                outbox.publish(subject, "test.happened", "{\"note\":\"" + note + "\"}"));
            return null;
        });
    }

    private long count() {
        return jdbc.queryForObject("SELECT count(*) FROM outbox", Long.class);
    }

    private long unpublished() {
        return jdbc.queryForObject("SELECT count(*) FROM outbox WHERE published_at IS NULL",
            Long.class);
    }
}
