package com.xenopsoftware.learn.identity.config;

import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.jpa.EntityManagerFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.repository.config.BootstrapMode;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * This module's own persistence unit, named rather than default (ADR-0109, ADR-0111).
 *
 * <h2>Why this is not left to auto-configuration any more</h2>
 *
 * ADR-0109 puts {@code identity}, {@code identity} and {@code assessment} in one deployable while
 * keeping their databases and migration histories separate. One process with two databases means
 * two {@link DataSource}s, two {@link EntityManagerFactory}s and — the part that bites — two
 * {@link PlatformTransactionManager}s.
 *
 * <p>Boot's auto-configuration produces exactly one of each, so at least one module has to declare
 * its own. Both do, and <b>neither is {@code @Primary} anywhere</b>. That is the decision worth
 * stating, because the obvious alternative is worse in a way that does not show up in a test.
 *
 * <h2>Why no primary</h2>
 *
 * With a primary transaction manager, an unqualified {@code @Transactional} in this module would
 * silently bind to <em>catalog's</em>. It would not fail. The identity repository inside it would
 * open its own session outside that transaction, so the method would look transactional, pass its
 * tests, and lose atomicity — a rollback would roll back nothing. That is the worst available
 * failure mode: invisible, and only in production, and only under a partial failure.
 *
 * <p>With no primary, the same mistake throws {@code NoUniqueBeanDefinitionException} on the first
 * call. {@code IdentityTransactionsAreQualifiedTest} moves it earlier still, to the build.
 *
 * <h2>How this stays identical whether the module runs alone or inside `core`</h2>
 *
 * The only thing that differs between the two is where the {@link DataSource} comes from, so that
 * is the only thing resolved at runtime: a {@code identityDataSource} bean if one exists (the
 * {@code core} assembly declares it), otherwise the single auto-configured one (running this
 * module alone, and every test in it). No {@code @Conditional}, no profile, no second code path —
 * conditions on user configuration are evaluated in registration order, and this codebase has
 * already paid once for depending on that.
 *
 * <p>Everything else — {@code ddl-auto: validate}, the UTC clock, the tenant discriminator, the
 * naming strategy — comes from {@code spring.jpa.*} through {@link EntityManagerFactoryBuilder},
 * so it is still stated once in YAML and applies to both units.
 */
@Configuration(proxyBeanMethods = false)
@EnableJpaRepositories(
    basePackages = "com.xenopsoftware.learn.identity",
    entityManagerFactoryRef = "identityEntityManagerFactory",
    transactionManagerRef = "identityTransactionManager",
    // Carried over from `spring.data.jpa.repositories.bootstrap-mode`, which an explicit
    // @EnableJpaRepositories no longer reads. Not a performance setting: repository
    // initialization validates queries by opening a session on the startup thread, which binds
    // no tenant, and TenantIdentifierResolver rightly refuses a session without one. Dropping
    // this makes the service fail to start -- loudly, at least, but for a reason that reads as
    // a tenancy bug rather than as a missing attribute.
    bootstrapMode = BootstrapMode.LAZY)
public class IdentityPersistenceConfiguration {

    /** The persistence unit name, which is what tells the two units apart in a stack trace. */
    static final String UNIT = "identity";

    @Bean
    LocalContainerEntityManagerFactoryBean identityEntityManagerFactory(
            EntityManagerFactoryBuilder builder,
            @Qualifier("identityDataSource") ObjectProvider<DataSource> identityDataSource,
            ObjectProvider<DataSource> theOnlyDataSource) {
        return builder
            .dataSource(identityDataSource.getIfAvailable(theOnlyDataSource::getObject))
            // Scoped to this module's packages, which is also the enforcement ADR-0109 promised:
            // an entity from another module is not in this unit and cannot be reached from a
            // repository bound to it, merged process or not.
            .packages("com.xenopsoftware.learn.identity")
            .persistenceUnit(UNIT)
            .build();
    }

    @Bean
    PlatformTransactionManager identityTransactionManager(
            @Qualifier("identityEntityManagerFactory") EntityManagerFactory identityEntityManagerFactory) {
        return new JpaTransactionManager(identityEntityManagerFactory);
    }
}
