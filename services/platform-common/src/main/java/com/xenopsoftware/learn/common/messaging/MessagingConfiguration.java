package com.xenopsoftware.learn.common.messaging;

import io.micrometer.core.instrument.MeterRegistry;
import io.nats.client.Connection;
import io.nats.client.Nats;
import io.nats.client.Options;
import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wires the bus, or wires the honest absence of one (T-9.8).
 *
 * <p>Gated on {@code platform.outbox.enabled} so a service that neither publishes nor consumes
 * pays for none of it — no relay, no scheduled task, no connection. The four services that do turn
 * it on with one property and a migration.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "platform.outbox.enabled", havingValue = "true")
@EnableScheduling
public class MessagingConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(MessagingConfiguration.class);

    /**
     * The broker connection, or nothing.
     *
     * <p>Returns null rather than failing when no URL is configured, which is what lets the local
     * stack run without NATS. A service that cannot reach a CONFIGURED broker is a different
     * matter and does fail: silently degrading to "delivered nowhere" in an environment that
     * expects delivery is the failure this whole task exists to prevent.
     */
    @Bean(destroyMethod = "close")
    Connection natsConnection(@Value("${platform.messaging.nats-url:}") String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            Connection connection = Nats.connect(Options.builder()
                .server(url)
                .connectionTimeout(Duration.ofSeconds(5))
                // Reconnect forever: a broker restart must not need a service restart.
                .maxReconnects(-1)
                .reconnectWait(Duration.ofSeconds(1))
                .build());
            Streams.apply(connection.jetStreamManagement());
            return connection;
        } catch (Exception e) {
            throw new IllegalStateException(
                "Could not connect to the message bus at " + url + ". Unset "
                + "platform.messaging.nats-url to run without one deliberately.", e);
        }
    }

    @Bean
    MessagePublisher messagePublisher(ObjectProvider<Connection> connection) {
        Connection nats = connection.getIfAvailable();
        return nats == null ? new RecordingPublisher() : new NatsPublisher(nats);
    }

    @Bean
    OutboxRelay outboxRelay(DataSource dataSource, MessagePublisher publisher,
            @Value("${platform.outbox.batch-size:100}") int batchSize) {
        return new OutboxRelay(dataSource, publisher, batchSize);
    }

    /**
     * The backlog metrics (T-9.8's sixth criterion).
     *
     * <p>Two gauges, and the age is the one that matters. A stalled relay produces no error, fails
     * no request, and shows up first as a report a day behind or a gate that never opens. Row COUNT
     * alone would not say it either — a busy service always has rows in flight. <b>Age does.</b>
     *
     * <p>The alert belongs with the rule: {@code platform_outbox_oldest_seconds > 60} for five
     * minutes means the relay is not draining, whatever the count says.
     */
    @Bean
    OutboxMetrics outboxMetrics(OutboxRelay relay, MeterRegistry meters) {
        return new OutboxMetrics(relay, meters);
    }

    @Bean
    NatsSubscriber natsSubscriber(ObjectProvider<Connection> connection,
            List<MessageHandler> handlers, ConsumedMessages consumed,
            PlatformTransactionManager transactionManager,
            @Value("${spring.application.name:service}") String serviceName,
            @Value("${platform.messaging.batch-size:50}") int batchSize) {
        Connection nats = connection.getIfAvailable();
        if (nats == null || handlers.isEmpty()) {
            if (!handlers.isEmpty()) {
                LOG.warn("{} message handler(s) registered but no broker is configured; nothing "
                    + "will ever be delivered to them.", handlers.size());
            }
            return null;
        }
        NatsSubscriber subscriber = new NatsSubscriber(nats, handlers, consumed,
            new TransactionTemplate(transactionManager), serviceName, batchSize);
        subscriber.subscribe();
        return subscriber;
    }
}
