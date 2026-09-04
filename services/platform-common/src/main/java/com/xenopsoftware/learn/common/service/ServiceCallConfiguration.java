package com.xenopsoftware.learn.common.service;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Turns on the shared service-to-service machinery (T-9.11) for every service that scans
 * {@code com.xenopsoftware.learn.common} — which is all of them, by the template.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ServiceEndpoints.class)
public class ServiceCallConfiguration {
}
