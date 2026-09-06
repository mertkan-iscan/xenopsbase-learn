package com.xenopsoftware.learn.core.config;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jpa.autoconfigure.EntityManagerFactoryDependsOnPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Two databases, one process (ADR-0109).
 *
 * <p>This is the entire cost of the merge, and it is worth reading once because ADR-0109 did not
 * price it. That ADR says the expensive part of splitting a service later is untangling a schema
 * and "there is nothing to untangle" — which is true, and is about splitting. <b>Merging costs
 * something else</b>: the transaction manager, and the migration namespace.
 *
 * <p>The migration namespace was paid first and separately. Both modules numbered from {@code V1},
 * so {@code classpath:db/migration} in one process resolved across both jars and Flyway refused to
 * start on duplicate versions. Their files now live under {@code db/migration/<module>}.
 *
 * <p>The transaction manager is paid in {@code IdentityPersistenceConfiguration} and
 * {@code CatalogPersistenceConfiguration}: each module owns a named persistence unit and a named
 * transaction manager, and <b>neither is primary</b>, so an unqualified {@code @Transactional}
 * fails instead of silently binding to the other module's.
 *
 * <p>What is left here is the part only the assembly can know: which database is which.
 *
 * <h2>Why the DataSources are declared here and not in the modules</h2>
 *
 * Because it is the only thing that differs between a module running alone and the same module
 * running inside this process. Each module's persistence configuration asks for a DataSource
 * <em>by name</em> and falls back to the single auto-configured one; declaring the two named beans
 * here is what switches them over, with no profile and no condition on either side.
 */
@Configuration(proxyBeanMethods = false)
public class CorePersistenceConfiguration {

    // ---------------------------------------------------------------------------------------
    // The two databases. Separate URLs, separate ROLES -- ADR-0109 is explicit that the
    // enforcement of "no module reads another module's schema" is credentials rather than
    // convention, and merging the processes does not merge the grants. A cross-module query
    // still fails to connect rather than returning the wrong answer.
    // ---------------------------------------------------------------------------------------

    @Bean
    @ConfigurationProperties("learn.identity.datasource")
    DataSourceProperties identityDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @ConfigurationProperties("learn.identity.datasource.hikari")
    DataSource identityDataSource(DataSourceProperties identityDataSourceProperties) {
        return identityDataSourceProperties.initializeDataSourceBuilder().build();
    }

    @Bean
    @ConfigurationProperties("learn.catalog.datasource")
    DataSourceProperties catalogDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @ConfigurationProperties("learn.catalog.datasource.hikari")
    DataSource catalogDataSource(DataSourceProperties catalogDataSourceProperties) {
        return catalogDataSourceProperties.initializeDataSourceBuilder().build();
    }

    // ---------------------------------------------------------------------------------------
    // Two migration histories, and they stay two.
    //
    // Boot's Flyway auto-configuration is excluded on CoreApp: it builds exactly one Flyway
    // against the primary DataSource, and there is no primary here. Declared explicitly, each
    // one names its own locations and its own database, so `flyway_schema_history` in the
    // identity database describes identity and nothing else.
    // ---------------------------------------------------------------------------------------

    @Bean(initMethod = "migrate")
    Flyway identityFlyway(DataSource identityDataSource) {
        return migrationsFor(identityDataSource, "identity");
    }

    @Bean(initMethod = "migrate")
    Flyway catalogFlyway(DataSource catalogDataSource) {
        return migrationsFor(catalogDataSource, "catalog");
    }

    private static Flyway migrationsFor(DataSource dataSource, String module) {
        return Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration/" + module)
            // No baselineOnMigrate, matching what both modules run standalone. It exists to adopt
            // a database that already has a schema, and on a fresh one it turns a failed first
            // migration into a silently half-built database.
            .baselineOnMigrate(false)
            .load();
    }

    /**
     * Migrations run before either persistence unit is built.
     *
     * <p>Without this the two are unordered, and the failure is intermittent rather than
     * consistent: {@code ddl-auto: validate} against a database whose migration has not run yet
     * reports a missing table, so a service that starts fine on a warm database fails on a cold
     * one. Boot wires this for its own single Flyway; with two declared by hand, the dependency
     * has to be declared by hand as well.
     */
    @Bean
    static EntityManagerFactoryDependsOnPostProcessor persistenceUnitsWaitForMigrations() {
        return new EntityManagerFactoryDependsOnPostProcessor("identityFlyway", "catalogFlyway");
    }
}
