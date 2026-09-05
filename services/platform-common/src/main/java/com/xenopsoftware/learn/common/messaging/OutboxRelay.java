package com.xenopsoftware.learn.common.messaging;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Drains the outbox into the broker (T-9.8).
 *
 * <p><b>Published, then marked — in that order, and the order is the at-least-once guarantee.</b>
 * Marking first would make a crash between the two steps lose the message permanently, with the
 * row saying it was sent. Publishing first means a crash re-sends it on the next pass, so the same
 * message can arrive twice and no committed change is ever lost. That is the trade the pattern
 * makes, and it is why every consumer must be idempotent ({@link ConsumedMessages}).
 *
 * <p><b>An advisory lock, not a row lock.</b> Two replicas draining at once would each publish the
 * same batch, doubling every message for no benefit — at-least-once is a floor to survive, not a
 * budget to spend. A Postgres session-level advisory lock is the cheapest correct answer: it costs
 * one round trip, it is released automatically if the holder dies (so a crashed replica does not
 * stall the relay until somebody notices), and it needs no table of its own to go stale.
 *
 * <p><b>The whole pass runs on ONE connection, and that is not an optimisation.</b> A session
 * advisory lock belongs to the connection that took it. A JdbcTemplate borrows a connection per
 * statement, so taking the lock and releasing it through one would use two different sessions:
 * the release would silently fail, and the lock would stay held by a pooled connection for as
 * long as it lived — stopping every future pass on every replica. Holding one connection for
 * the length of a pass is the price of a session lock, and it is why a pass is bounded by a
 * batch size.
 *
 * <p><b>Failure is retried, not swallowed.</b> A publish that throws leaves the row unmarked, so
 * the next pass tries it again — which is also why {@code MessagePublisher.publish} is specified
 * to throw. A publisher that quietly gave up would leave a table of rows marked sent that never
 * left the building.
 */
public class OutboxRelay {

    /**
     * The advisory lock this relay takes.
     *
     * <p>Per database, which is per module (T-9.9's one-database-per-module), so two services
     * relaying at the same time do not contend — they are draining different outboxes. The
     * constant is arbitrary and only has to be stable and unlikely to collide with another
     * feature that reaches for an advisory lock later.
     */
    private static final long LOCK_ID = 8_090_100L;

    private static final Logger LOG = LoggerFactory.getLogger(OutboxRelay.class);

    private final JdbcTemplate jdbc;
    private final MessagePublisher publisher;
    private final int batchSize;

    public OutboxRelay(DataSource dataSource, MessagePublisher publisher, int batchSize) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.publisher = publisher;
        this.batchSize = batchSize;
    }

    /**
     * One pass.
     *
     * <p>Deliberately NOT {@code @Transactional}. A transaction spanning the publish would hold a
     * database connection open for the length of a network call to the broker, and would roll the
     * marks back if the last message of a batch failed — re-sending the whole batch instead of the
     * one that did not make it.
     */
    @Scheduled(fixedDelayString = "${platform.outbox.interval:PT1S}")
    public void drain() {
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            if (!takeLock(connection)) {
                // Another replica is draining. Not a warning: this is the mechanism working.
                return null;
            }
            try {
                for (OutboxMessage message : unpublished(connection)) {
                    try {
                        // Under the ORIGINATING request's correlation id, so the publish and
                        // everything it causes downstream stay on one chain even though this runs
                        // on a scheduler thread minutes later.
                        Correlation.callWith(message.correlationId(), () -> {
                            publisher.publish(message);
                            return null;
                        });
                        markPublished(connection, message.id());
                    } catch (RuntimeException e) {
                        // Stop the batch rather than skipping ahead. Order within a subject is
                        // worth more than throughput: publishing message 4 after 3 failed would
                        // deliver "assignment revoked" before "assignment made".
                        LOG.warn("Outbox relay stopped at {} ({}); retrying next pass",
                            message.id(), e.toString());
                        return null;
                    }
                }
            } finally {
                releaseLock(connection);
            }
            return null;
        });
    }

    /** Oldest first, so order is preserved for anything that cares about it. */
    private List<OutboxMessage> unpublished(Connection connection) throws SQLException {
        List<OutboxMessage> batch = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, tenant_id, topic AS subject, type, payload::text AS payload, correlation_id,
                       occurred_at
                  FROM outbox
                 WHERE published_at IS NULL
                 ORDER BY occurred_at ASC, id ASC
                 LIMIT ?
                """)) {
            statement.setInt(1, batchSize);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    batch.add(new OutboxMessage(
                        rows.getObject("id", UUID.class),
                        rows.getString("tenant_id"),
                        rows.getString("subject"),
                        rows.getString("type"),
                        rows.getString("payload"),
                        rows.getString("correlation_id"),
                        rows.getTimestamp("occurred_at").toInstant()));
                }
            }
        }
        return batch;
    }

    private void markPublished(Connection connection, UUID id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE outbox SET published_at = ? WHERE id = ?")) {
            statement.setTimestamp(1, Timestamp.from(Instant.now()));
            statement.setObject(2, id);
            statement.executeUpdate();
        }
    }

    private boolean takeLock(Connection connection) throws SQLException {
        return lockFunction(connection, "pg_try_advisory_lock");
    }

    private void releaseLock(Connection connection) throws SQLException {
        lockFunction(connection, "pg_advisory_unlock");
    }

    private boolean lockFunction(Connection connection, String function) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + function + "(?)")) {
            statement.setLong(1, LOCK_ID);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    /**
     * How long the oldest unpublished row has been waiting, in seconds, and how many there are.
     *
     * <p>What the metric is built from (T-9.8's sixth criterion). <b>A stalled relay is otherwise
     * completely silent</b> — nothing errors, no request fails, and the first symptom is a report
     * that is a day behind or a gate that never opens. Row COUNT alone does not say it either: a
     * busy service always has rows in flight. Age does.
     */
    public Map<String, Number> backlog() {
        Map<String, Object> row = jdbc.queryForMap("""
            SELECT count(*) AS pending,
                   coalesce(extract(epoch FROM (now() - min(occurred_at))), 0) AS oldest_seconds
              FROM outbox WHERE published_at IS NULL
            """);
        return Map.of(
            "pending", ((Number) row.get("pending")).longValue(),
            "oldestSeconds", ((Number) row.get("oldest_seconds")).doubleValue());
    }
}
