package com.xenopsoftware.learn.identity.config;

import com.xenopsoftware.learn.identity.authz.AuthzVersion;
import com.xenopsoftware.learn.identity.authz.CachedPermissions;
import com.xenopsoftware.learn.identity.authz.PermissionCacheProperties;
import com.xenopsoftware.learn.identity.authz.UncachedPermissions;
import com.xenopsoftware.learn.identity.authz.ValkeyPermissions;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Which permission cache this installation runs (T-2.5).
 *
 * <p>Two beans, each gated on the same property with opposite values rather than one gated and
 * one {@code @ConditionalOnMissingBean}: the fallback then does not depend on the order the
 * container happens to evaluate bean methods in. Whichever registers, exactly one
 * {@link CachedPermissions} exists, so nothing downstream has an opinion about caching.
 *
 * <p>The bean names are what {@code /management/health} shows — {@code permissionCache} or
 * {@code permissionCacheDisabled}, which answers "is this installation caching?" without anyone
 * having to find the property.
 *
 * <p>Valkey's own health indicator is switched off in {@code application.yml}. It reports DOWN
 * when the cache is unreachable, and DOWN on this service's aggregate health means a rollout
 * stops for a dependency the service is designed to run without. The contributor these beans
 * register says the same thing without lying about whether the service can serve traffic.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PermissionCacheProperties.class)
public class PermissionCacheConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "identity.authz.cache", name = "enabled",
        havingValue = "true", matchIfMissing = true)
    CachedPermissions permissionCache(StringRedisTemplate valkey, AuthzVersion versions,
            PermissionCacheProperties properties, MeterRegistry meters) {
        return new ValkeyPermissions(valkey, versions, properties, meters);
    }

    @Bean
    @ConditionalOnProperty(prefix = "identity.authz.cache", name = "enabled", havingValue = "false")
    CachedPermissions permissionCacheDisabled(MeterRegistry meters) {
        return new UncachedPermissions(meters);
    }
}
