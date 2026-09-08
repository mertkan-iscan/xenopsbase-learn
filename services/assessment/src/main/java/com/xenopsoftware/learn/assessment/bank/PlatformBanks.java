package com.xenopsoftware.learn.assessment.bank;

import com.xenopsoftware.learn.common.tenancy.TenantFilter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The shared library: platform-owned banks a customer may read and copy from, and never edit
 * (T-6.1).
 *
 * <h2>Why this is plain JDBC and not a repository</h2>
 *
 * <p>A platform bank is an ordinary row in the platform's reserved tenant, so from a customer's
 * session it is <b>invisible</b> — {@code @TenantId} filters it out, correctly, and that is the
 * property the whole tenancy design rests on. Reading one is therefore a deliberate cross-tenant
 * act, and it is written here, in one class, in SQL that names the tenant out loud.
 *
 * <p>Hibernate would not have helped even if asked: the tenant identifier is fixed when the
 * session opens, so {@code TenantContext.callWith} inside an existing transaction does not move
 * it, and {@code @TenantId} does not filter native SQL at all. A "clever" JPA route to these rows
 * would either return nothing or return everything, and the second failure is the one that ships.
 *
 * <h2>Read-only, and structurally so</h2>
 *
 * <p>There is no update, no insert and no delete here, and there is no permission that would
 * enable one. A customer copies a shared bank into their own tenant — {@link BankService#copy} —
 * and edits the copy. Publishing a bank into the shared library is a platform act against the
 * platform tenant and does not exist yet; when it does it belongs beside {@code tenant:provision}
 * on the platform side, not behind an endpoint a customer can reach.
 */
@Component
public class PlatformBanks {

    // Only what crosses the boundary. A shared bank's own timestamps and provenance are the
    // platform's business, and a column selected but not mapped is one somebody later maps.
    private static final String COLUMNS = "id, name, description";

    private final JdbcTemplate jdbc;

    public PlatformBanks(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** What a customer sees in the shared library. */
    public List<SharedBank> all() {
        return jdbc.query(
            "SELECT " + COLUMNS + " FROM question_bank"
                + " WHERE tenant_id = ? AND shared = true"
                + " ORDER BY lower(name)",
            (rs, row) -> new SharedBank(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("description")),
            TenantFilter.PLATFORM_TENANT);
    }

    /**
     * One shared bank by id.
     *
     * <p>The {@code shared = true} predicate is not decoration. Without it this method would read
     * any row in the platform tenant by id, which is every platform bank whether offered or not —
     * a caller passing a guessed id would be performing exactly the cross-tenant read the rest of
     * the system spends its effort preventing.
     */
    public Optional<SharedBank> find(UUID id) {
        return jdbc.query(
            "SELECT " + COLUMNS + " FROM question_bank"
                + " WHERE tenant_id = ? AND shared = true AND id = ?",
            (rs, row) -> new SharedBank(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("description")),
            TenantFilter.PLATFORM_TENANT, id)
            .stream().findFirst();
    }

    /** What crosses the boundary: a name and a description, never the entity. */
    public record SharedBank(UUID id, String name, String description) {}
}
