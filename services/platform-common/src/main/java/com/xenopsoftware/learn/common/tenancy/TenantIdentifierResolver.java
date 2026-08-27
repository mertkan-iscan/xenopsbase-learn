package com.xenopsoftware.learn.common.tenancy;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;

/**
 * Hands the bound tenant to Hibernate, so the discriminator is the persistence layer's job and
 * not each query's (T-1.1).
 *
 * <p>Registered per service by class name, visibly, in its configuration:
 *
 * <pre>
 * spring.jpa.properties.hibernate.tenant_identifier_resolver:
 *     com.xenopsoftware.learn.common.tenancy.TenantIdentifierResolver
 * </pre>
 *
 * Hibernate instantiates it reflectively, which is why it reads {@link TenantContext} statically
 * and takes nothing in its constructor. Together with a {@code @TenantId} field on the entity
 * ({@link TenantOwned}), this filters every query and stamps every insert with the current tenant
 * — a forgotten {@code WHERE tenant_id = ?} is then a query that was already filtered, not a data
 * leak.
 *
 * <p>{@code null} is returned faithfully when no tenant is bound, and Hibernate's response is
 * stricter than expected in the right direction: it refuses to open a session at all, so unbound
 * work cannot touch the database (proved in {@code TenantDiscriminatorTest}). Every tenant-scoped
 * table additionally carries {@code tenant_id NOT NULL} as the schema-owned second fence. The
 * strictness has a consequence worth knowing: the platform side, which binds no tenant by design,
 * currently cannot use JPA either. The deliberate opt-in for that is this interface's root-tenant
 * mechanism ({@code isRoot}), and it arrives with the first platform-side reader (T-1.2/T-1.5)
 * rather than speculatively here.
 */
public final class TenantIdentifierResolver implements CurrentTenantIdentifierResolver<String> {

    @Override
    public String resolveCurrentTenantIdentifier() {
        return TenantContext.get();
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        // Sessions are request-scoped and requests bind exactly one tenant, so there is nothing
        // to re-validate; true would make any session that outlives a tenant switch an error.
        return false;
    }
}
