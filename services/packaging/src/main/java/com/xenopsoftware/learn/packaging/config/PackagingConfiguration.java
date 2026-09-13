package com.xenopsoftware.learn.packaging.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The service's own wiring (T-4.4).
 *
 * <p>Properties are picked up by {@code @ConfigurationPropertiesScan} on {@code PackagingApp}
 * rather than listed here, so this holds only what has nowhere else to live.
 */
@Configuration(proxyBeanMethods = false)
public class PackagingConfiguration {

    /**
     * The clock everything that reasons about time takes as an argument.
     *
     * <p>A bean rather than {@code Instant.now()} scattered through services, so a test can move
     * time — and so every timestamp in one save is reckoned against the same instant. That matters
     * more here than it looks: a runtime's {@code updated_at} and the {@code completedAt} inside
     * the completion event it announces have to agree, and two calls to the system clock a
     * microsecond apart are two different answers in a compliance record.
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
