package com.xenopsoftware.learn.common.messaging;

import io.nats.client.Connection;
import io.nats.client.impl.Headers;
import io.nats.client.impl.NatsMessage;
import java.nio.charset.StandardCharsets;

/**
 * Publishes to NATS JetStream (T-9.8).
 *
 * <p><b>{@code Nats-Msg-Id} carries the outbox row's id</b>, which is what lets the broker suppress
 * the most common duplicate for free: a relay that published and then died re-sends the same id
 * inside the stream's duplicate window, and JetStream drops it. That is an optimisation and not
 * the guarantee — the window is finite, so {@link ConsumedMessages} is still what makes a consumer
 * correct.
 *
 * <p>The tenant and the correlation id travel as headers rather than inside the body, so a consumer
 * can bind both before parsing anything, and so a payload schema change cannot lose them.
 */
public class NatsPublisher implements MessagePublisher {

    /** Standard JetStream header. Named by NATS, not by us. */
    private static final String MESSAGE_ID = "Nats-Msg-Id";

    static final String TENANT_HEADER = "X-Tenant-Id";
    static final String TYPE_HEADER = "X-Event-Type";

    private final Connection connection;

    public NatsPublisher(Connection connection) {
        this.connection = connection;
    }

    @Override
    public void publish(OutboxMessage message) {
        Headers headers = new Headers()
            .add(MESSAGE_ID, message.id().toString())
            .add(TENANT_HEADER, message.tenantId())
            .add(TYPE_HEADER, message.type())
            .add(Correlation.HEADER, message.correlationId());
        // THE CONNECTION IS CHECKED FIRST, and this is not belt-and-braces. Publishing through a
        // closed connection was found NOT to throw: the relay marked the row published and the
        // message never left the building -- exactly the failure the outbox exists to prevent,
        // arriving through the one path that was trusted not to produce it. The contract on
        // MessagePublisher.publish is that it throws when it cannot deliver, so it is enforced
        // here rather than assumed of the client.
        if (connection.getStatus() != Connection.Status.CONNECTED) {
            throw new IllegalStateException("Not connected to the broker (" + connection.getStatus()
                + "); leaving " + message.id() + " in the outbox for the next pass");
        }
        try {
            // The ACKNOWLEDGED publish, not the fire-and-forget one. jetStream().publish blocks
            // until the server confirms it stored the message; connection.publish would return
            // immediately and the relay would mark rows published that the broker never kept.
            connection.jetStream().publish(NatsMessage.builder()
                .subject(message.subject())
                .headers(headers)
                .data(message.payload(), StandardCharsets.UTF_8)
                .build());
        } catch (Exception e) {
            // Wrapped and rethrown, never swallowed: the relay retries what throws and marks what
            // does not, so a publisher that hid a failure would produce a table of lies.
            throw new IllegalStateException(
                "Could not publish " + message.id() + " to " + message.subject(), e);
        }
    }
}
