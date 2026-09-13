package com.xenopsoftware.learn.packaging.unpack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the unpacker lets through, and what it stops (ADR-0105's ingest list, T-4.1).
 *
 * <p>The archives are built here rather than checked in as fixtures. A zip bomb committed to a
 * repository is a file that gets scanned, quarantined and eventually deleted by somebody's
 * endpoint protection, and the test then fails for a reason nobody can reproduce — so the bomb is
 * assembled at run time from a loop, where it is obviously a bomb and obviously harmless on disk.
 */
class SafeUnpackerTest {

    /** Collects what the unpacker decided to keep, so a test can assert on the result. */
    private static final class Collecting implements SafeUnpacker.Sink {

        private final Map<String, String> written = new LinkedHashMap<>();
        private final Map<String, String> types = new LinkedHashMap<>();

        @Override
        public void write(String path, InputStream body, long length, String contentType) {
            try {
                written.put(path, new String(body.readAllBytes(), StandardCharsets.UTF_8));
                types.put(path, contentType);
            } catch (IOException unreadable) {
                throw new AssertionError(unreadable);
            }
        }
    }

    private SafeUnpacker unpacker(UnpackLimits limits) {
        return new SafeUnpacker(limits);
    }

    private static UnpackLimits defaults() {
        // Zeroes take the record's own defaults, so this test exercises the numbers the service
        // actually ships with rather than a set invented here.
        return new UnpackLimits(0, 0, 0, 0, 0, 0);
    }

    private static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("an ordinary course is extracted, and each file gets the type WE chose")
    void extractsAnOrdinaryCourse() throws Exception {
        byte[] archive = zip(new LinkedHashMap<>(Map.of(
            "index.html", bytes("<html><body>hello</body></html>"),
            "scripts/app.js", bytes("console.log(1)"),
            "styles/app.css", bytes("body{}"))));

        Collecting sink = new Collecting();
        SafeUnpacker.Unpacked unpacked = unpacker(defaults())
            .unpack(new ByteArrayInputStream(archive), sink);

        assertThat(sink.written).containsOnlyKeys("index.html", "scripts/app.js", "styles/app.css");
        // The charset is not decoration: a Turkish course served without one is decoded by the
        // browser's guess, which is mojibake for exactly the customers this product translates for.
        assertThat(sink.types).containsEntry("index.html", "text/html; charset=utf-8");
        assertThat(sink.types).containsEntry("scripts/app.js", "text/javascript; charset=utf-8");
        assertThat(unpacked.fileCount()).isEqualTo(3);
        assertThat(unpacked.sha256()).hasSize(64);
        assertThat(unpacked.sourceBytes()).isEqualTo(archive.length);
    }

    @Test
    @DisplayName("files that are not on the allowlist are dropped, and the course still loads")
    void dropsRatherThanRefuses() throws Exception {
        byte[] archive = zip(new LinkedHashMap<>(Map.of(
            "index.html", bytes("<html></html>"),
            // Everything a real export sweeps up. Refusing the course over any of these would
            // refuse every course exported on a Mac.
            ".DS_Store", bytes("junk"),
            "Thumbs.db", bytes("junk"),
            "design/source.psd", bytes("junk"),
            "evil.svg", bytes("<svg onload=\"alert(1)\"/>"))));

        Collecting sink = new Collecting();
        unpacker(defaults()).unpack(new ByteArrayInputStream(archive), sink);

        assertThat(sink.written).containsOnlyKeys("index.html");
        // SVG in particular: a document that can carry script, deliberately absent from the list.
        assertThat(sink.written).doesNotContainKey("evil.svg");
    }

    @Test
    @DisplayName("an entry that escapes the package root refuses the whole archive")
    void refusesTraversal() throws Exception {
        byte[] archive = zip(new LinkedHashMap<>(Map.of(
            "index.html", bytes("<html></html>"),
            "a/../../escaped.html", bytes("<html></html>"))));

        assertThatThrownBy(() ->
            unpacker(defaults()).unpack(new ByteArrayInputStream(archive), new Collecting()))
            .isInstanceOf(PackageRejected.class)
            .hasMessageContaining("escapes the package root");
    }

    @Test
    @DisplayName("a zip bomb is stopped by the total, not believed when it declares a size")
    void refusesTheBomb() throws Exception {
        // 32MB of zeroes compresses to a few kilobytes; the limit below is 1MB unpacked. The
        // archive on disk is tiny, which is the entire point of the attack.
        byte[] zeroes = new byte[32 * 1024 * 1024];
        byte[] archive = zip(new LinkedHashMap<>(Map.of("payload.txt", zeroes)));
        assertThat(archive.length).isLessThan(200 * 1024);

        UnpackLimits tight = new UnpackLimits(0, 1_048_576L, 0, 0, 0, 0);
        assertThatThrownBy(() ->
            unpacker(tight).unpack(new ByteArrayInputStream(archive), new Collecting()))
            .isInstanceOf(PackageRejected.class)
            .hasMessageContaining("expands to more than");
    }

    @Test
    @DisplayName("a single file past the per-entry ceiling is refused before it finishes")
    void refusesAnOversizedEntry() throws Exception {
        byte[] archive = zip(new LinkedHashMap<>(Map.of(
            "video.mp4", new byte[8 * 1024 * 1024])));

        UnpackLimits tight = new UnpackLimits(0, 0, 1_048_576L, 0, 0, 0);
        assertThatThrownBy(() ->
            unpacker(tight).unpack(new ByteArrayInputStream(archive), new Collecting()))
            .isInstanceOf(PackageRejected.class)
            .hasMessageContaining("video.mp4");
    }

    @Test
    @DisplayName("too many files is as effective a denial of service as one huge one")
    void refusesTooManyEntries() throws Exception {
        Map<String, byte[]> many = new LinkedHashMap<>();
        for (int index = 0; index < 50; index++) {
            many.put("page" + index + ".html", bytes("<html></html>"));
        }
        UnpackLimits tight = new UnpackLimits(0, 0, 0, 10, 0, 0);
        assertThatThrownBy(() ->
            unpacker(tight).unpack(new ByteArrayInputStream(zip(many)), new Collecting()))
            .isInstanceOf(PackageRejected.class)
            .hasMessageContaining("more than 10 files");
    }

    @Test
    @DisplayName("a manifest is kept in memory on the way past, so it is not read back out of storage")
    void keepsTheManifest() throws Exception {
        byte[] archive = zip(new LinkedHashMap<>(Map.of(
            "imsmanifest.xml", bytes("<manifest/>"),
            "index.html", bytes("<html></html>"))));

        SafeUnpacker.Unpacked unpacked = unpacker(defaults())
            .unpack(new ByteArrayInputStream(archive), new Collecting());

        assertThat(unpacked.wanted()).containsKey("imsmanifest.xml");
        assertThat(new String(unpacked.wanted().get("imsmanifest.xml"), StandardCharsets.UTF_8))
            .isEqualTo("<manifest/>");
    }

    @Test
    @DisplayName("something that is not a ZIP is the author's problem, not a 500")
    void refusesNonArchives() {
        assertThatThrownBy(() -> unpacker(defaults())
            .unpack(new ByteArrayInputStream(bytes("this is a PDF, not a course")), new Collecting()))
            .isInstanceOf(PackageRejected.class);
    }

    @Test
    @DisplayName("an empty archive is refused rather than becoming a package with no files")
    void refusesEmptyArchives() throws Exception {
        byte[] archive = zip(new LinkedHashMap<>());
        assertThatThrownBy(() ->
            unpacker(defaults()).unpack(new ByteArrayInputStream(archive), new Collecting()))
            .isInstanceOf(PackageRejected.class)
            .hasMessageContaining("empty");
    }
}
