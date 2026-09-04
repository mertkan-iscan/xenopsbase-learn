package com.xenopsoftware.learn.identity.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.xenopsoftware.learn.common.tenancy.AccountStatus;
import com.xenopsoftware.learn.identity.PostgresTestHarness;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The effective status is the worst link in the chain (T-1.4), and every combination says so.
 */
@SpringBootTest
class StatusChainTest extends PostgresTestHarness {

    @Autowired
    private EffectiveStatus effective;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;
    private UUID person;
    private UUID department;

    @BeforeEach
    void aPersonInADepartmentOfACompany() {
        jdbc = new JdbcTemplate(dataSource);
        clear();
        jdbc.update("INSERT INTO tenant (tenant_id, name) VALUES ('acme', 'Acme')");
        person = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO app_user (id, tenant_id, email, display_name, status, idp_sub, created_at, updated_at)
            VALUES (?, 'acme', 'person@acme.test', 'Person', 'ACTIVE', 'sub-person', now(), now())
            """, person);
        UUID company = UUID.randomUUID();
        department = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO user_group (id, tenant_id, parent_id, name, created_at, updated_at)
            VALUES (?, 'acme', null, 'Company', now(), now())
            """, company);
        jdbc.update("""
            INSERT INTO user_group (id, tenant_id, parent_id, name, created_at, updated_at)
            VALUES (?, 'acme', ?, 'Engineering', now(), now())
            """, department, company);
        jdbc.update("""
            INSERT INTO group_membership (id, tenant_id, group_id, user_id, created_at)
            VALUES (?, 'acme', ?, ?, now())
            """, UUID.randomUUID(), department, person);
        this.company = company;
    }

    private UUID company;

    @AfterEach
    void clearAfter() {
        clear();
    }

    @ParameterizedTest(name = "tenant {0} + group {1} + user {2} = {3}")
    @CsvSource({
        // Nothing wrong anywhere.
        "ACTIVE,     ACTIVE,      ACTIVE,      ACTIVE",
        // One link at a time, each of which decides on its own.
        "READ_ONLY,  ACTIVE,      ACTIVE,      READ_ONLY",
        "ACTIVE,     READ_ONLY,   ACTIVE,      READ_ONLY",
        "ACTIVE,     ACTIVE,      DEACTIVATED, SUSPENDED",
        "SUSPENDED,  ACTIVE,      ACTIVE,      SUSPENDED",
        "ACTIVE,     SUSPENDED,   ACTIVE,      SUSPENDED",
        // The worst wins wherever it sits, and a milder link never softens it.
        "READ_ONLY,  SUSPENDED,   ACTIVE,      SUSPENDED",
        "SUSPENDED,  READ_ONLY,   ACTIVE,      SUSPENDED",
        "READ_ONLY,  READ_ONLY,   ACTIVE,      READ_ONLY",
        "READ_ONLY,  ACTIVE,      DEACTIVATED, SUSPENDED"
    })
    void theWorstLinkDecides(String tenant, String group, String user, AccountStatus expected) {
        jdbc.update("UPDATE tenant SET status = ? WHERE tenant_id = 'acme'", tenant);
        jdbc.update("UPDATE user_group SET status = ? WHERE id = ?", group, department);
        jdbc.update("UPDATE app_user SET status = ? WHERE id = ?", user, person);

        assertThat(effective.ofUser("acme", "sub-person")).isEqualTo(expected);
    }

    @Test
    void aSuspendedParentSuspendsTheDepartmentsInsideIt() {
        // The same containment rule assignments follow (T-2.3): the person is in Engineering,
        // and it is the company group above it that was suspended.
        jdbc.update("UPDATE user_group SET status = 'SUSPENDED' WHERE id = ?", company);

        assertThat(effective.ofUser("acme", "sub-person")).isEqualTo(AccountStatus.SUSPENDED);
    }

    @Test
    void anArchivedTenantReadsAsSuspended() {
        jdbc.update("UPDATE tenant SET archived_at = now() WHERE tenant_id = 'acme'");

        // Archiving is the durable form of the same refusal (T-1.5), and expressing it twice
        // would let the two drift.
        assertThat(effective.ofUser("acme", "sub-person")).isEqualTo(AccountStatus.SUSPENDED);
    }

    @Test
    void anInvitedPersonIsNotRefused() {
        // An invitation is claimed by signing in; refusing the sign-in would make it
        // unclaimable (T-1.5).
        jdbc.update("UPDATE app_user SET status = 'INVITED' WHERE id = ?", person);

        assertThat(effective.ofUser("acme", "sub-person")).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void aStatusThisBuildDoesNotKnowIsRefused() {
        // Written by a newer version during a rolling deploy. Refusing is the safe direction:
        // better a customer who cannot act than one who acts when a version they never ran
        // said they should not.
        jdbc.update("UPDATE tenant SET status = 'PENDING_DELETION' WHERE tenant_id = 'acme'");

        assertThat(effective.ofTenant("acme")).isEqualTo(AccountStatus.SUSPENDED);
    }

    private void clear() {
        jdbc.update("DELETE FROM role_assignment");
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM group_membership");
        jdbc.update("DELETE FROM user_group");
        jdbc.update("DELETE FROM app_user WHERE tenant_id = 'acme'");
        jdbc.update("DELETE FROM tenant WHERE tenant_id = 'acme'");
    }
}
