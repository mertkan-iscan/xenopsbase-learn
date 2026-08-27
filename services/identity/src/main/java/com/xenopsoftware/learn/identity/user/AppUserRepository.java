package com.xenopsoftware.learn.identity.user;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Tenant-filtered by the persistence layer (T-1.1): every method here already carries
 * {@code WHERE tenant_id = <bound tenant>}, so a lookup by sub or email can only ever find the
 * current tenant's people.
 */
public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    Optional<AppUser> findByIdpSub(String idpSub);

    Optional<AppUser> findByEmailIgnoreCase(String email);
}
