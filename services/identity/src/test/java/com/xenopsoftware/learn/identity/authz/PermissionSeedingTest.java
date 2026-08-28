package com.xenopsoftware.learn.identity.authz;

import static org.assertj.core.api.Assertions.assertThat;

import com.xenopsoftware.learn.identity.PostgresTestHarness;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The seeder against a real Postgres (T-2.1): the projection is exact, a retired code is marked
 * and kept, a returning code is revived, and a restart writes nothing.
 */
@SpringBootTest
class PermissionSeedingTest extends PostgresTestHarness {

    @Autowired
    private PermissionCatalogSeeder seeder;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    @AfterEach
    void removeTheGhost() {
        jdbc().update("DELETE FROM permission WHERE code = 'ghost:haunt'");
    }

    @Test
    void startupProjectedExactlyTheEnum() {
        // The context is up, so the ApplicationRunner has run.
        List<String> live = jdbc().queryForList(
            "SELECT code FROM permission WHERE NOT orphaned ORDER BY code", String.class);
        assertThat(live).containsExactlyInAnyOrderElementsOf(
            Arrays.stream(Permission.values()).map(Permission::code).toList());
    }

    @Test
    void aRetiredCodeIsOrphanedAndKeptNeverDeleted() throws Exception {
        jdbc().update("""
            INSERT INTO permission (code, resource, action, side, min_scope)
            VALUES ('ghost:haunt', 'ghost', 'haunt', 'TENANT', 'TENANT')
            """);

        seeder.seed();

        Map<String, Object> ghost = jdbc().queryForMap(
            "SELECT orphaned FROM permission WHERE code = 'ghost:haunt'");
        assertThat(ghost.get("orphaned")).isEqualTo(true);
    }

    @Test
    void aCodeThatReturnsToTheEnumIsRevived() throws Exception {
        jdbc().update("UPDATE permission SET orphaned = true WHERE code = ?",
            Permission.USER_READ.code());

        seeder.seed();

        assertThat(jdbc().queryForObject(
            "SELECT orphaned FROM permission WHERE code = ?", Boolean.class,
            Permission.USER_READ.code())).isFalse();
    }

    @Test
    void reseedingAnUnchangedCatalogWritesNothing() throws Exception {
        seeder.seed();
        List<Map<String, Object>> before =
            jdbc().queryForList("SELECT code, updated_at FROM permission ORDER BY code");

        seeder.seed();

        // updated_at unchanged is the proof: the upsert's WHERE clause really does skip
        // untouched rows, so "when the catalog last changed" stays a meaningful timestamp
        // instead of "when the service last restarted".
        assertThat(jdbc().queryForList("SELECT code, updated_at FROM permission ORDER BY code"))
            .isEqualTo(before);
    }
}
