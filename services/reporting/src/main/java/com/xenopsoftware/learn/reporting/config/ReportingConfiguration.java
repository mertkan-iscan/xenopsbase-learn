package com.xenopsoftware.learn.reporting.config;

import com.xenopsoftware.learn.reporting.telemetry.IngestProperties;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * What ingest needs wired (T-3.6).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IngestProperties.class)
public class ReportingConfiguration {

    /**
     * A bean rather than {@code Instant.now()} at the call site, so a test can put a sample's
     * observation an hour in the past and assert the lag metric reports an hour — which is the
     * measurement this task owes and the one that cannot be checked against a moving clock.
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
