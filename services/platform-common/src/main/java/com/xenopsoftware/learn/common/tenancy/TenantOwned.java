package com.xenopsoftware.learn.common.tenancy;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import org.hibernate.annotations.TenantId;

/**
 * The base of every tenant-scoped entity (T-1.1).
 *
 * <p>{@code @TenantId} is what moves the discriminator into the persistence layer: Hibernate
 * stamps the column on insert from {@link TenantIdentifierResolver} and filters every query by it,
 * so no repository method ever writes the {@code WHERE tenant_id} clause and no repository method
 * can forget it.
 *
 * <p>The column is {@code NOT NULL} in every migration that creates a tenant-scoped table, with no
 * nullable variant anywhere — a nullable tenant column produces rows that match no filter and
 * become invisible to the tenant that owns them. {@code updatable = false} because a row changing
 * tenants is not an update, it is a data-ownership transfer that should not be expressible by
 * mutating a field.
 *
 * <p>Entities that are deliberately <i>not</i> tenant-scoped (the tenant table itself, platform
 * configuration) simply do not extend this — and that choice is visible in the class declaration,
 * where a review can see it.
 */
@MappedSuperclass
public abstract class TenantOwned {

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false, length = 64)
    private String tenantId;

    /** The owning tenant. Set by Hibernate at insert, never by application code. */
    public String getTenantId() {
        return tenantId;
    }
}
