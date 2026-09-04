package com.xenopsoftware.learn.identity.sso;

import com.xenopsoftware.learn.common.tenancy.TenantFilter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * The realm, told about a customer's provider over its admin API (T-1.8).
 *
 * <p><b>Where the tenant comes from, in one place.</b> Every provider this writes carries two
 * hardcoded attribute mappers — {@code tenant_id} and {@code side} — whose values come from our
 * own row. Keycloak calls them "hardcoded" because the value is in the mapper rather than read
 * from the assertion, which is exactly the property T-1.8 asks for: a customer's provider can
 * assert {@code tenant_id: globex} all it likes and the mapper overwrites it with the company
 * whose provider it is.
 *
 * <p><b>{@code syncMode: FORCE} is load-bearing.</b> The default is {@code LEGACY}, which applies
 * mappers on first login only. A provider that behaved for a month and then started asserting a
 * different company would, under the default, keep the value it first set — which sounds safe and
 * is: it is also indistinguishable from a mapper that silently stopped running, and the day
 * somebody moves a company's slug the stale value is the one that matters. FORCE re-applies on
 * every login, so the answer is always "whatever our row says now".
 *
 * <p>No mapper is ever accepted from a caller. The connection details are theirs; what the login
 * means is ours.
 */
public class KeycloakRealmProviders implements RealmProviders {

    private static final Logger LOG = LoggerFactory.getLogger(KeycloakRealmProviders.class);

    /** Keycloak's provider id for "set this attribute to this literal value". */
    private static final String HARDCODED = "hardcoded-attribute-idp-mapper";

    private final RestClient http;
    private final SsoProperties.RealmAdmin admin;

    public KeycloakRealmProviders(RestClient.Builder builder, SsoProperties.RealmAdmin admin) {
        // RestClient.builder() at the call site rather than an injected builder, for the reason
        // CloudflareStreamConfiguration records: Boot 4 does not auto-configure one here, and a
        // conditional bean would have hidden the failure until somebody switched this on.
        this.http = builder.baseUrl(admin.url()).build();
        this.admin = admin;
    }

    @Override
    public void apply(TenantProvider provider, ProviderSecrets secrets) {
        String token = adminToken();
        Map<String, Object> representation = new LinkedHashMap<>();
        representation.put("alias", provider.alias());
        representation.put("displayName", provider.displayName());
        representation.put("providerId", provider.kind() == ProviderKind.SAML ? "saml" : "oidc");
        representation.put("enabled", true);
        // Trusted, because the customer's provider is the authority on their own users' email
        // addresses -- and because provisioning refuses to link an unverified email to an
        // existing account (T-1.2), which would otherwise make every federated first login a
        // conflict a human has to resolve.
        representation.put("trustEmail", true);
        // No account linking on email match. Keycloak's "first broker login" flow can be told to
        // attach a federated identity to an existing local account when the emails agree, and
        // that is precisely the takeover ADR-0104 refuses: email ownership must not be equivalent
        // to account ownership.
        representation.put("firstBrokerLoginFlowAlias", "first broker login");
        representation.put("config", connectionConfig(provider, secrets));

        upsert(token, "/admin/realms/" + admin.realm() + "/identity-provider/instances",
            provider.alias(), representation);
        mapper(token, provider, TenantFilter.TENANT_CLAIM, provider.tenantId());
        mapper(token, provider, TenantFilter.SIDE_CLAIM, "TENANT");
        LOG.info("Applied identity provider {} for tenant {}", provider.alias(), provider.tenantId());
    }

