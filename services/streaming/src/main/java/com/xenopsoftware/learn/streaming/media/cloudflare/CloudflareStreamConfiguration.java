package com.xenopsoftware.learn.streaming.media.cloudflare;

import com.xenopsoftware.learn.streaming.media.MediaProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * The adapter wires itself, inside its own package — deliberately not in a shared configuration
 * class, because a shared class naming {@code CloudflareStreamAdapter} would itself be the leak
 * the ArchUnit rule forbids. Selecting a provider is one property; nothing outside this package
 * knows which class answers.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "streaming.media.provider", havingValue = CloudflareStreamAdapter.PROVIDER_ID)
@EnableConfigurationProperties(CloudflareStreamProperties.class)
public class CloudflareStreamConfiguration {

    @Bean
    MediaProvider cloudflareStream(RestClient.Builder restClientBuilder, CloudflareStreamProperties properties) {
        return new CloudflareStreamAdapter(restClientBuilder, properties);
    }
}
