package com.xenopsoftware.learn.packaging.unpack;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The bounds an uploaded archive is unpacked within (ADR-0105, T-4.1).
 *
 * <p><b>Every one of these is enforced WHILE STREAMING, against bytes actually read.</b> A ZIP's
 * central directory declares an uncompressed size for each entry, and that number is written by
 * whoever made the archive — checking it is asking the attacker how big their attack is. So the
 * numbers here are compared against a running count from the decompressing stream, and the read
 * stops the moment one is passed rather than after the disk is full.
 *
 * @param maxSourceBytes    the archive itself. Also signed into the upload target, so storage
 *                          refuses a larger body before this service ever sees it
 * @param maxUnpackedBytes  everything the archive expands to, added up. The number that actually
 *                          stops a zip bomb: a 42KB archive expanding to 4.5PB fails here, at
 *                          whatever byte crosses the line
 * @param maxEntryBytes     any single file. A course with one 3GB video inside it is not a bomb
 *                          and is still not something to serve from a package (ADR-0101)
 * @param maxEntries        how many files. Ten thousand small files is as effective a denial of
 *                          service as one huge one, and costs the attacker less
 * @param maxRatio          uncompressed ÷ compressed, per entry. Ordinary HTML and JavaScript
 *                          compress somewhere under 10:1; text engineered to compress does far
 *                          better, which is the whole trick. 200 leaves an order of magnitude
 *                          over anything an honest exporter produces
 * @param maxPathLength     a path inside the archive. Long paths are how a normalisation bug is
 *                          reached, and no real course has one
 */
@ConfigurationProperties(prefix = "packaging.limits")
public record UnpackLimits(
        long maxSourceBytes,
        long maxUnpackedBytes,
        long maxEntryBytes,
        int maxEntries,
        int maxRatio,
        int maxPathLength) {

    public UnpackLimits {
        maxSourceBytes = orDefault(maxSourceBytes, 1_073_741_824L);      // 1 GiB
        maxUnpackedBytes = orDefault(maxUnpackedBytes, 4_294_967_296L);  // 4 GiB
        maxEntryBytes = orDefault(maxEntryBytes, 536_870_912L);          // 512 MiB
        maxEntries = (int) orDefault(maxEntries, 20_000);
        maxRatio = (int) orDefault(maxRatio, 200);
        maxPathLength = (int) orDefault(maxPathLength, 512);
    }

    private static long orDefault(long configured, long fallback) {
        // Zero means "not configured" rather than "no limit". A limit of zero would refuse every
        // archive, and a limit of infinity is not a thing this file is allowed to express.
        return configured > 0 ? configured : fallback;
    }
}
