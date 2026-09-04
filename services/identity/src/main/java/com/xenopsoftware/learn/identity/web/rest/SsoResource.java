package com.xenopsoftware.learn.identity.web.rest;

import com.xenopsoftware.learn.common.tenancy.TenantContext;
import com.xenopsoftware.learn.identity.sso.ProviderKind;
import com.xenopsoftware.learn.identity.sso.RealmProviders;
import com.xenopsoftware.learn.identity.sso.TenantProvider;
import com.xenopsoftware.learn.identity.sso.TenantSso;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * A company configuring its own identity provider (T-1.8).
 *
 * <p>"Applied without hand-editing the realm" is the criterion, and this is what it means in
 * practice: an administrator posts their provider here, and the service writes the realm. Nobody
 * opens a JSON file, and — more to the point — nobody hand-writes the attribute mapper that
 * decides which company the provider's users land in.
 *
 * <p><b>No mapper configuration is accepted.</b> The request carries endpoints and credentials,
 * which are the customer's business, and nothing that says what a login means, which is ours.
 */
@RestController
@RequestMapping("/api/v1/sso")
public class SsoResource {

    private final TenantSso sso;

    public SsoResource(TenantSso sso) {
        this.sso = sso;
    }

    /**
     * @param clientSecret write-only. It goes to the realm and is never stored here or read back:
     *                     this service holds no credential, not even a customer's (T-1.5)
     */
    public record ProviderRequest(String alias, String kind, String displayName, String issuer,
                                  String clientId, String clientSecret, String metadataUrl) {}

    public record ProviderView(String alias, String kind, String displayName, boolean applied,
                               Instant appliedAt) {}

    public record DomainRequest(String domain) {}

    /**
     * @param dnsName what to publish the record at, spelled out rather than left for the customer
     *                to assemble from a hostname and a prefix
     */
    public record DomainView(UUID id, String domain, String dnsName, String txtValue,
                             boolean verified, Instant verifiedAt) {}

    @GetMapping("/providers")
    @PreAuthorize("hasPermission('sso', 'manage')")
    public List<ProviderView> providers() {
        return sso.providersOf(TenantContext.require()).stream().map(SsoResource::view).toList();
    }

    @PostMapping("/providers")
    @PreAuthorize("hasPermission('sso', 'manage')")
    public ProviderView register(@RequestBody ProviderRequest request) {
        ProviderKind kind;
        try {
            kind = ProviderKind.valueOf(request.kind() == null ? "" : request.kind().toUpperCase(
                java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "kind must be OIDC or SAML");
        }
        String displayName = request.displayName() == null || request.displayName().isBlank()
            ? request.alias() : request.displayName();
        return view(sso.register(request.alias(), kind, displayName,
            new RealmProviders.ProviderSecrets(request.issuer(), request.clientId(),
                request.clientSecret(), request.metadataUrl())));
    }

    @DeleteMapping("/providers/{alias}")
    @PreAuthorize("hasPermission('sso', 'manage')")
    public void unregister(@PathVariable String alias) {
        sso.unregister(alias);
    }

    @GetMapping("/domains")
    @PreAuthorize("hasPermission('sso', 'manage')")
    public List<DomainView> domains() {
        return sso.domainsOf(TenantContext.require()).stream().map(SsoResource::view).toList();
    }

    /** Claims a domain and returns what must be published to prove it. Idempotent per domain. */
    @PostMapping("/domains")
    @PreAuthorize("hasPermission('sso', 'manage')")
    public DomainView claim(@RequestBody DomainRequest request) {
        return view(sso.claim(request.domain()));
    }

    /** Checks the DNS record now. Repeatable: a customer publishes the record and asks again. */
    @PostMapping("/domains/{id}/verify")
    @PreAuthorize("hasPermission('sso', 'manage')")
    public DomainView verify(@PathVariable UUID id) {
        return view(sso.verify(id));
    }

    private static ProviderView view(TenantProvider provider) {
        return new ProviderView(provider.alias(), provider.kind().name(), provider.displayName(),
            provider.appliedAt() != null, provider.appliedAt());
    }

    private static DomainView view(TenantSso.DomainView domain) {
        return new DomainView(domain.id(), domain.domain(),
            domain.record() + "." + domain.domain(), domain.verificationToken(),
            domain.verifiedAt() != null, domain.verifiedAt());
    }
}
