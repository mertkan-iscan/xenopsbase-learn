package com.xenopsoftware.learn.catalog.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.xenopsoftware.learn.catalog.PostgresTestHarness;
import com.xenopsoftware.learn.common.messaging.NatsPublisher;
import com.xenopsoftware.learn.common.messaging.OutboxMessage;
import com.xenopsoftware.learn.common.messaging.OutboxRelay;
import com.xenopsoftware.learn.common.messaging.Streams;
import io.nats.client.Connection;
import io.nats.client.JetStreamSubscription;
import io.nats.client.Message;
import io.nats.client.Nats;
import io.nats.client.PullSubscribeOptions;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * The bus itself, against a real broker (T-9.8's first and last criteria).
 *
 * <p>The one that matters most is {@link #wipingTheBrokerLosesNoCommittedChange}: <b>the broker's
 * storage is destroyed mid-flight and nothing committed is lost</b>, because the outbox row is the
 * record and the broker is transport. A design where the broker held the only copy would fail that
 * test by construction, and would fail it in production as a day of missing events that nobody can
 * reconstruct.
 *
 * <p>A real NATS rather than a fake, for the reason {@code PostgresTestHarness} gives about
 * Postgres: what is being tested here is JetStream's actual behaviour — that a stream declared in
 * code exists, that an acknowledged publish is stored, that a wiped server forgets. A fake that
 * agreed with our assumptions would test the assumptions.
 */
@SpringBootTest
class BusDeliveryTest extends PostgresTestHarness {

    /** Matches docker-compose.yml's pin -- the drift T-9.10 still owes a single source for. */
    private static final GenericContainer<?> NATS = new GenericContainer<>("nats:2.12-alpine")
        .withCommand("-js", "-sd", "/data", "-m", "8222")
        .withExposedPorts(4222, 8222)
        .waitingFor(Wait.forHttp("/healthz").forPort(8222).forStatusCode(200));

    static {
        NATS.start();
    }

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;
    private Connection nats;

    @BeforeEach
    void connect() throws Exception {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM outbox");
        nats = Nats.connect("nats://" + NATS.getHost() + ":" + NATS.getMappedPort(4222));
        // The topology, declared in code -- criterion one. A stream created by hand on a laptop
        // exists in exactly one environment, and the first anybody knows is a consumer elsewhere
        // that receives nothing, silently, because a subject with no stream is not an error.
        Streams.apply(nats.jetStreamManagement());
        // The broker outlives a test method: a durable consumer starts at the beginning of the
        // stream, so without this a test counting arrivals counts the previous test's too.
        nats.jetStreamManagement().purgeStream("catalog");
    }

    @AfterEach
    void disconnect() throws Exception {
        if (nats != null) {
            nats.close();
        }
    }

    @Test
    void theTopologyIsDeclaredInCodeAndApplyingItTwiceIsApplyingItOnce() throws Exception {
        Streams.apply(nats.jetStreamManagement());

        assertThat(nats.jetStreamManagement().getStreamNames())
            .contains("identity", "catalog", "streaming", "reporting");
    }

    @Test
    void aRelayedMessageArrivesWithItsTenantTypeAndCorrelation() throws Exception {
        JetStreamSubscription subscription = subscribe("catalog-arrival");
        UUID id = writeOutboxRow("catalog.test.happened", "req-99");

        new OutboxRelay(dataSource, new NatsPublisher(nats), 100).drain();

        List<Message> received = subscription.fetch(10, Duration.ofSeconds(5));
        assertThat(received).hasSize(1);
        Message message = received.getFirst();
        assertThat(message.getHeaders().getFirst("Nats-Msg-Id")).isEqualTo(id.toString());
        assertThat(message.getHeaders().getFirst("X-Tenant-Id")).isEqualTo("acme");
        assertThat(message.getHeaders().getFirst("X-Correlation-Id"))
            .as("one id spans the click and everything it caused, across the broker")
            .isEqualTo("req-99");
        assertThat(unpublished()).isZero();
    }

    @Test
    void republishingTheSameRowIsSuppressedByTheBroker() throws Exception {
        JetStreamSubscription subscription = subscribe("catalog-dedupe");
        UUID id = writeOutboxRow("catalog.test.happened", "req-1");
        NatsPublisher publisher = new NatsPublisher(nats);
        OutboxMessage message = new OutboxMessage(id, "acme", "catalog.test.happened",
            "test.happened", "{}", "req-1", Instant.now());

        publisher.publish(message);
        // Exactly what a relay that published and then died does on its next pass.
        publisher.publish(message);

        assertThat(subscription.fetch(10, Duration.ofSeconds(3)))
            .as("the stream's duplicate window catches the common case by message id -- an "
                + "optimisation, not the guarantee, because the window is finite")
            .hasSize(1);
    }

    @Test
    void wipingTheBrokerLosesNoCommittedChange() throws Exception {
        // Three committed changes, announced. The first is relayed; then the broker's storage is
        // destroyed with two still in the outbox and one already delivered.
        writeOutboxRow("catalog.test.happened", "req-1");
        writeOutboxRow("catalog.test.happened", "req-2");
        writeOutboxRow("catalog.test.happened", "req-3");
        assertThat(unpublished()).isEqualTo(3);

        new OutboxRelay(dataSource, new NatsPublisher(nats), 1).drain();
        assertThat(unpublished()).isEqualTo(2);

        // The broker forgets everything: streams, messages, consumer positions.
        nats.jetStreamManagement().deleteStream("catalog");
        Streams.apply(nats.jetStreamManagement());

        new OutboxRelay(dataSource, new NatsPublisher(nats), 100).drain();

        assertThat(unpublished())
            .as("every committed change still reached transport, because the ROW is the record "
                + "and the broker is only transport")
            .isZero();
        JetStreamSubscription subscription = subscribe("catalog-after-wipe");
        assertThat(subscription.fetch(10, Duration.ofSeconds(3)))
            .as("the two that had not been relayed survived the wipe and arrived afterwards")
            .hasSize(2);
    }

    @Test
    void aBrokerThatIsGoneLeavesTheRowsAloneRatherThanMarkingThemSent() throws Exception {
        writeOutboxRow("catalog.test.happened", "req-1");
        Connection closed = Nats.connect("nats://" + NATS.getHost() + ":" + NATS.getMappedPort(4222));
        closed.close();

        new OutboxRelay(dataSource, new NatsPublisher(closed), 100).drain();

        assertThat(unpublished())
            .as("a publisher that swallowed the failure would leave a table of rows marked sent "
                + "that never left the building")
            .isEqualTo(1);
    }

    private JetStreamSubscription subscribe(String durable) throws Exception {
        return nats.jetStream().subscribe("catalog.>",
            PullSubscribeOptions.builder().durable(durable).build());
    }

    private UUID writeOutboxRow(String subject, String correlationId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO outbox (id, tenant_id, topic, type, payload, correlation_id, occurred_at)
            VALUES (?, 'acme', ?, 'test.happened', '{}'::jsonb, ?, now())
            """, id, subject, correlationId);
        return id;
    }

    private long unpublished() {
        return jdbc.queryForObject("SELECT count(*) FROM outbox WHERE published_at IS NULL",
            Long.class);
    }
}
