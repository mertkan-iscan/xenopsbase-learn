package com.xenopsoftware.learn.identity.config;

import com.xenopsoftware.learn.common.messaging.ConsumedMessages;
import com.xenopsoftware.learn.common.messaging.MessageHandler;
import com.xenopsoftware.learn.common.messaging.MessagePublisher;
import com.xenopsoftware.learn.common.messaging.ModuleMessaging;
import com.xenopsoftware.learn.common.messaging.NatsSubscriber;
import com.xenopsoftware.learn.common.messaging.Outbox;
import com.xenopsoftware.learn.common.messaging.OutboxMetrics;
import com.xenopsoftware.learn.common.messaging.OutboxRelay;
import io.micrometer.core.instrument.MeterRegistry;
import io.nats.client.Connection;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * This module's own outbox, relay and subscriptions (T-9.8, ADR-0109).
 *
 * <p>These four beans used to be declared once in {@code MessagingConfiguration}, injecting
 * {@code DataSource} and {@code PlatformTransactionManager} by type. That was right while every
 * module was its own process, and stopped being right when ADR-0109 put identity and catalog in
 * one JVM with two databases: a transactional outbox has to write into the same database as the
 * transaction it belongs to, so there are two of everything below and no way to pick one by type.
 *
 * <p>{@link ModuleMessaging} carries the full reasoning. What is here is only the wiring: which
 * database, which transaction manager, and which name the meters and durable consumers carry.
 *
 * <p>The DataSource is resolved by name with a fallback, exactly as this module's persistence
 * configuration does it — the named bean exists when running inside {@code core}, and does not
 * when this module runs alone or under its own tests. One code path, no profile, no condition.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "platform.outbox.enabled", havingValue = "true")
public class IdentityMessagingConfiguration {

    /** Tags this module's meters and names its durable consumers. Unique per module, by rule. */
    private static final String MODULE = "identity";

    @Bean
    Outbox identityOutbox(@Qualifier("identityDataSource") ObjectProvider<DataSource> named,
            ObjectProvider<DataSource> theOnlyDataSource) {
        return ModuleMessaging.outbox(named.getIfAvailable(theOnlyDataSource::getObject));
    }

    @Bean
    OutboxRelay identityOutboxRelay(@Qualifier("identityDataSource") ObjectProvider<DataSource> named,
            ObjectProvider<DataSource> theOnlyDataSource, MessagePublisher publisher,
            @Value("${platform.outbox.batch-size:100}") int batchSize) {
        return ModuleMessaging.relay(
            named.getIfAvailable(theOnlyDataSource::getObject), publisher, batchSize);
    }

    @Bean
    OutboxMetrics identityOutboxMetrics(
            @Qualifier("identityOutboxRelay") OutboxRelay relay, MeterRegistry meters) {
        return ModuleMessaging.metrics(relay, meters, MODULE);
    }

    @Bean
    ConsumedMessages identityConsumedMessages(@Qualifier("identityDataSource") ObjectProvider<DataSource> named,
            ObjectProvider<DataSource> theOnlyDataSource) {
        return ModuleMessaging.consumed(named.getIfAvailable(theOnlyDataSource::getObject));
    }

    @Bean
    NatsSubscriber identityNatsSubscriber(ObjectProvider<Connection> connection,
            List<MessageHandler> handlers,
            @Qualifier("identityConsumedMessages") ConsumedMessages consumed,
            @Qualifier("identityTransactionManager") ObjectProvider<PlatformTransactionManager> named,
            ObjectProvider<PlatformTransactionManager> theOnlyTransactionManager,
            @Value("${platform.messaging.batch-size:50}") int batchSize) {
        return ModuleMessaging.subscriber(connection.getIfAvailable(), handlers, consumed,
            named.getIfAvailable(theOnlyTransactionManager::getObject), MODULE, batchSize);
    }
}
