package com.xenopsoftware.learn.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where the relay sends things, and where the browser goes afterwards (T-10.2).
 *
 * @param identity  the service that owns people, tenants and permissions
 * @param streaming playback tokens and progress
 * @param reporting telemetry ingest
 * @param catalog   what training exists, who it reaches, and what is pinned inside a video
 * @param assessment banks, questions, tests, attempts and marking
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
        @DefaultValue("http://localhost:8085") String catalog,
        @DefaultValue("http://localhost:8086") String assessment,
        @DefaultValue("http://localhost:8080") String appUrl) {
}
