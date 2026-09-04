package com.xenopsoftware.learn.identity.tenant;

import com.xenopsoftware.learn.common.tenancy.AccountStatus;
import com.xenopsoftware.learn.identity.group.GroupHierarchy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The worst link in the chain (T-1.4): tenant, then the groups a person is in and everything
 * those groups hang from, then the person.
 *
 * <p>Ancestors count, and that is the same containment rule assignments follow (T-2.3): a group
 * suspended near the root suspends the departments inside it, or the tree would mean one thing
 * for grants and another for status.
 *
 * <p>Plain SQL, and it names its tenant. This is asked on request paths where the caller may be
 * platform staff looking at a customer, and it is asked at startup for tenants nobody is bound
 * to — neither of which a session-scoped discriminator can serve.
 */
@Component
public class EffectiveStatus {

    private final JdbcTemplate jdbc;
    private final GroupHierarchy hierarchy;

    public EffectiveStatus(DataSource dataSource, GroupHierarchy hierarchy) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.hierarchy = hierarchy;
    }

    /** The tenant's own status, which is the ceiling for everyone in it. */
    public AccountStatus ofTenant(String tenantId) {
        List<String> status = jdbc.queryForList(
            "SELECT status FROM tenant WHERE tenant_id = ?", String.class, tenantId);
        if (status.isEmpty()) {
            // A tenant nobody provisioned: dev data that predates the tenant table, or a slug
            // that never existed. Treated as active, because refusing every unknown tenant
            // would suspend the local stack rather than protect anything.
            return AccountStatus.ACTIVE;
        }
        return parse(status.getFirst());
    }

    /**
     * The chain as far as the EDGE should judge it: tenant and groups, and deliberately not the
     * person's own status.
     *
     * <p>Deactivation is already refused by {@code DeactivatedUserFilter} (T-1.9), with a
     * reason of its own — "your account was deactivated" and "your company is suspended" are
     * different things to be told, and collapsing them into one code would make a UI say the
     * wrong one. This gate covers the two levels nothing else covers; that filter keeps the
     * level it already owns.
     */
    public AccountStatus ofMembership(String tenantId, String idpSub) {
        AccountStatus tenant = archived(tenantId) ? AccountStatus.SUSPENDED : ofTenant(tenantId);
        if (tenant == AccountStatus.SUSPENDED || idpSub == null) {
            return tenant;
        }
        List<UUID> ids = jdbc.queryForList(
            "SELECT id FROM app_user WHERE tenant_id = ? AND idp_sub = ?", UUID.class,
            tenantId, idpSub);
        if (ids.isEmpty()) {
            return tenant;
        }
        return AccountStatus.worstOf(tenant, groupStatus(tenantId, ids.getFirst()));
    }

    /**
     * The whole chain for one person, the person's own status included. This is the answer an
     * entitlement decision needs (T-3.4), where there is no second filter behind it to catch a
     * deactivated learner. Archived tenants read as suspended: archiving is the
     * durable form of the same refusal (T-1.5's migration says so), and expressing it twice
     * would let the two drift.
     */
    public AccountStatus ofUser(String tenantId, String idpSub) {
        AccountStatus tenant = archived(tenantId) ? AccountStatus.SUSPENDED : ofTenant(tenantId);
        if (tenant == AccountStatus.SUSPENDED || idpSub == null) {
            return tenant;
        }
        List<java.util.Map<String, Object>> rows = jdbc.queryForList("""
            SELECT id, status FROM app_user WHERE tenant_id = ? AND idp_sub = ?
            """, tenantId, idpSub);
        if (rows.isEmpty()) {
            // Authenticated but not provisioned yet -- their first request. The tenant's own
            // status is the whole answer, and provisioning happens inside the handler.
            return tenant;
        }
        AccountStatus user = userStatus((String) rows.getFirst().get("status"));
        UUID userId = (UUID) rows.getFirst().get("id");
        return AccountStatus.worstOf(tenant, user, groupStatus(tenantId, userId));
    }

    /** The worst status among the person's groups and every group those hang from. */
    private AccountStatus groupStatus(String tenantId, UUID userId) {
        List<UUID> groups = jdbc.queryForList(
            "SELECT group_id FROM group_membership WHERE tenant_id = ? AND user_id = ?",
            UUID.class, tenantId, userId);
        if (groups.isEmpty()) {
            return AccountStatus.ACTIVE;
        }
        List<UUID> chain = new ArrayList<>(groups);
        for (UUID group : groups) {
            chain.addAll(com.xenopsoftware.learn.common.tenancy.TenantContext
                .callWithUnchecked(tenantId, () -> hierarchy.ancestorIds(group)));
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(chain.size(), "?"));
        Object[] args = new Object[chain.size() + 1];
        args[0] = tenantId;
        for (int i = 0; i < chain.size(); i++) {
            args[i + 1] = chain.get(i);
        }
        List<String> statuses = jdbc.queryForList(
            "SELECT status FROM user_group WHERE tenant_id = ? AND id IN (" + placeholders + ")",
            String.class, args);
        AccountStatus worst = AccountStatus.ACTIVE;
        for (String status : statuses) {
            worst = AccountStatus.worstOf(worst, parse(status));
        }
        return worst;
    }

    private boolean archived(String tenantId) {
        Boolean archived = jdbc.query(
            "SELECT archived_at IS NOT NULL FROM tenant WHERE tenant_id = ?",
            rows -> rows.next() && rows.getBoolean(1), tenantId);
        return Boolean.TRUE.equals(archived);
    }

    /**
     * A person's lifecycle state as a status. DEACTIVATED is suspension for one person;
     * INVITED is not a refusal at all -- an invitation is claimed by signing in, and refusing
     * the sign-in would make it unclaimable.
     */
    private static AccountStatus userStatus(String status) {
        return "DEACTIVATED".equals(status) ? AccountStatus.SUSPENDED : AccountStatus.ACTIVE;
    }

    private static AccountStatus parse(String status) {
        for (AccountStatus known : AccountStatus.values()) {
            if (known.name().equals(status)) {
                return known;
            }
        }
        // A status this build does not know is a status a newer build wrote. Refusing is the
        // safe direction: better a customer who cannot act than one who acts when a version
        // they never ran said they should not.
        return AccountStatus.SUSPENDED;
    }
}
