package com.xenopsoftware.learn.packaging.unpack;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Unpacking an archive from a stranger (ADR-0105's ingest list, T-4.1).
 *
 * <h2>Everything here is checked against bytes that were actually read</h2>
 *
 * <p>A ZIP declares each entry's uncompressed size in its central directory, and that number was
 * written by whoever built the archive. {@link ZipInputStream} is used rather than
 * {@code ZipFile} precisely because it cannot consult that directory: it decompresses forwards and
 * this class counts what comes out, so a bomb is stopped at the byte that crosses the limit rather
 * than believed when it says it is small.
 *
 * <h2>Where the extracted files go, and why that changes one of the ADR's checks</h2>
 *
 * <p>Entries are written to OBJECT STORAGE, not to a filesystem. That removes a whole class of
 * escape — there is no working directory to climb out of, no {@code open(2)} to follow a
 * symlink, no permission bits to honour — and it changes what one item on the ADR's list means.
 * <b>Symlink entries are not refused; they are stored as what they are, a short file containing a
 * path string, which is inert because nothing ever resolves it.</b> That is worth stating plainly
 * rather than leaving as an unimplemented bullet: the day somebody adds a "download this package
 * as a ZIP" feature that writes to a real filesystem, the check has to come back, and this
 * paragraph is where they will find out why it was safe to omit.
 *
 * <p>{@link EntryPath} still normalises every name and refuses everything that could leave the
 * root. An object key is not a path, but it is concatenated into one — the prefix of the tenant
 * and the package — and a key of {@code ../other-tenant/x} would be exactly as bad in a bucket as
 * on a disk.
 *
 * <h2>Entries that are not on the allowlist are dropped, not refused</h2>
 *
 * <p>Real exports contain {@code .DS_Store}, {@code Thumbs.db}, source maps and a designer's
 * working files. Refusing the course because a Mac wrote a metadata file into the ZIP would refuse
 * every course exported on a Mac. They are simply never written, so there is nothing to serve.
 */
@Component
public class SafeUnpacker {

    private static final Logger LOG = LoggerFactory.getLogger(SafeUnpacker.class);

    /**
     * Files whose CONTENT the ingest needs, kept in memory as they go past.
     *
     * <p>The manifest is read after extraction, and reading it back out of object storage would be
     * a second round trip for a file this class has just had in its hands. The cap below is what
     * keeps "keep it in memory" from being a way to make this service allocate whatever an
     * attacker likes.
     */
    private static final Set<String> WANTED = Set.of("imsmanifest.xml", "cmi5.xml", "tincan.xml");

    /** A manifest larger than this is not a manifest. Real ones are tens of kilobytes. */
    private static final int MAX_WANTED_BYTES = 4 * 1024 * 1024;

    private final UnpackLimits limits;

    public SafeUnpacker(UnpackLimits limits) {
        this.limits = limits;
    }

    /** Where each surviving entry is written. Implemented by the ingest against object storage. */
    public interface Sink {
        void write(String path, InputStream body, long length, String contentType);
    }

    /**
     * What came out.
     *
     * @param paths       every path written, in archive order — the manifest reader resolves
     *                    relative hrefs against this, and a launch is refused if the entry it
     *                    names is not in here
     * @param wanted      the content of the manifest files, if the archive had any
     * @param sourceBytes the archive's real size, counted rather than declared
     * @param sha256      the identity of those bytes
     */
    public record Unpacked(Set<String> paths, Map<String, byte[]> wanted, int fileCount,
                           long unpackedBytes, long sourceBytes, String sha256) {}

    /**
     * Streams the archive, checking as it goes, and writes what survives.
     *
     * @throws PackageRejected when a check refuses the archive. Every other failure is ours
     */
    public Unpacked unpack(InputStream source, Sink sink) {
        MessageDigest digest = sha256();
        Counting counted = new Counting(new DigestInputStream(source, digest));

        Set<String> paths = new LinkedHashSet<>();
        Map<String, byte[]> wanted = new LinkedHashMap<>();
        int files = 0;
        long unpacked = 0;

        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(counted))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (counted.count > limits.maxSourceBytes()) {
                    // The presigned PUT already refused a body larger than this, so reaching here
                    // means storage let something through -- or the archive is being ingested
                    // from somewhere that was not signed. Either way it stops now.
                    throw new PackageRejected("The archive is larger than the "
                        + limits.maxSourceBytes() + " byte ceiling.");
                }
                String path = EntryPath.normalise(entry.getName(), limits.maxPathLength());
                if (path == null || entry.isDirectory()) {
                    continue;
                }
                if (++files > limits.maxEntries()) {
                    throw new PackageRejected("The archive contains more than "
                        + limits.maxEntries() + " files. A course this large is almost always an "
                        + "export that included a working directory by mistake.");
                }

                String contentType = MediaTypes.serveAs(path).orElse(null);
                boolean isWanted = WANTED.contains(lastSegment(path).toLowerCase(java.util.Locale.ROOT));
                if (contentType == null && !isWanted) {
                    // Not served and not needed. Drained rather than skipped: ZipInputStream must
                    // reach the end of an entry before the next one is readable, and the drain is
                    // itself bounded, because a bomb hidden in a .psd is still a bomb.
                    unpacked += drain(zip, entry, path, unpacked);
                    continue;
                }

                Spooled spooled = spool(zip, entry, path, unpacked);
                unpacked += spooled.length;
                try {
                    if (isWanted && spooled.length <= MAX_WANTED_BYTES) {
                        wanted.put(path.toLowerCase(java.util.Locale.ROOT), spooled.readAll());
                    }
                    if (contentType != null) {
                        try (InputStream body = spooled.open()) {
                            sink.write(path, body, spooled.length, contentType);
                        }
                        paths.add(path);
                    }
                } finally {
                    spooled.discard();
                }
            }
        } catch (IOException broken) {
            // A truncated or corrupt archive reads as an IOException from the inflater. That is
            // the AUTHOR's problem and not ours, so it is a rejection with a sentence they can
            // act on rather than a 500 that tells them to contact support.
            throw new PackageRejected(
                "The archive could not be read as a ZIP file. It may have been truncated during "
                + "upload, or it may not be a ZIP at all. (" + broken.getMessage() + ")");
        }

        if (files == 0) {
            throw new PackageRejected("The archive is empty.");
        }
        return new Unpacked(paths, wanted, files, unpacked, counted.count,
            HexFormat.of().formatHex(digest.digest()));
    }

    /** Reads one entry to a temp file, enforcing the per-entry, total and ratio limits as it goes. */
    private Spooled spool(ZipInputStream zip, ZipEntry entry, String path, long unpackedSoFar) {
        Path file;
        try {
            file = Files.createTempFile("xenopslearn-entry-", ".part");
        } catch (IOException cannotSpool) {
            // Ours, not the author's: no temp space. Let it out as an IO failure so the ingest
            // records FAILED rather than telling somebody to fix their course.
            throw new UncheckedIOException(cannotSpool);
        }
        long length = 0;
        try (OutputStream out = Files.newOutputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = zip.read(buffer)) > 0) {
                length += read;
                check(entry, path, length, unpackedSoFar + length);
                out.write(buffer, 0, read);
            }
        } catch (IOException broken) {
            discard(file);
            throw new PackageRejected("The archive could not be read at \"" + path + "\": "
                + broken.getMessage());
        } catch (RuntimeException refused) {
            discard(file);
            throw refused;
        }
        try {
            // The entry is finished, so a streamed archive's data descriptor has now been read
            // and the compressed size is finally knowable. See checkRatio.
            checkRatio(entry, path, length);
        } catch (PackageRejected refused) {
            discard(file);
            throw refused;
        }
        return new Spooled(file, length);
    }

    /** Reads an entry we will not keep, still bounded. */
    private long drain(ZipInputStream zip, ZipEntry entry, String path, long unpackedSoFar)
            throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long length = 0;
        int read;
        while ((read = zip.read(buffer)) > 0) {
            length += read;
            check(entry, path, length, unpackedSoFar + length);
        }
        checkRatio(entry, path, length);
        return length;
    }

    /**
     * The three limits that stop a bomb, applied per chunk read.
     *
     * <p>Per chunk rather than per entry: an entry that expands to four petabytes is stopped
     * sixty-four kilobytes into it, which is the difference between a refusal and an outage.
     */
    private void check(ZipEntry entry, String path, long entryBytes, long totalBytes) {
        if (entryBytes > limits.maxEntryBytes()) {
            throw new PackageRejected("\"" + path + "\" expands to more than "
                + limits.maxEntryBytes() + " bytes. A single file that large does not belong in a "
                + "package; video is uploaded separately (ADR-0101).");
        }
        if (totalBytes > limits.maxUnpackedBytes()) {
            throw new PackageRejected("The archive expands to more than "
                + limits.maxUnpackedBytes() + " bytes in total.");
        }
        checkRatio(entry, path, entryBytes);
    }

    /**
     * The ratio test, run twice for one reason worth knowing about.
     *
     * <p>The compressed size IS trustworthy in a way the uncompressed one is not: it is how many
     * bytes the archive actually spends, and an attacker cannot make it smaller than the data they
     * had to include. But {@link ZipInputStream} can only report it when the LOCAL header carried
     * it — an archive written as a stream puts the sizes in a data descriptor AFTER the entry, and
     * until that is read {@code getCompressedSize()} answers -1.
     *
     * <p>So this is called per chunk, where it fires early on the ordinary archives that do carry
     * the header, and again once the entry is finished, where the descriptor has been read and the
     * number is finally there. The early call is an optimisation; the late one is the check. The
     * absolute limits above are what bound a streamed archive in the meantime, and they are why -1
     * here is safe rather than a hole.
     */
    private void checkRatio(ZipEntry entry, String path, long entryBytes) {
        long compressed = entry.getCompressedSize();
        // -1 is "not known yet", and a tiny entry says nothing about compression: a 12-byte file
        // shrinking to 2 is a 6:1 ratio and is a header.
        if (compressed <= 4096) {
            return;
        }
        long ratio = entryBytes / compressed;
        if (ratio > limits.maxRatio()) {
            throw new PackageRejected("\"" + path + "\" expands " + ratio
                + " times over, past the " + limits.maxRatio() + ":1 ceiling. Ordinary course "
                + "files do not compress like that; data engineered to compress does.");
        }
    }

    private static String lastSegment(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required of every JVM", impossible);
        }
    }

    private static void discard(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException leftBehind) {
            LOG.warn("Could not remove spooled entry {}", file, leftBehind);
        }
    }

    /** One entry, on disk, with its real length. */
    private record Spooled(Path file, long length) {

        InputStream open() throws IOException {
            return Files.newInputStream(file);
        }

        byte[] readAll() throws IOException {
            try (InputStream in = open()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream((int) length);
                in.transferTo(out);
                return out.toByteArray();
            }
        }

        void discard() {
            SafeUnpacker.discard(file);
        }
    }

    /**
     * Counts what was actually read from the source.
     *
     * <p>The archive's own size is not taken from the {@code Content-Length} storage reported,
     * for the same reason the entry sizes are not taken from the central directory: this is the
     * number the limits are checked against, so it has to be the one nobody else supplied.
     */
    private static final class Counting extends java.io.FilterInputStream {

        private long count;

        private Counting(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int one = super.read();
            if (one >= 0) {
                count++;
            }
            return one;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) {
                count += read;
            }
            return read;
        }
    }
}
