package com.xenopsoftware.learn.identity.config;

import com.xenopsoftware.learn.identity.sso.KeycloakRealmProviders;
import com.xenopsoftware.learn.identity.sso.RealmProviders;
import com.xenopsoftware.learn.identity.sso.RecordingRealmProviders;
import com.xenopsoftware.learn.identity.sso.SsoProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Which realm this installation can actually write to (T-1.8).
 *
 * <p>One bean either way, chosen by whether the credentials are there, so nothing downstream has
 * an opinion about whether a realm is reachable. Half-configured counts as absent and says so:
 * three of four properties set is a deployment somebody got most of the way through, and failing
 * to start would be a worse answer than running with providers recorded and unapplied — the API
 * reports them as unapplied, so the gap is visible without being fatal.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SsoProperties.class)
public class SsoConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(SsoConfiguration.class);

    @Bean
    RealmProviders realmProviders(SsoProperties properties) {
        SsoProperties.RealmAdmin admin = properties.realmAdmin();
        if (admin == null || !admin.isComplete()) {
            LOG.warn("No realm admin credentials (identity.sso.realm-admin.*): customer identity "
                + "providers will be recorded but not applied to Keycloak.");
            return new RecordingRealmProviders();
        }
        // RestClient.builder() rather than an injected builder: Boot 4 does not auto-configure
        // one here, and this bean is conditional -- so the failure would have waited for the
        // first installation that actually configured a realm.
        return new KeycloakRealmProviders(RestClient.builder(), admin);
    }
}
