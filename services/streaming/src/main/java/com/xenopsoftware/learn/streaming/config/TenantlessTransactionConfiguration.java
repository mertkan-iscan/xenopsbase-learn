package com.xenopsoftware.learn.streaming.config;

import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A transaction for work that belongs to no tenant (T-3.3).
 *
 * <p>{@code @Transactional} here runs on JPA's transaction manager, which opens a Hibernate
 * session, which the T-1.1 resolver rightly refuses when no tenant is bound. That strictness is
 * correct and has caught real mistakes — but a provider webhook genuinely has no tenant: it is
 * one system telling another that an encode finished, and which customer owns the asset is
 * something only our row knows.
 *
 * <p>So this is a plain JDBC transaction manager, used explicitly by the few pieces of
 * infrastructure that span tenants or precede knowing one. Explicit, and named, so that reaching
 * for it is a decision somebody makes rather than a default that quietly bypasses the
 * discriminator.
 */
@Configuration(proxyBeanMethods = false)
// Named for the configuration rather than the bean: a @Configuration class registers itself
// under its own decapitalized name, so a @Bean method of the same name collides with it.
public class TenantlessTransactionConfiguration {

    @Bean
    TransactionTemplate tenantlessTransactions(DataSource dataSource) {
        return new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }
}
