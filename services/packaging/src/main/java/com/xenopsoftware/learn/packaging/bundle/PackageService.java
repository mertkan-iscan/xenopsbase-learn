package com.xenopsoftware.learn.packaging.bundle;

import com.xenopsoftware.learn.common.tenancy.TenantContext;
import com.xenopsoftware.learn.packaging.manifest.ManifestReader;
import com.xenopsoftware.learn.packaging.manifest.PackageManifest;
import com.xenopsoftware.learn.packaging.storage.ObjectStore;
import com.xenopsoftware.learn.packaging.storage.StorageProperties;
import com.xenopsoftware.learn.packaging.unpack.PackageRejected;
import com.xenopsoftware.learn.packaging.unpack.SafeUnpacker;
import com.xenopsoftware.learn.packaging.unpack.UnpackLimits;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Creating an upload target, and turning what arrives into a package (T-4.1, T-4.2).
 *
 * <h2>Two calls, and why it is not one</h2>
 *
 * <p>An author's browser sends the archive <b>straight to object storage</b>, using a signed URL
 * this service mints. Nothing in this repository ever holds a request thread open for a 200MB
 * upload on somebody's office wifi — which is streaming's rule for video (T-3.2), applied here for
 * the same reason even though the eventual handling is very different.
 *
 * <p>Then {@link #ingest} is called, and that one <b>does</b> read every byte. It has to: a SCORM
 * archive is third-party code and the entire point of ADR-0105's ingest list is that somebody
 * inspects it before a learner's browser does. What makes holding a thread acceptable here is that
 * the work is bounded before it starts — {@link UnpackLimits} caps the archive, the expansion, the
 * file count and the ratio, so the longest this can run is a function of numbers in a
 * configuration file rather than of what an attacker sent.
 *
 * <h2>Synchronous, and that is a decision rather than a shortcut</h2>
 *
 * <p>The alternative is a queue and a polling client, which is what streaming does for encoding —
 * and encoding genuinely takes minutes, on somebody else's machine. Extraction takes seconds, and
 * the thing an author most needs from this endpoint is the SENTENCE: "rejected, because
 * {@code course/../../etc/passwd} escapes the package root". Delivering that through a state field
 * a screen polls turns a fixable mistake into a support ticket.
 */
@Service
public class PackageService {

    private static final Logger LOG = LoggerFactory.getLogger(PackageService.class);

    private final ContentPackageRepository repository;
    private final ObjectStore store;
    private final StorageProperties storage;
    private final SafeUnpacker unpacker;
    private final ManifestReader manifests;
    private final UnpackLimits limits;

    public PackageService(ContentPackageRepository repository, ObjectStore store,
            StorageProperties storage, SafeUnpacker unpacker, ManifestReader manifests,
            UnpackLimits limits) {
        this.repository = repository;
        this.store = store;
        this.storage = storage;
        this.unpacker = unpacker;
        this.manifests = manifests;
        this.limits = limits;
    }

    /** A row, and the signed URL the browser sends the archive to. */
    public record IssuedUpload(ContentPackage stored, URI uploadUrl, Instant expiresAt) {}

    /**
     * Reserves a package and issues its upload target.
     *
     * <p>The size is checked BEFORE anything is minted, exactly as streaming checks quota before
     * asking its provider for a target: an over-limit request should cost nothing, and an issued
     * target should always be one the archive had room for. The same number is then signed into
     * the URL, so the ceiling is enforced by storage rather than by trust.
     */
    @Transactional
    public IssuedUpload create(PackageKind kind, String sourceName, long declaredBytes) {
        if (declaredBytes <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "An upload has to declare how large it is; the size is signed into the target.");
        }
        if (declaredBytes > limits.maxSourceBytes()) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                "That archive is " + declaredBytes + " bytes, past the " + limits.maxSourceBytes()
                + " byte ceiling for a package. Video does not belong inside a package — upload it "
                + "separately and add it to the course as its own item.");
        }
        ContentPackage stored = repository.saveAndFlush(
            new ContentPackage(kind, trimName(sourceName), declaredBytes));
        URI target = store.presignPut(storage.uploadsBucket(),
            PackageStorageKeys.source(tenant(), stored.getId()),
            declaredBytes, storage.uploadValidFor());
        return new IssuedUpload(stored, target, Instant.now().plus(storage.uploadValidFor()));
    }

    /**
     * Reads the uploaded archive, checks it, extracts what survives, and reads its manifest.
     *
     * <p>Not transactional across the whole body, and that is deliberate: the extraction writes to
     * object storage, which no database transaction can roll back. Holding one open for the
     * duration would give the appearance of atomicity over an operation that does not have it, and
     * would hold a connection for the length of an upload. The row is saved at each state change
     * instead, so a crash halfway leaves a row that says {@code PROCESSING} — which is true, and
     * is a thing an operator can find — rather than a row that says nothing happened.
     */
    public ContentPackage ingest(UUID id) {
        ContentPackage stored = require(id);
        if (stored.getState().isBeingRemoved()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "This package is being deleted.");
        }
        if (stored.getState() == PackageState.READY) {
            // Idempotent for the common cause of a repeat: a client retried, or an author pressed
            // the button twice. Re-extracting would rewrite every object for no change.
            return stored;
        }
        String tenant = tenant();
        String sourceKey = PackageStorageKeys.source(tenant, id);
        if (store.head(storage.uploadsBucket(), sourceKey).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "No archive has been uploaded for this package yet. Send the file to the upload "
                + "target first, then ask for it to be processed.");
        }

        stored.processing();
        repository.saveAndFlush(stored);

        try (ObjectStore.Retrieved archive = store.get(storage.uploadsBucket(), sourceKey)
                .orElseThrow(() -> new IllegalStateException("The archive vanished between the "
                    + "head and the read of " + sourceKey))) {
            SafeUnpacker.Unpacked unpacked = unpacker.unpack(archive.body(),
                (path, body, length, contentType) -> store.put(storage.packagesBucket(),
                    PackageStorageKeys.extracted(tenant, id, path), body, length, contentType));

            PackageManifest manifest = manifests.read(stored.getKind(), unpacked.wanted(),
                unpacked.paths());

            stored.ready(manifest.title(), manifest.entryPath(), manifest.profile(),
                unpacked.fileCount(), unpacked.unpackedBytes(), unpacked.sourceBytes(),
                unpacked.sha256());
            LOG.info("Package {} ready: {} files, {} bytes unpacked, entry {}",
                id, unpacked.fileCount(), unpacked.unpackedBytes(), manifest.entryPath());
        } catch (PackageRejected refused) {
            /*
             * THE AUTHOR'S PROBLEM, AND WHAT IS LEFT BEHIND.
             *
             * Whatever was extracted before the refusal stays in the bucket. That looks untidy and
             * is the right way round: the objects are under this package's own prefix, nothing
             * will ever serve them because a REJECTED package has no launch URL, and sweeping them
             * synchronously would mean a failed delete turning a clear rejection into a 500. The
             * delete sweep takes them when the row is deleted.
             */
            stored.rejected(refused.getMessage());
            LOG.info("Package {} rejected: {}", id, refused.getMessage());
        } catch (RuntimeException | java.io.IOException ours) {
            // Storage unreachable, no temp space, a read that timed out. NOT the author's to fix,
            // so it must not be reported as a rejection -- see PackageState.
            stored.failed(ours.getClass().getSimpleName() + ": " + ours.getMessage());
            LOG.error("Package {} could not be processed", id, ours);
        }
        return repository.saveAndFlush(stored);
    }

    /**
     * Asks for a package to go, and takes the objects with it.
     *
     * <p>202 rather than 204 at the edge, and this is why: the row is marked {@code DELETING}, the
     * objects are swept, and only then does it claim {@code DELETED}. If the sweep fails, the row
     * stays {@code DELETING} — which is honest, and is a state an operator can find — rather than
     * claiming bytes are gone that are not. It is the same two-step T-3.8 uses for video, for the
     * same reason: the promise "this is deleted" is worth nothing if it is made optimistically.
     */
    @Transactional
    public ContentPackage delete(UUID id) {
        ContentPackage stored = require(id);
        stored.deletionRequested();
        repository.saveAndFlush(stored);
        String tenant = tenant();
        try {
            store.deletePrefix(storage.packagesBucket(),
                PackageStorageKeys.extractedPrefix(tenant, id));
            store.deletePrefix(storage.uploadsBucket(),
                PackageStorageKeys.extractedPrefix(tenant, id));
        } catch (RuntimeException notGone) {
            LOG.warn("Could not remove the objects for package {}; it stays DELETING", id, notGone);
            return stored;
        }
        stored.deletionConfirmed();
        return repository.saveAndFlush(stored);
    }

    public List<ContentPackage> list() {
        return repository.findByStateNotOrderByCreatedAtDesc(PackageState.DELETED);
    }

    public ContentPackage require(UUID id) {
        // Tenant-filtered by the persistence layer: another company's package is not found rather
        // than refused, which is the shape ADR-0102 promises.
        return repository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private static String tenant() {
        return TenantContext.require();
    }

    /** A filename is a string an attacker chose; it is stored to say back and never parsed. */
    private static String trimName(String sourceName) {
        if (sourceName == null || sourceName.isBlank()) {
            return null;
        }
        String trimmed = sourceName.strip();
        return trimmed.length() > 255 ? trimmed.substring(0, 255) : trimmed;
    }

}
