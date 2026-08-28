package com.xenopsoftware.learn.streaming.video;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Tenant-filtered by the persistence layer (T-1.1) — including the quota sum, which is the query
 * where forgetting the filter would bill one tenant for another's library.
 */
public interface VideoAssetRepository extends JpaRepository<VideoAsset, UUID> {

    /**
     * The bytes this tenant is currently accountable for: everything except what was reaped or
     * failed. PENDING_UPLOAD counts — a quota that ignores issued-but-unfinished targets can be
     * oversubscribed arbitrarily by asking for targets in parallel.
     */
    @Query("""
        select coalesce(sum(a.sizeBytes), 0)
          from VideoAsset a
         where a.state in (com.xenopsoftware.learn.streaming.video.VideoAssetState.PENDING_UPLOAD,
                           com.xenopsoftware.learn.streaming.video.VideoAssetState.PROCESSING,
                           com.xenopsoftware.learn.streaming.video.VideoAssetState.READY)
        """)
    long accountableSizeBytes();
}
