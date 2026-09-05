package com.xenopsoftware.learn.common.messaging;

import com.xenopsoftware.learn.common.tenancy.TenantContext;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Writes what happened, in the transaction that made it happen (T-9.8).
 *
 * <p><b>This is the whole point of the pattern, in one sentence: the row and the change commit
 * together or neither does.</b> A service that publishes to a broker from application code is
 * doing two things to two systems, and every ordering of those two has a window. Publish first and
 * the transaction can roll back, leaving a completion event for something that never happened —
 * which triggers a gate, a notification and a report entry for a fiction. Publish after and the
 * process can die in between, losing the event for a change that is now permanent.
 *
 * <p>An INSERT into the same database in the same transaction has no window. The broker becomes
 * transport, and transport is allowed to lose things because the row is the record.
 *
 * <p><b>What this costs, stated rather than discovered: delivery is at-least-once.</b> The relay
 * can publish and then fail before marking the row, so the same message arrives twice. That is a
 * requirement on every consumer this platform will ever have, not a property of the bus —
 * {@link ConsumedMessages} is how a consumer meets it.
 *
 * <p>JdbcTemplate rather than an entity: it joins whatever transaction is active without needing a
 * Hibernate session, which matters because publishers include code that runs on a startup thread
 * with no tenant bound, and because {@code platform-common} keeps Hibernate optional.
 */
@Component
public class Outbox {

    private final JdbcTemplate jdbc;

    public Outbox(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /**
     * Records an event for publication. Call it inside the transaction that made the change.
     *
     * @return the message as written, whose id is what a consumer dedupes on
     */
    public OutboxMessage publish(String subject, String type, String payloadJson) {
        return publish(TenantContext.require(), subject, type, payloadJson);
    }

    /**
     * The same, for the callers that know their tenant without being bound to it — cross-tenant
     * work like provisioning, and anything on a startup thread.
     */
    public OutboxMessage publish(String tenantId, String subject, String type, String payloadJson) {
        OutboxMessage message = new OutboxMessage(UUID.randomUUID(), tenantId, subject, type,
            payloadJson, Correlation.current(), Instant.now());
        jdbc.update("""
            INSERT INTO outbox (id, tenant_id, topic, type, payload, correlation_id, occurred_at)
            VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)
            """, message.id(), message.tenantId(), message.subject(), message.type(),
            message.payload(), message.correlationId(), java.sql.Timestamp.from(message.occurredAt()));
        return message;
    }
}
