package com.xenopsoftware.learn.catalog.config;

import com.xenopsoftware.learn.catalog.due.DueProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The service's own wiring (T-5.6).
 *
 * <p>{@code @EnableScheduling} is here as well as on the messaging configuration, deliberately.
 * That one is conditional on {@code platform.outbox.enabled}, so the reminder pass would inherit
 * its scheduler and stop running the day somebody turned the bus off in an environment -- a change
 * about messages that silently stopped the mail. Enabling it twice is harmless; enabling it by
 * accident is not.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(DueProperties.class)
public class CatalogConfiguration {
}
