package com.xenopsoftware.learn.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import com.xenopsoftware.learn.streaming.media.FakeMediaProvider;
import com.xenopsoftware.learn.streaming.media.MediaProvider;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The service boots on the template's stack — Flyway migrates, the fake provider is the default
 * wiring — and the schema holds the platform rules from its first migration: tenant_id NOT NULL
 * everywhere (T-1.1), no stored sub (ADR-0104), and no vendor named in a column (T-3.1).
 */
@SpringBootTest
class StreamingAppTest extends PostgresTestHarness {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private MediaProvider mediaProvider;

    @Test
    void theDefaultProviderIsTheFakeOne() {
        // matchIfMissing: a developer with no vendor account gets a working service, and the
        // fake's startup WARN keeps anyone from reading that as proof of edge delivery.
        assertThat(mediaProvider).isInstanceOf(FakeMediaProvider.class);
        assertThat(mediaProvider.providerId()).isEqualTo("fake");
    }

    @Test
    void migrationsAppliedAndTheMarkerNamesThisModule() {
        String module = new JdbcTemplate(dataSource)
            .queryForObject("SELECT module FROM schema_marker", String.class);
        assertThat(module).isEqualTo("streaming");
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
        assertThat(nullable).isEmpty();
    }

    @Test
    void noColumnStoresASubAndNoColumnNamesTheVendor() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        List<String> subColumns = jdbc.queryForList("""
            SELECT table_name || '.' || column_name
              FROM information_schema.columns
             WHERE table_schema = 'public'
               AND column_name ~ '(^|_)(idp_)?sub(ject)?(_id)?($|_)'
               AND table_name <> 'flyway_schema_history'
            """, String.class);
        assertThat(subColumns).isEmpty();

        List<String> vendorColumns = jdbc.queryForList("""
            SELECT table_name || '.' || column_name
              FROM information_schema.columns
             WHERE table_schema = 'public'
               AND column_name ~ 'cloudflare|cf_'
            """, String.class);
        assertThat(vendorColumns)
            .as("the vendor appears in the schema only as the generic provider discriminator (T-3.1)")
            .isEmpty();
    }
}
