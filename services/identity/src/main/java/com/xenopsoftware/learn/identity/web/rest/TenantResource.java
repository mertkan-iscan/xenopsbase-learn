package com.xenopsoftware.learn.identity.web.rest;

import com.xenopsoftware.learn.common.tenancy.AccountStatus;
import com.xenopsoftware.learn.identity.tenant.TenantProvisioningService;
import com.xenopsoftware.learn.identity.tenant.TenantStatusService;
import com.xenopsoftware.learn.identity.tenant.Tenants;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
    private final TenantStatusService status;
    private final Tenants tenants;

    public TenantResource(TenantProvisioningService provisioning, TenantStatusService status,
            Tenants tenants) {
        this.provisioning = provisioning;
        this.status = status;
        this.tenants = tenants;
    }

    public record ProvisionRequest(String tenantId, String name, String adminEmail,
                                   String adminName) {}

    public record StatusRequest(String status, String reason) {}

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

    /**
     * Suspending, restricting to read-only, or reinstating a company (T-1.4).
     *
     * <p>What the customer sees changes on their next request, because the change publishes the
     * entry the edge reads. What it cannot do is recall a playback token already issued: that
     * bound is the token TTL and nothing else (ADR-0101), which is why T-3.4 keeps it short.
     */
    @PutMapping("/{tenantId}/status")
    @PreAuthorize("hasPermission('tenant', 'suspend')")
    public TenantView setStatus(@PathVariable String tenantId, @RequestBody StatusRequest request) {
        AccountStatus wanted;
        try {
            wanted = AccountStatus.valueOf(request.status());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST,
                "status must be ACTIVE, READ_ONLY or SUSPENDED");
        }
        status.change(tenantId, wanted, request.reason());
        return tenants.find(tenantId)
            .map(tenant -> new TenantView(tenant.tenantId(), tenant.name(), tenant.status(),
                tenant.archived()))
            .orElseThrow();
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
