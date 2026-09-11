package com.xenopsoftware.learn.packaging.bundle;

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
 * An uploaded archive, and what became of it (T-4.1, T-4.2).
 *
 * <p>{@link #id} is the {@code packageId} catalog stores in a content item's payload. Everything
 * else about the package — what it is called, which file a launch opens, whether it may be
 * launched at all — is asked for here rather than copied there, which is the same data-ownership
 * rule (ADR-0109) that keeps a video's duration out of catalog's tables.
 *
 * <p><b>Every field the manifest fills in is written once, by the ingest, after validation.</b>
 * {@link #entryPath} in particular is the only string in this schema that a URL is built from, and
 * an archive chose it — so it arrives already normalised and already proven to be inside the
 * package root, and there is no setter that takes one from anywhere else.
 */
@Entity
@Table(name = "content_package")
public class ContentPackage extends TenantOwned {

    @Id
    @UuidGenerator
    @Column(name = "id")
    private UUID id;

    @Column(name = "kind", nullable = false, length = 16)
    private String kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 24)
    private PackageState state;

    @Column(name = "source_name", length = 255)
    private String sourceName;

    @Column(name = "declared_bytes", nullable = false)
    private long declaredBytes;

    @Column(name = "source_bytes")
    private Long sourceBytes;

    @Column(name = "source_sha256", length = 64)
    private String sourceSha256;

    @Column(name = "title", length = 512)
    private String title;

    @Column(name = "entry_path", length = 1024)
    private String entryPath;

    @Column(name = "profile", length = 32)
    private String profile;

    @Column(name = "file_count")
    private Integer fileCount;

    @Column(name = "unpacked_bytes")
    private Long unpackedBytes;

    @Column(name = "error", columnDefinition = "text")
    private String error;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deletion_requested_at")
    private Instant deletionRequestedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected ContentPackage() {}

    public ContentPackage(PackageKind kind, String sourceName, long declaredBytes) {
        this.kind = kind.code();
        this.sourceName = sourceName;
        this.declaredBytes = declaredBytes;
        this.state = PackageState.PENDING_UPLOAD;
    }

    /** The archive is being read. Sets the state the ingest holds while it works. */
    public void processing() {
        this.state = PackageState.PROCESSING;
        this.error = null;
    }

    /**
     * Everything passed.
     *
     * @param entryPath already normalised and already proven inside the package root — this method
     *                  does not re-check it, and is not the place to start
     */
    public void ready(String title, String entryPath, String profile, int fileCount,
            long unpackedBytes, long sourceBytes, String sourceSha256) {
        this.state = PackageState.READY;
        this.title = title;
        this.entryPath = entryPath;
        this.profile = profile;
        this.fileCount = fileCount;
        this.unpackedBytes = unpackedBytes;
        this.sourceBytes = sourceBytes;
        this.sourceSha256 = sourceSha256;
        this.error = null;
    }

    /** A check refused the archive. The author is the one who can act on {@code why}. */
    public void rejected(String why) {
        this.state = PackageState.REJECTED;
        this.error = why;
    }

    /** Something on our side broke. Not the author's problem, and retryable. */
    public void failed(String why) {
        this.state = PackageState.FAILED;
        this.error = why;
    }

    /**
     * Somebody asked for this package to go (T-3.8's two-step, applied here).
     *
     * <p>Idempotent: pressing delete twice is not an error, and re-stamping the timestamp would
     * overwrite the record of when it was actually asked for.
     */
    public void deletionRequested() {
        if (state.isBeingRemoved()) {
            return;
        }
        this.state = PackageState.DELETING;
        this.deletionRequestedAt = Instant.now();
    }

    /** Storage has confirmed. The only place this row may claim the bytes are gone. */
    public void deletionConfirmed() {
        this.state = PackageState.DELETED;
        this.deletedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public PackageKind getKind() {
        return PackageKind.of(kind);
    }

    public PackageState getState() {
        return state;
    }

    public String getSourceName() {
        return sourceName;
    }

    public long getDeclaredBytes() {
        return declaredBytes;
    }

    public Long getSourceBytes() {
        return sourceBytes;
    }

    public String getSourceSha256() {
        return sourceSha256;
    }

    public String getTitle() {
        return title;
    }

    /** The file a launch opens, relative to the package root. Null until the ingest has run. */
    public String getEntryPath() {
        return entryPath;
    }

    /** {@code scorm-1.2}, {@code scorm-2004}, {@code cmi5}, or null when the kind has no runtime. */
    public String getProfile() {
        return profile;
    }

    public Integer getFileCount() {
        return fileCount;
    }

    public Long getUnpackedBytes() {
        return unpackedBytes;
    }

    public String getError() {
        return error;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
