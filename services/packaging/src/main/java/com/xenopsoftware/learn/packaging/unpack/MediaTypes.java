package com.xenopsoftware.learn.packaging.unpack;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The extension allowlist, and the content type WE give each file (ADR-0105).
 *
 * <p><b>Allowlist, not blocklist, and that is the whole design.</b> A blocklist is a list of the
 * dangerous things somebody has thought of so far; the next authoring tool emits a format nobody
 * on this side has heard of, and it is served. This list is what real SCORM and cmi5 exports
 * actually contain, and anything outside it is not served at all — not refused at request time,
 * not stored with a warning: never written to the packages bucket in the first place, so there is
 * nothing to serve even if a route were added later.
 *
 * <p><b>The type is set from the extension, never sniffed and never taken from the archive.</b>
 * A ZIP entry carries no content type, and guessing one from the bytes is how a {@code .txt} full
 * of markup becomes a document with script in it. Every response also carries
 * {@code X-Content-Type-Options: nosniff} — the two together are what stop the browser deciding
 * for itself.
 *
 * <p><b>HTML and JavaScript are on the list, obviously.</b> A SCORM package IS HTML and
 * JavaScript; refusing them would refuse SCORM. What makes that safe is not this file — it is the
 * origin they are served from, which holds no cookie, no token and nothing worth taking
 * (ADR-0105). This list exists to stop the categories that are dangerous <em>regardless</em> of
 * origin: an uploaded {@code .exe} a learner is invited to download, a {@code .svg} served as
 * something else, an {@code .htaccess} or a {@code .php} that a future origin might execute.
 */
public final class MediaTypes {

    /**
     * Extension → the type we serve it as.
     *
     * <p>Every text type is explicitly {@code charset=utf-8}. A SCORM package written in Turkish
     * and served without a charset is decoded by the browser's guess, which turns a course into
     * mojibake for exactly the customers this product ships a Turkish translation for.
     */
    private static final Map<String, String> ALLOWED = Map.ofEntries(
        // The package itself.
        Map.entry("html", "text/html; charset=utf-8"),
        Map.entry("htm", "text/html; charset=utf-8"),
        Map.entry("js", "text/javascript; charset=utf-8"),
        Map.entry("mjs", "text/javascript; charset=utf-8"),
        Map.entry("css", "text/css; charset=utf-8"),
        Map.entry("json", "application/json; charset=utf-8"),
        Map.entry("xml", "application/xml; charset=utf-8"),
        Map.entry("txt", "text/plain; charset=utf-8"),
        Map.entry("csv", "text/csv; charset=utf-8"),
        Map.entry("vtt", "text/vtt; charset=utf-8"),
        Map.entry("map", "application/json; charset=utf-8"),

        // Images. SVG is deliberately absent -- it is a document that can carry script, and an
        // authoring tool that needs one can rasterise it.
        Map.entry("png", "image/png"),
        Map.entry("jpg", "image/jpeg"),
        Map.entry("jpeg", "image/jpeg"),
        Map.entry("gif", "image/gif"),
        Map.entry("webp", "image/webp"),
        Map.entry("avif", "image/avif"),
        Map.entry("ico", "image/x-icon"),
        Map.entry("bmp", "image/bmp"),

        // Fonts.
        Map.entry("woff", "font/woff"),
        Map.entry("woff2", "font/woff2"),
        Map.entry("ttf", "font/ttf"),
        Map.entry("otf", "font/otf"),
        Map.entry("eot", "application/vnd.ms-fontobject"),

        // Media a package embeds directly. Course VIDEO does not arrive this way -- it goes to
        // the delivery provider (ADR-0101) -- but a five-second interaction sound does.
        Map.entry("mp3", "audio/mpeg"),
        Map.entry("m4a", "audio/mp4"),
        Map.entry("ogg", "audio/ogg"),
        Map.entry("wav", "audio/wav"),
        Map.entry("mp4", "video/mp4"),
        Map.entry("webm", "video/webm"),

        // Handouts a course links to. Served as attachments by the serving layer rather than
        // rendered, because a PDF viewer is a script engine.
        // Slides arrive already rasterised (see PackageKind.SLIDES), so their pages are images
        // covered above and a handout is the only thing this line is for.
        Map.entry("pdf", "application/pdf"));

    private MediaTypes() {}

    /**
     * The type to serve this path as, or empty when it is not on the list.
     *
     * <p>An empty answer means the file is dropped during extraction and never reaches the
     * packages bucket. It is not an error: real exports contain source maps, {@code .DS_Store},
     * Thumbs.db, a designer's {@code .psd}. Refusing the whole course because a Mac wrote a
     * metadata file into the ZIP would be refusing every course exported on a Mac.
     */
    public static Optional<String> serveAs(String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1) {
            return Optional.empty();
        }
        String extension = path.substring(dot + 1).toLowerCase(Locale.ROOT);
        String type = ALLOWED.get(extension);
        return Optional.ofNullable(type);
    }
}
