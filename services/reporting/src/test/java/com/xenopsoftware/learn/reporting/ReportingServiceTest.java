package com.xenopsoftware.learn.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The service boots on the template's stack with its own database (T-9.7), and the platform
 * rules hold in it from the first migration — the third application of the template, which is
 * the claim T-9.10 makes and this is its third test.
 */
@SpringBootTest
class ReportingServiceTest extends PostgresTestHarness {

    @Autowired
    private DataSource dataSource;

    @Test
    void migrationsAppliedAndTheMarkerNamesThisModule() {
        String module = new JdbcTemplate(dataSource)
            .queryForObject("SELECT module FROM schema_marker", String.class);
        assertThat(module).isEqualTo("reporting");
    }

    @Test
    void itKnowsNothingAboutAnyOtherModulesTables() {
        // Its own database, and only its own. A report that could see catalog's tables would
        // eventually read them (T-9.7).
        List<String> tables = new JdbcTemplate(dataSource).queryForList("""
            SELECT table_name FROM information_schema.tables
             WHERE table_schema = 'public'
             ORDER BY table_name
            """, String.class);

        assertThat(tables).doesNotContain("app_user", "app_role", "video_asset", "content_item",
            "role_assignment", "user_group", "tenant");
    }

    @Test
    void noTableCarriesANullableTenantColumn() {
        // The rule every schema in this platform keeps (T-1.1), asserted here from the first
        // migration so the first real table cannot be the one that breaks it.
        List<String> nullable = new JdbcTemplate(dataSource).queryForList("""
            SELECT table_name FROM information_schema.columns
             WHERE table_schema = 'public' AND column_name = 'tenant_id' AND is_nullable = 'YES'
            """, String.class);
        assertThat(nullable).isEmpty();
    }
}
