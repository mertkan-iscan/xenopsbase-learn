package com.xenopsoftware.learn.packaging.storage;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the objects live (T-4.1, T-9.9).
 *
 * <p>The bucket names are the ones {@code local/minio/init.sh} creates and the ones the real
 * object storage will use, so the adapter and its configuration do not change between a laptop and
 * a cluster — only {@link #endpoint} and the credentials do.
 *
 * @param endpoint        the S3 endpoint. MinIO locally; the provider's URL in a cluster
 * @param region          S3 requires one in the signature even where the provider ignores it
 * @param accessKey       credentials for OUR calls. Nothing derived from these ever reaches a
 *                        browser: the presigned URL carries a signature over one request, not a key
 * @param secretKey       see above
 * @param pathStyle       true for MinIO, whose buckets are a path segment rather than a subdomain.
 *                        Virtual-host style needs wildcard DNS the local stack does not have
 * @param uploadsBucket   archives exactly as an author sent them. Never served to anybody
 * @param packagesBucket  what survived validation. Read by browsers, through the content origin
 *                        and only through it (ADR-0105)
 * @param uploadValidFor  how long an issued upload target works. Long enough for a big archive on
 *                        a bad connection, short enough that a leaked URL is not a standing write
 */
@ConfigurationProperties(prefix = "packaging.storage")
public record StorageProperties(
        String endpoint,
        String region,
        String accessKey,
        String secretKey,
        boolean pathStyle,
        String uploadsBucket,
        String packagesBucket,
        Duration uploadValidFor) {

    public StorageProperties {
        region = region == null || region.isBlank() ? "us-east-1" : region;
        uploadsBucket = uploadsBucket == null || uploadsBucket.isBlank() ? "uploads" : uploadsBucket;
        packagesBucket = packagesBucket == null || packagesBucket.isBlank() ? "packages" : packagesBucket;
        uploadValidFor = uploadValidFor == null ? Duration.ofHours(2) : uploadValidFor;
    }
}