    @Override
    public void remove(String alias) {
        try {
            http.delete()
                .uri("/admin/realms/{realm}/identity-provider/instances/{alias}", admin.realm(), alias)
                .header("Authorization", "Bearer " + adminToken())
                .retrieve().toBodilessEntity();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() != 404) {
                throw e;
            }
            // Already gone. The caller wanted it absent and it is.
        }
    }

    /** The customer's half: endpoints and credentials, and nothing that decides what a login means. */
    private static Map<String, Object> connectionConfig(TenantProvider provider, ProviderSecrets secrets) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("syncMode", "FORCE");
        if (provider.kind() == ProviderKind.SAML) {
            config.put("metadataDescriptorUrl", secrets.metadataUrl());
            config.put("useMetadataDescriptorUrl", "true");
            config.put("entityId", secrets.issuer());
            config.put("wantAssertionsSigned", "true");
            config.put("validateSignature", "true");
        } else {
            config.put("issuer", secrets.issuer());
            config.put("clientId", secrets.clientId());
            config.put("clientSecret", secrets.clientSecret());
            config.put("clientAuthMethod", "client_secret_post");
            config.put("defaultScope", "openid email profile");
            config.put("validateSignature", "true");
            config.put("useJwksUrl", "true");
        }
        return config;
    }

    /** One hardcoded attribute mapper, made to match ours whether or not it already exists. */
    private void mapper(String token, TenantProvider provider, String attribute, String value) {
        Map<String, Object> representation = Map.of(
            "name", provider.alias() + "-" + attribute,
            "identityProviderAlias", provider.alias(),
            "identityProviderMapper", HARDCODED,
            "config", Map.of(
                "syncMode", "FORCE",
                "attribute", attribute,
                "attribute.value", value));
        upsertMapper(token, provider.alias(), representation);
    }

    private void upsert(String token, String collection, String id, Map<String, Object> body) {
        try {
            http.post().uri(collection)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body).retrieve().toBodilessEntity();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() != 409) {
                throw e;
            }
            // Already there: make it match. A PUT of the whole representation rather than a
            // merge, so a field somebody edited in the admin console goes back to what our row
            // says -- this table is the source of truth or it is decoration.
            http.put().uri(collection + "/{id}", id)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body).retrieve().toBodilessEntity();
        }
    }

    /**
     * Mappers have no natural id, so "already there" is answered by name: the existing one is
     * deleted and rewritten rather than left beside a second mapper for the same attribute. Two
     * mappers writing one attribute is a coin toss, and it is the coin toss that decides which
     * company somebody lands in.
     */
    private void upsertMapper(String token, String alias, Map<String, Object> representation) {
        String base = "/admin/realms/" + admin.realm() + "/identity-provider/instances/" + alias
            + "/mappers";
        List<Map<String, Object>> existing = http.get().uri(base)
            .header("Authorization", "Bearer " + token)
            .retrieve().body(new org.springframework.core.ParameterizedTypeReference<>() {});
        if (existing != null) {
            for (Map<String, Object> mapper : existing) {
                if (representation.get("name").equals(mapper.get("name"))) {
                    http.delete().uri(base + "/{id}", mapper.get("id"))
                        .header("Authorization", "Bearer " + token)
                        .retrieve().toBodilessEntity();
                }
            }
        }
        http.post().uri(base)
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .body(representation).retrieve().toBodilessEntity();
    }

    /**
     * A client-credentials token for the admin API. The service account needs the realm's
     * {@code manage-identity-providers} role and nothing else — not {@code realm-admin}, which
     * would also let this service edit users, and this service has no business editing users in
     * the realm (ADR-0104: it owns its own identity table, and the realm owns credentials).
     */
    private String adminToken() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", admin.clientId());
        form.add("client_secret", admin.clientSecret());
        Map<String, Object> response = http.post()
            .uri("/realms/{realm}/protocol/openid-connect/token", admin.realm())
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve().body(new org.springframework.core.ParameterizedTypeReference<>() {});
        if (response == null || response.get("access_token") == null) {
            throw new IllegalStateException("Keycloak returned no admin token for "
                + admin.clientId() + "; check that its service account holds manage-identity-providers");
        }
        return String.valueOf(response.get("access_token"));
    }
}
