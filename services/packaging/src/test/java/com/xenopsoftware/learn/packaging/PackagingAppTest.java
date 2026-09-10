package com.xenopsoftware.learn.packaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void theContentOriginServesItsOwnContentSecurityPolicy() {
        String policy = origins.contentSecurityPolicy();

        // The half of ADR-0105's isolation that stops package code calling anywhere outbound.
        // It lived only in the local stack's Caddyfile, which is a development file -- so a
        // cluster would have served packages with no policy at all and nothing would have said so.
        assertThat(policy).contains("connect-src 'self'");
        // 'self' is not optional: the launch chain is this origin framing itself, and without it
        // the wrapper loads, both API objects appear, and the course is refused with a blank frame.
        assertThat(policy).contains("frame-ancestors 'self' " + origins.appOrigin());
        // Real authoring tools emit both, and a policy that forbids them forbids SCORM. Survivable
        // only because of where it runs: the script it permits has nothing within reach.
        assertThat(policy).contains("'unsafe-inline'").contains("'unsafe-eval'");
    }

    @Test
    void aTwoCharacterCompanyIdCannotBecomeAReservedHostnameLabel() {
        // ADR-0105's amended scheme puts the tenant and the separator in ONE label, and RFC 5891
        // reserves `--` in a label's third and fourth characters for internationalised names. A
        // two-character id lands exactly there, and the failure would otherwise arrive as a
        // customer whose courses do not load, long after the id could be changed.
        ContentOriginProperties clustered = new ContentOriginProperties(
            "https://{tenant}--usercontent-dev.example.com", "https://app.example.com", "/packages");

        assertThat(clustered.originFor("acme"))
            .isEqualTo("https://acme--usercontent-dev.example.com");
        assertThatThrownBy(() -> clustered.originFor("ab"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("RFC 5891");
    }

    @Test
    void noLaunchUrlIsOnTheApplicationsOrigin() {
        String launch = launchUrls.launchUrl("acme", UUID.randomUUID());
        // The whole decision, as one assertion: if these two ever coincide, an uploaded package is
        // running with the application's DOM, cookies and session in reach.
        assertThat(launch).doesNotStartWith(origins.appOrigin());
    }
}
