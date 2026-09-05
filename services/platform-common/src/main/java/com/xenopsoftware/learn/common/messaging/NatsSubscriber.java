package com.xenopsoftware.learn.common.messaging;

import com.xenopsoftware.learn.common.tenancy.TenantContext;
import io.nats.client.Connection;
import io.nats.client.JetStreamSubscription;
import io.nats.client.Message;
import io.nats.client.PullSubscribeOptions;
import io.nats.client.api.AckPolicy;
import io.nats.client.api.ConsumerConfiguration;
import io.nats.client.api.DeliverPolicy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Delivers messages to {@link MessageHandler}s, once each (T-9.8).
 *
 * <p><b>Pull, not push.</b> A push consumer delivers at the broker's pace into a callback thread,
 * which means back-pressure is the consumer's problem and a slow handler builds an invisible queue
 * inside the process. Pulling a bounded batch on a schedule makes the rate ours, makes the backlog
 * visible in the broker rather than in a heap, and makes the whole thing behave like the relay it
 * sits opposite.
 *
 * <p><b>Durable consumers, named for the handler.</b> An ephemeral consumer starts from wherever
 * the stream happens to be, so a service restarting after a deploy silently skips everything
 * published while it was down. A durable one remembers its position on the server.
 *
 * <p><b>The claim and the effect share one transaction.</b> Marking a message consumed in its own
 * transaction would let a handler fail afterwards and never be retried — a message recorded as
 * handled that did nothing, which is worse than handling it twice. Here, throwing rolls back both
 * and the message is redelivered.
 */
public class NatsSubscriber {

    private static final Logger LOG = LoggerFactory.getLogger(NatsSubscriber.class);

    private final Connection connection;
    private final List<MessageHandler> handlers;
    private final ConsumedMessages consumed;
    private final TransactionTemplate transactions;
    private final String serviceName;
    private final int batchSize;
    private final List<JetStreamSubscription> subscriptions = new java.util.ArrayList<>();

    public NatsSubscriber(Connection connection, List<MessageHandler> handlers,
            ConsumedMessages consumed, TransactionTemplate transactions, String serviceName,
            int batchSize) {
        this.connection = connection;
        this.handlers = handlers;
        this.consumed = consumed;
        this.transactions = transactions;
        this.serviceName = serviceName;
        this.batchSize = batchSize;
    }

    /**
     * Binds one durable consumer per handler.
     *
     * <p>Named {@code <service>-<handler>}: two services consuming the same subject need separate
     * positions, and one service with two handlers on one subject needs two as well — sharing a
     * durable name would make them compete for messages rather than each seeing all of them.
     */
    public void subscribe() {
        for (MessageHandler handler : handlers) {
            String durable = (serviceName + "-" + handler.getClass().getSimpleName())
                .toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9-]", "-");
            try {
                subscriptions.add(connection.jetStream().subscribe(handler.subject(),
                    PullSubscribeOptions.builder()
                        .durable(durable)
                        .configuration(ConsumerConfiguration.builder()
                            .ackPolicy(AckPolicy.Explicit)
                            // From the beginning of the stream for a brand new consumer, so a
                            // service deployed after an event was published still sees it. All
                            // is safe precisely because handlers are idempotent.
                            .deliverPolicy(DeliverPolicy.All)
                            .ackWait(Duration.ofSeconds(30))
                            .build())
                        .build()));
                LOG.info("Subscribed {} to {}", durable, handler.subject());
            } catch (Exception e) {
                throw new IllegalStateException(
                    "Could not subscribe " + durable + " to " + handler.subject(), e);
            }
        }
    }

    /** One pull per handler, per tick. */
    @Scheduled(fixedDelayString = "${platform.messaging.poll-interval:PT1S}")
    public void poll() {
        for (int i = 0; i < subscriptions.size(); i++) {
            MessageHandler handler = handlers.get(i);
            for (Message message : subscriptions.get(i).fetch(batchSize, Duration.ofMillis(200))) {
                deliver(handler, message);
            }
        }
    }

    private void deliver(MessageHandler handler, Message message) {
        OutboxMessage event = read(message);
        try {
            // THE TENANT IS BOUND OUTSIDE THE TRANSACTION, and the order is not cosmetic.
            // Hibernate fixes the tenant identifier when the session opens, which is when the
            // transaction STARTS -- so binding it inside would leave the resolver with nothing
            // and fail every delivery with "Could not open JPA EntityManager for transaction".
            TenantContext.callWithUnchecked(event.tenantId(), () ->
                Correlation.callWith(event.correlationId(), () -> {
                    transactions.executeWithoutResult(status -> {
                        // Claim FIRST: the insert and the primary key decide, so two deliveries
                        // racing cannot both do the work. Checking-then-handling has a window
                        // where both find nothing.
                        if (consumed.claim(event.id(), message.getSubject())) {
                            handler.handle(event);
                        }
                    });
                    return null;
                }));
            message.ack();
        } catch (RuntimeException e) {
            // Not acked, so the broker redelivers after ackWait. A handler that fails on a
            // transient problem gets another go; one that fails permanently keeps failing
            // visibly, which is the right kind of loud.
            LOG.warn("Handler {} failed on {}; leaving it unacked for redelivery",
                handler.getClass().getSimpleName(), event.id(), e);
        }
    }

    private static OutboxMessage read(Message message) {
        io.nats.client.impl.Headers headers = message.getHeaders();
        String id = header(headers, "Nats-Msg-Id");
        return new OutboxMessage(
            id == null ? UUID.randomUUID() : UUID.fromString(id),
            header(headers, NatsPublisher.TENANT_HEADER),
            message.getSubject(),
            header(headers, NatsPublisher.TYPE_HEADER),
            new String(message.getData(), StandardCharsets.UTF_8),
            header(headers, Correlation.HEADER),
            Instant.now());
    }

    private static String header(io.nats.client.impl.Headers headers, String name) {
        return headers == null ? null : headers.getFirst(name);
    }
}
