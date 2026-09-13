package com.xenopsoftware.learn.packaging.storage;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;

/**
 * Object storage, as a port (T-4.1).
 *
 * <p>A port for the same reason {@code MediaProvider} is one in streaming, and for a narrower one:
 * every call this platform makes is plain S3 — no bucket notifications, no lifecycle API, no
 * region-specific behaviour — so the adapter behind this runs unchanged against MinIO in the local
 * stack, Hetzner Object Storage, or R2. What the interface refuses to express is the shape of that
 * promise: nothing here can reach for an AWS-only service, because there is nowhere to put it.
 *
 * <p><b>Two buckets, and the difference between them is who may read.</b> The uploads bucket holds
 * the archive exactly as an author sent it and is never served to anybody; the packages bucket
 * holds what survived validation and is read by browsers, through the content origin and only
 * through it (ADR-0105). Both are private to our credentials — see
 * {@code local/minio/init.sh}, which sets neither public.
 */
public interface ObjectStore {

    /** An object that exists, described without reading it. */
    record Stored(String key, long sizeBytes, String contentType) {}

    /**
     * An object being read.
     *
     * <p>{@link AutoCloseable} so a caller can put it in a try-with-resources and cannot forget:
     * an unclosed S3 response stream holds a connection out of a bounded pool, and the symptom
     * of leaking them is not an error but a service that stops answering after a few hundred
     * ingests.
     */
    record Retrieved(InputStream body, long sizeBytes, String contentType) implements AutoCloseable {

        @Override
        public void close() throws java.io.IOException {
            body.close();
        }
    }

    /**
     * A URL a browser may {@code PUT} the archive to, directly.
     *
     * <p><b>This is why an author can upload a 200MB course without a request thread here holding
     * it.</b> The bytes go browser → storage; this service learns about them afterwards, when it
     * is asked to ingest them, and reads them on its own terms with its own limits.
     *
     * @param contentLength the size the client declared. Signed INTO the URL, so a client that
     *                      declared 10MB cannot then send 10GB — the signature covers the header,
     *                      and storage refuses the mismatch before a byte is stored
     */
    URI presignPut(String bucket, String key, long contentLength, Duration validFor);

    /** Reads an object, or empty when there is no such key. */
    Optional<Retrieved> get(String bucket, String key);

    /** Describes an object without reading it, or empty when there is no such key. */
    Optional<Stored> head(String bucket, String key);

    /**
     * Writes one object.
     *
     * @param contentType set by US, from the extension allowlist, and never guessed from the
     *                    bytes or taken from the archive (ADR-0105). A package that could choose
     *                    its own content type could serve HTML as an image
     */
    void put(String bucket, String key, InputStream body, long length, String contentType);

    /** Removes everything under a prefix. Used when a package is deleted, and by nothing else. */
    void deletePrefix(String bucket, String prefix);
}
