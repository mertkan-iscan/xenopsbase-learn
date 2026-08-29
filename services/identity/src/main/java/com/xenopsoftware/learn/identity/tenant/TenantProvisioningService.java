package com.xenopsoftware.learn.identity.tenant;

import com.xenopsoftware.learn.common.tenancy.TenantFilter;
import com.xenopsoftware.learn.identity.audit.AuditLogger;
import com.xenopsoftware.learn.identity.audit.CurrentUser;
import com.xenopsoftware.learn.identity.authz.AssignmentScopeType;
import com.xenopsoftware.learn.identity.authz.SystemRole;
import com.xenopsoftware.learn.identity.authz.SystemRoleSeeder;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Creating a company, in one call and one transaction (T-1.5).
 *
 * <h2>Why it is all one transaction rather than a saga</h2>
 *
 * A half-created tenant is worse than none: an admin who cannot sign in, or a company with no
 * roles, is discovered by the customer. The issue expected the hard part to be compensating for
 * work done in Keycloak — and ADR-0102 removed that work entirely. A company is a row, so
 * <b>nothing is created outside this database</b> and "leaves nothing behind" is a transaction
 * rather than a compensation path. The realm is untouched: no realm, no client, no user, no
 * password.
 *
 * <h2>Why the new tenant's rows are written with explicit SQL</h2>
 *
 * A platform caller is bound to the platform's own tenant, and the obvious move — bind the new
 * tenant for the duration and use the repositories — does not work: <b>Hibernate fixes the
 * tenant identifier when the session opens</b>, and inside a transaction there is one session.
 * Rebinding mid-transaction changes nothing, and the writes land in the caller's tenant while
 * every read comes back from it too. That failed silently here first, which is exactly how it
 * would have failed in production.
 *
 * <p>So the four rows this creates name their tenant in the statement. That is also the more
 * honest shape: this is the one operation that legitimately writes into a tenant the caller is
 * not in, and it says so in the SQL rather than in ambient state.
 */
@Service
public class TenantProvisioningService {

    /** Slugs must be usable in a claim and a URL, and must not collide with the reserved one. */
    private static final java.util.regex.Pattern SLUG = java.util.regex.Pattern.compile("[a-z0-9][a-z0-9-]{1,62}");

    private final Tenants tenants;
    private final SystemRoleSeeder systemRoles;
    private final JdbcTemplate jdbc;
    private final AuditLogger audit;
    private final CurrentUser currentUser;

    public TenantProvisioningService(Tenants tenants, SystemRoleSeeder systemRoles,
            DataSource dataSource, AuditLogger audit, CurrentUser currentUser) {
        this.tenants = tenants;
        this.systemRoles = systemRoles;
        this.jdbc = new JdbcTemplate(dataSource);
        this.audit = audit;
        this.currentUser = currentUser;
    }

    public record ProvisionedTenant(String tenantId, String name, UUID adminUserId, String adminEmail) {}

    /**
     * The whole company: the row, its role templates, its first administrator, and the grant
     * that makes that administrator able to do anything at all.
     *
     * <p>The actor is resolved BEFORE the work starts. That is not ceremony: the audit entry at
     * the end must attribute this to a person, and resolving them lazily inside the transaction
     * is what deadlocked T-2.6 the first time.
     */
    @Transactional
    public ProvisionedTenant provision(String tenantId, String name, String adminEmail,
            String adminDisplayName) {
        UUID actor = currentUser.requireId();
        validate(tenantId, adminEmail);
        if (tenants.exists(tenantId)) {
            // Not an error worth a stack trace: a second call for the same company is either a
            // retry (which the idempotency filter answers before it reaches here) or a name
            // collision, and both want the same plain refusal.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "A company with the id " + tenantId + " already exists");
        }
        tenants.create(tenantId, name);
        systemRoles.ensureSeededFor(tenantId);

        UUID adminId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO app_user (id, tenant_id, email, display_name, status, created_at, updated_at)
            VALUES (?, ?, ?, ?, 'INVITED', now(), now())
            """, adminId, tenantId, adminEmail, adminDisplayName);

        UUID tenantAdminRole = jdbc.query("""
            SELECT id FROM app_role WHERE tenant_id = ? AND system AND name = ?
            """, rows -> rows.next() ? rows.getObject(1, UUID.class) : null,
            tenantId, SystemRole.TENANT_ADMIN.displayName());
        if (tenantAdminRole == null) {
            throw new IllegalStateException("The tenant-admin template was not projected for "
                + tenantId + "; provisioning cannot hand out a role that does not exist");
        }

        // THE FIRST GRANT. It cannot come from inside the tenant -- T-2.6 refuses to let anyone
        // hand out what they do not hold, and nobody in a brand-new company holds anything. So
        // provisioning makes it, from outside, and records which platform user did.
        jdbc.update("""
            INSERT INTO role_assignment (id, tenant_id, role_id, user_id, scope_type, granted_by, created_at)
            VALUES (?, ?, ?, ?, ?, ?, now())
            """, UUID.randomUUID(), tenantId, tenantAdminRole, adminId,
            AssignmentScopeType.TENANT.name(), actor);

        // Audited in the platform's own tenant, where the actor lives and where somebody asking
        // "who created this company" will look.
        audit.record("tenant.provision", "tenant", null, Map.of(
            "tenantId", tenantId,
            "name", name,
            "adminEmail", adminEmail,
            "adminUserId", adminId.toString()));
        return new ProvisionedTenant(tenantId, name, adminId, adminEmail);
    }

    private static void validate(String tenantId, String adminEmail) {
        // Reserved first: __platform does not match the slug shape either, and answering with
        // the shape complaint would send an operator off fixing the wrong thing.
        if (TenantFilter.PLATFORM_TENANT.equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "That id is reserved for the platform itself");
        }
        if (tenantId == null || !SLUG.matcher(tenantId).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "A company id is lowercase letters, digits and hyphens, 2 to 63 characters");
        }
        if (adminEmail == null || !adminEmail.contains("@")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "The first administrator needs an email address to be invited at");
        }
    }
}
