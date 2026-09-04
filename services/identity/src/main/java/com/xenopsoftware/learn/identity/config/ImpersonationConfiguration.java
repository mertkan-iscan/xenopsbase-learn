package com.xenopsoftware.learn.identity.config;

import com.xenopsoftware.learn.identity.impersonation.ImpersonationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds the impersonation limits an operator sets (T-2.8). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ImpersonationProperties.class)
public class ImpersonationConfiguration {
}
