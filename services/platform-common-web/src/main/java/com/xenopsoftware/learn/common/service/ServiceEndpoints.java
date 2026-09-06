package com.xenopsoftware.learn.common.service;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the other services are (T-9.11), by name rather than by URL at the call site.
 *
 * <p>Named targets rather than arbitrary ones, deliberately: a relay that takes a URL from a
 * caller is a request-forgery hole, and one that takes a name can only reach somewhere an
 * operator configured.
 */
@ConfigurationProperties(prefix = "platform.services")
public record ServiceEndpoints(Map<String, String> endpoints) {

    public ServiceEndpoints {
        endpoints = endpoints == null ? Map.of() : Map.copyOf(endpoints);
    }

    public String baseUrlOf(String service) {
        String url = endpoints.get(service);
        if (url == null) {
            throw new IllegalArgumentException("No endpoint configured for service " + service
                + "; known: " + endpoints.keySet());
        }
        return url;
    }
}
