package com.xenopsoftware.learn.packaging.bundle;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Packages, this company's only.
 *
 * <p>No method here writes a {@code tenant_id} clause and none can forget one: the discriminator
 * is the persistence layer's job ({@code TenantOwned}), so another company's id is simply not
 * found — the 404-not-403 shape ADR-0102 promises.
 */
public interface ContentPackageRepository extends JpaRepository<ContentPackage, UUID> {

    /** The authoring list: everything that has not been deleted, newest first. */
    List<ContentPackage> findByStateNotOrderByCreatedAtDesc(PackageState state);
}
