package com.xenopsoftware.learn.identity.web.rest;

import com.xenopsoftware.learn.identity.tenant.TenantProvisioningService;
import com.xenopsoftware.learn.identity.tenant.Tenants;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Provisioning a company is an API call, not a runbook (T-1.5).
 *
 * <p><b>The first endpoint in this service to carry a real check</b>, and it can because the
 * chain finally has a start: an operator names the platform administrators in configuration,
 * they hold {@code tenant:provision} through the seeded platform role, and this call gives a new
 * company's first admin the grant that lets them run it.
 *
 * <p>Retries are safe: send an {@code Idempotency-Key} header and a repeat returns the first
 * response rather than creating a second company.
 */
@RestController
@RequestMapping("/api/v1/platform/tenants")
public class TenantResource {

    private final TenantProvisioningService provisioning;
    private final Tenants tenants;

    public TenantResource(TenantProvisioningService provisioning, Tenants tenants) {
        this.provisioning = provisioning;
        this.tenants = tenants;
    }

    public record ProvisionRequest(String tenantId, String name, String adminEmail,
                                   String adminName) {}

    public record TenantView(String tenantId, String name, String status, boolean archived) {}

    public record ProvisionedView(String tenantId, String name, UUID adminUserId, String adminEmail) {}

    @GetMapping
    @PreAuthorize("hasPermission('tenant', 'provision')")
    public List<TenantView> all() {
        return tenants.customers().stream()
            .map(tenant -> new TenantView(tenant.tenantId(), tenant.name(), tenant.status(),
                tenant.archived()))
            .toList();
    }

    @PostMapping
    @PreAuthorize("hasPermission('tenant', 'provision')")
    public ProvisionedView provision(@RequestBody ProvisionRequest request) {
        TenantProvisioningService.ProvisionedTenant provisioned = provisioning.provision(
            request.tenantId(), request.name(), request.adminEmail(),
            request.adminName() == null ? request.adminEmail() : request.adminName());
        return new ProvisionedView(provisioned.tenantId(), provisioned.name(),
            provisioned.adminUserId(), provisioned.adminEmail());
    }
}
