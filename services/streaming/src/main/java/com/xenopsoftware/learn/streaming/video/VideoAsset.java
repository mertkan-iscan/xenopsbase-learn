package com.xenopsoftware.learn.streaming.video;

import com.xenopsoftware.learn.common.tenancy.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * A video as this platform knows it (T-3.1, T-3.2). {@link #id} is what everything else
 * references — {@code content_item} (T-5.1) included; the (provider, providerRef) pair is the
 * only place the delivery vendor's identifier exists, and it is opaque.
 */
@Entity
@Table(name = "video_asset")
public class VideoAsset extends TenantOwned {

    @Id
    @UuidGenerator
    @Column(name = "id")
    private UUID id;

    @Column(name = "provider", nullable = false)
    private String provider;

    @Column(name = "provider_ref", nullable = false)
    private String providerRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    private VideoAssetState state;

    @Column(name = "duration_seconds")
    private Double durationSeconds;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    /** The ceiling declared at creation; a re-issued target repeats it to the provider. */
    @Column(name = "max_duration_seconds", nullable = false)
    private long maxDurationSeconds;

    @Column(name = "upload_target_expires_at")
    private Instant uploadTargetExpiresAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected VideoAsset() {}

    public VideoAsset(String provider, String providerRef, long sizeBytes, long maxDurationSeconds,
            Instant uploadTargetExpiresAt) {
        this.provider = provider;
        this.providerRef = providerRef;
        this.sizeBytes = sizeBytes;
        this.maxDurationSeconds = maxDurationSeconds;
        this.uploadTargetExpiresAt = uploadTargetExpiresAt;
        this.state = VideoAssetState.PENDING_UPLOAD;
    }

    /**
     * A fresh target for an upload that expired or failed before finishing. Only meaningful
     * while PENDING_UPLOAD — the service guards that; this just records the swap.
     */
    public void replaceUploadTarget(String newProviderRef, Instant expiresAt) {
        this.providerRef = newProviderRef;
        this.uploadTargetExpiresAt = expiresAt;
    }

    public UUID getId() {
        return id;
    }

    public String getProvider() {
        return provider;
    }

    public String getProviderRef() {
        return providerRef;
    }

    public VideoAssetState getState() {
        return state;
    }

    public Double getDurationSeconds() {
        return durationSeconds;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public long getMaxDurationSeconds() {
        return maxDurationSeconds;
    }

    public Instant getUploadTargetExpiresAt() {
        return uploadTargetExpiresAt;
    }
}
