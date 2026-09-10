package com.xenopsoftware.learn.packaging;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.ComponentScan;

/**
 * SCORM, cmi5 and slide packages: ingest, validation, storage and delivery (T-4.1, T-4.2, T-4.3).
 *
 * <p><b>A separate process because of what it holds and what it serves</b>, not because of load.
 * This is the only module that opens an archive a customer uploaded, and the only one whose
 * responses a learner's browser reads from an origin that is not the application's. ADR-0105 makes
 * both of those isolation boundaries; putting them in a process that also answers the API the
 * application's session talks to would make the second one a routing detail.
 *
 * <p>{@code @ConfigurationPropertiesScan} rather than a class-by-class {@code @EnableConfigurationProperties}:
 * every properties record in this service is a record in its own package next to the thing it
 * configures, and listing them here would be a list somebody forgets to add to.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@ComponentScan({"com.xenopsoftware.learn.packaging", "com.xenopsoftware.learn.common"})
public class PackagingApp {

    public static void main(String[] args) {
        SpringApplication.run(PackagingApp.class, args);
    }
}
