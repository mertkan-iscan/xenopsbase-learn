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

    // ---- Deletion (T-3.8). The record of intent; `deletedAt` is the record of fact. ----

    @Column(name = "deletion_requested_at")
    private Instant deletionRequestedAt;

    /** An app_user id resolved through identity, never a token subject (ADR-0104). */
    @Column(name = "deletion_requested_by")
    private UUID deletionRequestedBy;

    @Column(name = "deletion_reason", length = 500)
    private String deletionReason;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "deletion_attempts", nullable = false)
    private int deletionAttempts;

    @Column(name = "deletion_error", columnDefinition = "text")
    private String deletionError;

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

    /**
     * Record that somebody has asked for this video to be deleted (T-3.8).
     *
     * <p>This does not delete anything. It stops playback and puts the row in the reconciler's
     * queue, and that ordering is the whole guarantee: the claim "this is deleted" is only made by
     * {@link #deletionConfirmed()}, after a provider has said so.
     *
     * <p>Idempotent for a repeated request, and deliberately: a customer pressing delete twice is
     * not an error, and resetting the reason or the actor on the second press would overwrite the
     * record of who actually asked.
     */
    public void deletionRequested(UUID actor, String reason) {
        if (state == VideoAssetState.DELETING || state == VideoAssetState.DELETED) {
            return;
        }
        this.state = VideoAssetState.DELETING;
        this.deletionRequestedAt = Instant.now();
        this.deletionRequestedBy = actor;
        this.deletionReason = reason == null || reason.isBlank() ? null : reason.strip();
        this.deletionAttempts = 0;
        this.deletionError = null;
    }

    /** The provider has confirmed. The only place this row is allowed to claim the bytes are gone. */
    public void deletionConfirmed() {
        this.state = VideoAssetState.DELETED;
        this.deletedAt = Instant.now();
        this.deletionError = null;
    }

    /**
     * An attempt failed. The row stays DELETING and is retried; the count and the message are what
     * turn "still not deleted" from a silence into something an operator can escalate.
     */
    public void deletionFailed(String error) {
        this.deletionAttempts++;
        this.deletionError = error;
    }

    /** Whether this asset is on its way out or already gone — either way, it never plays again. */
    public boolean isBeingRemoved() {
        return state == VideoAssetState.DELETING || state == VideoAssetState.DELETED;
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

    public Instant getDeletionRequestedAt() {
        return deletionRequestedAt;
    }

    public UUID getDeletionRequestedBy() {
        return deletionRequestedBy;
    }

    public String getDeletionReason() {
        return deletionReason;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public int getDeletionAttempts() {
        return deletionAttempts;
    }

    public String getDeletionError() {
        return deletionError;
    }

    public Instant getUploadTargetExpiresAt() {
        return uploadTargetExpiresAt;
    }
}
