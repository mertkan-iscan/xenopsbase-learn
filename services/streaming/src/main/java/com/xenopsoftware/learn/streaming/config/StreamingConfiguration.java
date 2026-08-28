package com.xenopsoftware.learn.streaming.config;

import com.xenopsoftware.learn.streaming.video.UploadProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Scheduling exists for exactly one job so far — the upload reaper (T-3.2). Scheduled work runs
 * on a thread that binds no tenant, which is why the reaper is plain JDBC; anything scheduled
 * that touches tenant data through JPA will meet the T-1.1 resolver's refusal, on purpose.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(UploadProperties.class)
public class StreamingConfiguration {}
