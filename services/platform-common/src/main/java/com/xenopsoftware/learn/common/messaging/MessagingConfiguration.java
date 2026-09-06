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
 *
 * <h2>What is here and what is not (ADR-0109)</h2>
 *
 * Only the two beans that belong to a PROCESS: the broker connection and the publisher. The
 * outbox, its relay, its metrics and the subscriber all belong to a DATABASE, and since identity
 * and catalog share a process there are two of those. They used to be declared here, injecting
 * {@code DataSource} and {@code PlatformTransactionManager} by type — which resolved to exactly
 * one of each, correctly, right up until a second database appeared in the same JVM.
 *
 * <p>Each module now declares its own set through {@link ModuleMessaging}, which is where the
 * reasoning lives. Moving them was not a preference: a transactional outbox that writes into a
 * different database than the transaction it belongs to is not an outbox.
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

}
