package com.xenopsoftware.learn.identity.tenant;

import com.xenopsoftware.learn.common.tenancy.AccountStatus;
import com.xenopsoftware.learn.identity.audit.AuditLogger;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Suspending and reinstating a company (T-1.4).
 *
 * <p>The change and its publication happen together, in that order: the row is the truth and the
 * published entry is a copy, so a crash between them leaves a customer briefly enforced only at
 * the boundary — never a customer suspended in the cache that the database says is fine.
 */
@Service
public class TenantStatusService {

    private final JdbcTemplate jdbc;
    private final PublishedTenantStatus published;
    private final AuditLogger audit;

    public TenantStatusService(DataSource dataSource, PublishedTenantStatus published,
            AuditLogger audit) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.published = published;
        this.audit = audit;
    }

    @Transactional
    public AccountStatus change(String tenantId, AccountStatus status, String reason) {
        String previous = jdbc.query("SELECT status FROM tenant WHERE tenant_id = ?",
            rows -> rows.next() ? rows.getString(1) : null, tenantId);
        if (previous == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such company");
        }
        jdbc.update("UPDATE tenant SET status = ?, updated_at = now() WHERE tenant_id = ?",
            status.name(), tenantId);
        // Audited in the platform tenant, where the actor lives: this is staff acting on a
        // customer, and the record of it belongs where somebody would look for it.
        audit.record("tenant.status", "tenant", null, Map.of(
            "tenantId", tenantId,
            "before", previous,
            "after", status.name(),
            "reason", reason == null ? "" : reason));
        published.publish(tenantId, status);
        return status;
    }
}
