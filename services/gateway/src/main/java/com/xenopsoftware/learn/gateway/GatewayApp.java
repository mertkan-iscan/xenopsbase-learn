package com.xenopsoftware.learn.gateway;

import com.xenopsoftware.learn.gateway.config.GatewayProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * The one origin a browser talks to (T-10.2, ADR-0109).
 *
 * <p>Two jobs and nothing else: it signs a person in, and it relays their calls inward with a
 * token they never see. It owns no table, no domain rule and no screen — a decision worth
 * defending, because an edge process that starts answering questions of its own becomes the place
 * every future feature is cheapest to put, and then it is the monolith the eight modules exist to
 * avoid.
 */
@SpringBootApplication
@EnableConfigurationProperties(GatewayProperties.class)
public class GatewayApp {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApp.class, args);
    }
}
