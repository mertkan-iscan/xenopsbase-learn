package com.xenopsoftware.learn.identity.config;

import com.xenopsoftware.learn.identity.user.InvitationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * The two numbers the person lifecycle needs from configuration (T-1.9): how long an invitation
 * stands, and how large one import may be. Both have defaults that work, and both are the kind
 * of number that becomes a mystery if it is only ever a literal in a method.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(InvitationProperties.class)
public class UserLifecycleConfiguration {}
