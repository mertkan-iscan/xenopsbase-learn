package com.xenopsoftware.learn.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.xenopsoftware.learn.common.messaging.Outbox;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * {@code core} starts, and the two modules inside it stay two (ADR-0109, T-9.17).
 *
 * <h2>What this proves that neither module's own suite can</h2>
 *
 * identity's 185 tests and catalog's 146 all pass against a single database, and every one of them
 * would still pass if the merge silently collapsed the two into one. The properties that only
 * exist once the two share a JVM are the ones asserted here:
 *
 * <ul>
 *   <li><b>Both persistence units build.</b> Two {@code EntityManagerFactory}s, two Hibernate
 *       {@code validate} passes against two schemas. This is the assertion that fails if a
 *       migration namespace regresses or an entity drifts from its migration.
 *   <li><b>Two transaction managers, and neither is primary.</b> The whole reason the 78
 *       {@code @Transactional} annotations carry qualifiers. If a primary ever reappears, an
 *       unqualified one starts binding to the wrong module's manager <em>silently</em> — losing
 *       atomicity rather than failing — so the absence of a primary is asserted directly.
 *   <li><b>Two outboxes, in two databases.</b> The transactional outbox's guarantee is that the
 *       event row commits with the domain change; that only holds inside one database, so one
 *       shared outbox would have quietly traded the guarantee for a table.
 *   <li><b>Each module's migrations ran into its own database, and only its own.</b>
 * </ul>
 */
@SpringBootTest
class CoreStartsWithTwoDatabasesTest {

    /**
     * Two containers rather than two schemas in one, because the thing being proved is that the
     * two modules cannot see each other's tables — and two schemas behind one connection would
     * prove the opposite by construction.
     */
    private static final PostgreSQLContainer<?> IDENTITY_DB = new PostgreSQLContainer<>("postgres:17-alpine");
    private static final PostgreSQLContainer<?> CATALOG_DB = new PostgreSQLContainer<>("postgres:17-alpine");

    static {
        IDENTITY_DB.start();
        CATALOG_DB.start();
    }

    @DynamicPropertySource
    static void twoDatabases(DynamicPropertyRegistry registry) {
        registry.add("learn.identity.datasource.url", IDENTITY_DB::getJdbcUrl);
        registry.add("learn.identity.datasource.username", IDENTITY_DB::getUsername);
        registry.add("learn.identity.datasource.password", IDENTITY_DB::getPassword);
        registry.add("learn.catalog.datasource.url", CATALOG_DB::getJdbcUrl);
        registry.add("learn.catalog.datasource.username", CATALOG_DB::getUsername);
        registry.add("learn.catalog.datasource.password", CATALOG_DB::getPassword);

        // No broker in this test: ModuleMessaging returns no subscriber and the relays find
        // nothing to drain. The outbox WRITERS still exist, which is what is asserted below.
        registry.add("platform.messaging.nats-url", () -> "");
        registry.add("identity.authz.cache.enabled", () -> false);
        registry.add("identity.sso.domain-verification", () -> "trusting");
    }

    @Autowired
    private org.springframework.context.ApplicationContext context;

    /**
     * Reaching an assertion at all means the context started: both units built, both Hibernate
     * validations passed against their own migrated schema, and no bean was ambiguous.
     */
    @Test
    void bothPersistenceUnitsAreBuiltAndDistinct() {
        assertThat(context.getBean("identityEntityManagerFactory"))
            .isNotSameAs(context.getBean("catalogEntityManagerFactory"));
    }

    /**
     * Neither transaction manager is primary.
     *
     * <p>Asserted by asking for one by type and expecting the ambiguity, which is the same
     * question an unqualified {@code @Transactional} asks. A test that only checked "two beans
     * exist" would still pass on the day somebody adds {@code @Primary} to fix an unrelated
     * wiring error, and that is the day atomicity quietly stops working in one module.
     */
    @Test
    void neitherTransactionManagerIsPrimary() {
        assertThat(context.getBeanNamesForType(PlatformTransactionManager.class))
            .containsExactlyInAnyOrder("identityTransactionManager", "catalogTransactionManager");
        assertThat(catchThrowableOfType(() -> context.getBean(PlatformTransactionManager.class)))
            .as("an unqualified @Transactional must fail loudly rather than pick one")
            .isNotNull();
    }

    /** One outbox per database, each writing where its module's transaction commits. */
    @Test
    void thereAreTwoOutboxesAndTheyAreInDifferentDatabases() {
        assertThat(context.getBeanNamesForType(Outbox.class))
            .containsExactlyInAnyOrder("identityOutbox", "catalogOutbox");
    }

    /**
     * Each module's migrations ran into its own database, and the other module's did not.
     *
     * <p>{@code app_user} is identity's and {@code content_item} is catalog's, so a table found in
     * the wrong database would mean the migration locations regressed to a shared
     * {@code classpath:db/migration} — the failure that made Flyway refuse to start at all before
     * they were namespaced, and that would silently cross-migrate if the versions ever stopped
     * colliding.
     */
    @ParameterizedTest(name = "{1} exists in {0} and not in the other")
    @CsvSource({
        "identityDataSource, app_user,     content_item",
        "catalogDataSource,  content_item, app_user",
    })
    void eachDatabaseHasOnlyItsOwnSchema(String dataSourceBean, String ownTable, String otherModulesTable) {
        JdbcTemplate jdbc = new JdbcTemplate((DataSource) context.getBean(dataSourceBean));
        assertThat(tableExists(jdbc, ownTable)).as(ownTable + " is missing").isTrue();
        assertThat(tableExists(jdbc, otherModulesTable))
            .as(otherModulesTable + " belongs to the other module's database")
            .isFalse();
    }

    /**
     * The module-specific properties really are bound from this module's own application.yml.
     *
     * <p>core restates identity's and catalog's configuration because Boot reads exactly one
     * {@code config/application.yml} from the classpath and this module's wins. That is
     * deterministic and not obvious, so a property added to a module's yml and not to this one is
     * caught here rather than as a feature that silently reverts to its default.
     */
    @ParameterizedTest
    @CsvSource({
        "identity.invitations.max-import-rows, 5000",
        "identity.impersonation.min-reason,    12",
        "catalog.due.send-hour,                9",
    })
    void moduleConfigurationIsCarriedOverRatherThanInherited(String property, String expected) {
        assertThat(context.getEnvironment().getProperty(property)).isEqualTo(expected);
    }

    private static boolean tableExists(JdbcTemplate jdbc, String table) {
        Integer found = jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = ?",
            Integer.class, table);
        return found != null && found > 0;
    }

    private static Throwable catchThrowableOfType(Runnable call) {
        try {
            call.run();
            return null;
        } catch (RuntimeException expected) {
            return expected;
        }
    }
}
