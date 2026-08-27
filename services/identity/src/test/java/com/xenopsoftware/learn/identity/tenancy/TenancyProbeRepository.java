package com.xenopsoftware.learn.identity.tenancy;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Deliberately nothing but the inherited methods: no {@code findByTenantId}, no tenant parameter
 * anywhere. The discriminator test's whole point is that an ordinary repository, written by
 * someone who forgot tenancy exists, is already filtered.
 */
public interface TenancyProbeRepository extends JpaRepository<TenancyProbe, Long> {}
