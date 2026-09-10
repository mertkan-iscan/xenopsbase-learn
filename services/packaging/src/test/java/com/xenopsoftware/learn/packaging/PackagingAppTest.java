package com.xenopsoftware.learn.packaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.xenopsoftware.learn.packaging.launch.ContentOriginProperties;
import com.xenopsoftware.learn.packaging.launch.LaunchUrls;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The service boots on the template's stack, and the schema holds the platform's rules from its
 * first migration.
 *
 * <p>The context load is doing more work than it looks like: Flyway migrates, Hibernate validates
 * every entity against what Flyway produced ({@code ddl-auto: validate}), and the wrapper template
 * is read out of the jar at construction. Each of those is a way this service can be broken by a
 * change that compiles — a column renamed in one place, a resource left out of the build — and
 * each fails here rather than at the first upload.
 */
@SpringBootTest
class PackagingAppTest extends PostgresTestHarness {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private LaunchUrls launchUrls;

    @Autowired
    private ContentOriginProperties origins;

    @Test
    void migrationsAppliedAndTheMarkerNamesThisModule() {
        String module = new JdbcTemplate(dataSource)
            .queryForObject("SELECT module FROM schema_marker", String.class);
        assertThat(module).isEqualTo("packaging");
    }

    @Test
    void noTableCarriesANullableTenantColumn() {
        List<String> nullable = new JdbcTemplate(dataSource).queryForList("""
            SELECT table_name
              FROM information_schema.columns
             WHERE table_schema = 'public'
               AND column_name = 'tenant_id'
               AND is_nullable = 'YES'
            """, String.class);
        // A nullable tenant column produces rows that match no filter and become invisible to the
        // company that owns them (T-1.1).
        assertThat(nullable).isEmpty();
    }

    @Test
    void noColumnStoresASub() {
        List<String> subColumns = new JdbcTemplate(dataSource).queryForList("""
            SELECT table_name || '.' || column_name
              FROM information_schema.columns
             WHERE table_schema = 'public'
               AND column_name ~ '(^|_)(idp_)?sub(ject)?(_id)?($|_)'
               AND table_name <> 'flyway_schema_history'
            """, String.class);
        assertThat(subColumns)
            .as("only identity maps a Keycloak sub, in one nullable column (ADR-0104)")
            .isEmpty();
    }

    @Test
    void everyLaunchUrlIsOnTheTenantsOwnContentOrigin() {
        UUID packageId = UUID.randomUUID();
        String acme = launchUrls.launchUrl("acme", packageId);
        String globex = launchUrls.launchUrl("globex", packageId);

        // ONE ORIGIN PER TENANT (ADR-0105), and it is not retrofittable: a launch URL is embedded
        // in course content and recorded in attempt history, so the day two companies share an
        // origin is the day fixing it means rewriting data.
        assertThat(acme).startsWith("http://acme.localhost:8090/");
        assertThat(globex).startsWith("http://globex.localhost:8090/");
        assertThat(acme).isNotEqualTo(globex);
    }

    @Test
    void noLaunchUrlIsOnTheApplicationsOrigin() {
        String launch = launchUrls.launchUrl("acme", UUID.randomUUID());
        // The whole decision, as one assertion: if these two ever coincide, an uploaded package is
        // running with the application's DOM, cookies and session in reach.
        assertThat(launch).doesNotStartWith(origins.appOrigin());
    }
}
