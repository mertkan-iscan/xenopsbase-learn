package com.xenopsoftware.learn.identity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * Tenants, users, groups, roles and permissions.
 *
 * <p>Whether this runs as its own process or inside a merged {@code core} is still open
 * (ADR-0109). Nothing in this service may depend on the answer: it owns its database, it is
 * reached through its published interface, and it never reads another module's schema. That is
 * what makes the decision a deployment change rather than a rewrite.
 */
@SpringBootApplication
@ComponentScan({"com.xenopsoftware.learn.identity", "com.xenopsoftware.learn.common"})
public class IdentityApp {

    public static void main(String[] args) {
        SpringApplication.run(IdentityApp.class, args);
    }
}
