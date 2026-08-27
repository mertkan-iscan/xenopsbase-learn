package com.xenopsoftware.learn.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The rules that live in the schema, checked against the schema the migrations actually build —
 * not against entities, which only describe the columns Hibernate knows about.
 *
 * <p>These are the standing tripwires for two written rules: T-1.1's "tenant_id NOT NULL with no
 * nullable variant anywhere" and ADR-0104's "no stored sub outside app_user". Each new migration
 * runs into them automatically; nobody has to remember they exist.
 */
@SpringBootTest
class SchemaConventionsTest extends PostgresTestHarness {

    @Autowired
    private DataSource dataSource;

    @Test
    void noTableCarriesANullableTenantColumn() {
        List<String> nullable = new JdbcTemplate(dataSource).queryForList("""
            SELECT table_name
              FROM information_schema.columns
             WHERE table_schema = 'public'
               AND column_name = 'tenant_id'
               AND is_nullable = 'YES'
            """, String.class);
        assertThat(nullable)
            .as("tables whose tenant_id is nullable — rows there would match no tenant filter (T-1.1)")
            .isEmpty();
    }

    @Test
    void noColumnStoresASubOutsideAppUser() {
        List<String> subColumns = new JdbcTemplate(dataSource).queryForList("""
            SELECT table_name || '.' || column_name
              FROM information_schema.columns
             WHERE table_schema = 'public'
               AND column_name ~ '(^|_)(idp_)?sub(ject)?(_id)?($|_)'
               AND NOT (table_name = 'app_user' AND column_name = 'idp_sub')
               AND table_name <> 'flyway_schema_history'
            """, String.class);
        assertThat(subColumns)
            .as("columns shaped like a stored sub — a person is referenced by app_user.id (ADR-0104)")
            .isEmpty();
    }
}
