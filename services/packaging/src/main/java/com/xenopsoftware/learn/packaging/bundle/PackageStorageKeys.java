package com.xenopsoftware.learn.packaging.bundle;

import java.util.UUID;

/**
 * Where a package's objects live, in one place.
 *
 * <p><b>Tenant first, always.</b> Every key this service writes or reads begins with the company's
 * id, which makes a storage-level control — a bucket policy, a lifecycle rule, an access grant to
 * a CDN — automatically tenant-scoped without anybody remembering that it should be. It is the
 * same argument ADR-0105 makes for per-tenant origins, applied one layer down.
 */
public final class PackageStorageKeys {

    private PackageStorageKeys() {}

    /**
     * The archive exactly as the author sent it.
     *
     * <p>Kept after a successful ingest, deliberately: it is the evidence behind "this is the
     * course we published in March", and it is what a re-ingest would run against if the
     * extraction ever has to be redone with a corrected allowlist.
     */
    public static String source(String tenantId, UUID packageId) {
        return tenantId + "/" + packageId + "/source.zip";
    }

    /** Everything extracted from that archive, under one prefix so deleting is one sweep. */
    public static String extractedPrefix(String tenantId, UUID packageId) {
        return tenantId + "/" + packageId + "/";
    }

    /** One extracted file. {@code path} has already been through {@code EntryPath}. */
    public static String extracted(String tenantId, UUID packageId, String path) {
        return extractedPrefix(tenantId, packageId) + path;
    }
}
