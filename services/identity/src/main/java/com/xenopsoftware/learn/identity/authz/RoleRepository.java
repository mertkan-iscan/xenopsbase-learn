package com.xenopsoftware.learn.identity.authz;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Tenant-filtered by the persistence layer (T-1.1). */
public interface RoleRepository extends JpaRepository<Role, UUID> {}
