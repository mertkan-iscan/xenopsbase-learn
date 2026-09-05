package com.xenopsoftware.learn.catalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * Content items, courses, modules, gates and assignments.
 *
 * <p><b>ADR-0109 says this module starts inside {@code core}, beside {@code identity} and
 * {@code assessment}, and this class is not that.</b> It is its own application because the merge
 * is a deployment change by that ADR's own argument — separate databases, separate migrations, an
 * enforced boundary — and doing it while three services are mid-flight onto a cluster would break
 * manifests to buy nothing the code needs. What the ADR requires of the code is true here from the
 * first commit: this module owns {@code catalog_db} outright and reads nobody else's schema.
 *
 * <p>Which is the part worth watching. Catalog is the module most likely to break that rule,
 * because "just cache the video's duration here" is a reasonable-sounding request and a content
 * item already points at a streaming asset. It points and asks; it does not copy.
 */
@SpringBootApplication
@ComponentScan({"com.xenopsoftware.learn.catalog", "com.xenopsoftware.learn.common"})
public class CatalogApp {

    public static void main(String[] args) {
        SpringApplication.run(CatalogApp.class, args);
    }
}
