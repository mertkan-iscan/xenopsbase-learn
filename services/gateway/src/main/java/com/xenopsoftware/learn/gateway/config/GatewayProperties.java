package com.xenopsoftware.learn.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where the relay sends things, and where the browser goes afterwards (T-10.2).
 *
 * @param identity  the service that owns people, tenants and permissions
 * @param streaming playback tokens and progress
 * @param reporting telemetry ingest
 * @param appUrl    the browser-facing origin, used to build the post-sign-out return address.
 *                  Configured rather than derived from the request, because a Host header is
 *                  something a caller sends: deriving a redirect from one is how an open redirect
 *                  is written by accident
 */
@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(
        @DefaultValue("http://localhost:8082") String identity,
        @DefaultValue("http://localhost:8083") String streaming,
        @DefaultValue("http://localhost:8084") String reporting,
        @DefaultValue("http://localhost:8080") String appUrl) {
}
