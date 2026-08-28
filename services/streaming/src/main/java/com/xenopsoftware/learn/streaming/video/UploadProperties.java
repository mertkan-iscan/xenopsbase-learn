package com.xenopsoftware.learn.streaming.video;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The upload rules, enforced before a target is issued (T-3.2). {@code tenantQuotaBytes} is one
 * number for every tenant until the tenant table (T-1.5) gives it a per-tenant home — a default
 * to override, not a design.
 *
 * @param maxSizeBytes     ceiling per upload; checked against the declared size
 * @param tenantQuotaBytes ceiling per tenant across everything accountable
 * @param abandonAfter     how long a PENDING_UPLOAD may sit untouched before the reaper takes it
 */
@ConfigurationProperties(prefix = "streaming.upload")
public record UploadProperties(long maxSizeBytes, long tenantQuotaBytes, Duration abandonAfter) {}
