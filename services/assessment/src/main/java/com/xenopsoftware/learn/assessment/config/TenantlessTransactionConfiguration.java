package com.xenopsoftware.learn.assessment.config;

import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A transaction for work that belongs to no tenant (T-6.6), copied from streaming's (T-3.3).
 *
 * <p>{@code @Transactional} runs on JPA's transaction manager, which opens a Hibernate session,
 * which the T-1.1 resolver rightly refuses when no tenant is bound. That strictness is correct and
 * it caught this: the attempt reaper's first version used the JPA repository and every scheduled
 * run threw {@code SessionFactory configured for multi-tenancy, but no tenant identifier
 * specified}, once every five minutes, into a log nobody was reading. The tests passed the whole
 * time, because a test calls the sweep inside a tenant the way a request would.
 *
 * <p>A reaper genuinely has no tenant: it is one sweep across every company, and which company owns
 * a lapsed attempt is something only the row knows. So this is a plain JDBC transaction manager,
 * used explicitly by the few pieces of infrastructure that span tenants — explicit, and named, so
 * that reaching for it is a decision somebody makes rather than a default that quietly bypasses the
 * discriminator.
 */
@Configuration(proxyBeanMethods = false)
// Named for the configuration rather than the bean: a @Configuration class registers itself under
// its own decapitalized name, so a @Bean method of the same name collides with it.
public class TenantlessTransactionConfiguration {

    @Bean
    TransactionTemplate tenantlessTransactions(DataSource dataSource) {
        return new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }
}
