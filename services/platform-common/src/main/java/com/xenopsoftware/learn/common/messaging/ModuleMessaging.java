package com.xenopsoftware.learn.common.messaging;

import io.micrometer.core.instrument.MeterRegistry;
import io.nats.client.Connection;
import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The half of the bus that belongs to a database rather than to a process (T-9.8, ADR-0109).
 *
 * <h2>Why this class exists</h2>
 *
 * The outbox is a <b>transactional</b> outbox, and that is not a detail of the implementation —
 * it is the entire guarantee. A row saying "this happened" is written in the same transaction as
 * the thing that happened, so the two commit together or neither does. There is no window where
 * the domain changed and the event was lost, and none where the event was published and the
 * change rolled back.
 *
 * <p>That guarantee is only available <b>inside one database</b>. So when ADR-0109 put identity
 * and catalog in one process while keeping their databases separate, it also put two outboxes in
 * one JVM: catalog's events must be written by catalog's transaction manager into catalog's
 * database, and identity's into identity's. One shared outbox would have silently traded the
 * guarantee for a table.
 *
 * <p>{@code MessagingConfiguration} therefore keeps only what is genuinely per-process — the
 * broker connection and the publisher — and each module declares its own set through the factories
 * here. Four short {@code @Bean} methods per module, with the reasoning in one place instead of
 * four.
 *
 * <h2>What a module has to get right</h2>
 *
 * The {@code module} argument is the name its meters and its durable consumers are tagged with.
 * Two modules passing the same string is the failure this parameter exists to prevent, and it is a
 * quiet one: Micrometer keys a meter on name plus tags, so the second registration silently
 * returns the first module's gauge and the second module's backlog is never published.
 */
public final class ModuleMessaging {

    private static final Logger LOG = LoggerFactory.getLogger(ModuleMessaging.class);

    private ModuleMessaging() {}

    /** Where this module writes its events, in this module's own transaction. */
    public static Outbox outbox(DataSource dataSource) {
        return new Outbox(dataSource);
    }

    /** What drains them onto the bus, at least once, in order. */
    public static OutboxRelay relay(DataSource dataSource, MessagePublisher publisher, int batchSize) {
        return new OutboxRelay(dataSource, publisher, batchSize);
    }

    /** The backlog gauges, tagged so two modules in one process stay distinguishable. */
    public static OutboxMetrics metrics(OutboxRelay relay, MeterRegistry meters, String module) {
        return new OutboxMetrics(relay, meters, module);
    }

    /** This module's record of what it has already handled, in this module's own database. */
    public static ConsumedMessages consumed(DataSource dataSource) {
        return new ConsumedMessages(dataSource);
    }

    /**
     * This module's subscriptions, or null when there is nothing to subscribe to.
     *
     * <p>Null rather than an empty object because {@code @Scheduled} on a live subscriber that
     * polls nothing is a thread doing nothing forever, and because a module with handlers and no
     * broker is a misconfiguration worth a warning rather than a silence.
     *
     * @param module names the durable consumers, so identity's and catalog's do not compete for
     *     the same messages when they share a process
     */
    public static NatsSubscriber subscriber(Connection nats, List<MessageHandler> handlers,
            ConsumedMessages consumed, PlatformTransactionManager transactionManager,
            String module, int batchSize) {
        if (nats == null || handlers.isEmpty()) {
            if (!handlers.isEmpty()) {
                LOG.warn("{} message handler(s) registered in {} but no broker is configured; "
                    + "nothing will ever be delivered to them.", handlers.size(), module);
            }
            return null;
        }
        NatsSubscriber subscriber = new NatsSubscriber(nats, handlers, consumed,
            new TransactionTemplate(transactionManager), module, batchSize);
        subscriber.subscribe();
        return subscriber;
    }
}
