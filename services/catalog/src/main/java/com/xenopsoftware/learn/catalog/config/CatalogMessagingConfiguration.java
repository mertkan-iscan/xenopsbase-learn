package com.xenopsoftware.learn.catalog.config;

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
 * <p>One database here, so the resolution below always takes the fallback — but the shape matches
 * every other module deliberately. {@link ModuleMessaging} says why these beans are per-module
 * rather than declared once in {@code MessagingConfiguration}, and a module that looks different
 * is a module somebody has to re-derive the answer for.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "platform.outbox.enabled", havingValue = "true")
public class CatalogMessagingConfiguration {

    /** Tags this module's meters and names its durable consumers. Unique per module, by rule. */
    private static final String MODULE = "catalog";

    @Bean
    Outbox catalogOutbox(@Qualifier("catalogDataSource") ObjectProvider<DataSource> named,
            ObjectProvider<DataSource> theOnlyDataSource) {
        return ModuleMessaging.outbox(named.getIfAvailable(theOnlyDataSource::getObject));
    }

    @Bean
    OutboxRelay catalogOutboxRelay(@Qualifier("catalogDataSource") ObjectProvider<DataSource> named,
            ObjectProvider<DataSource> theOnlyDataSource, MessagePublisher publisher,
            @Value("${platform.outbox.batch-size:100}") int batchSize) {
        return ModuleMessaging.relay(
            named.getIfAvailable(theOnlyDataSource::getObject), publisher, batchSize);
    }

    @Bean
    OutboxMetrics catalogOutboxMetrics(
            @Qualifier("catalogOutboxRelay") OutboxRelay relay, MeterRegistry meters) {
        return ModuleMessaging.metrics(relay, meters, MODULE);
    }

    @Bean
    ConsumedMessages catalogConsumedMessages(@Qualifier("catalogDataSource") ObjectProvider<DataSource> named,
            ObjectProvider<DataSource> theOnlyDataSource) {
        return ModuleMessaging.consumed(named.getIfAvailable(theOnlyDataSource::getObject));
    }

    @Bean
    NatsSubscriber catalogNatsSubscriber(ObjectProvider<Connection> connection,
            List<MessageHandler> handlers,
            @Qualifier("catalogConsumedMessages") ConsumedMessages consumed,
            @Qualifier("catalogTransactionManager") ObjectProvider<PlatformTransactionManager> named,
            ObjectProvider<PlatformTransactionManager> theOnlyTransactionManager,
            @Value("${platform.messaging.batch-size:50}") int batchSize) {
        return ModuleMessaging.subscriber(connection.getIfAvailable(), handlers, consumed,
            named.getIfAvailable(theOnlyTransactionManager::getObject), MODULE, batchSize);
    }
}
