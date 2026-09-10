package com.xenopsoftware.learn.packaging.web.rest;

import com.xenopsoftware.learn.packaging.launch.ContentOriginProperties;
import com.xenopsoftware.learn.packaging.launch.LaunchUrls;
import com.xenopsoftware.learn.packaging.launch.PackageLookup;
import com.xenopsoftware.learn.packaging.storage.ObjectStore;
import com.xenopsoftware.learn.packaging.storage.StorageProperties;
import com.xenopsoftware.learn.packaging.unpack.EntryPath;
import com.xenopsoftware.learn.packaging.unpack.MediaTypes;
import com.xenopsoftware.learn.packaging.unpack.PackageRejected;
import com.xenopsoftware.learn.packaging.wrapper.Wrapper;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerMapping;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;

/**
 * What the content origin serves (ADR-0105, T-4.3).
 *
 * <h2>This is not on the application origin, and the path is how that is kept true</h2>
 *
 * <p>These two routes are reached only through the tenant's content origin —
 * {@code https://<tenant>.<content-domain>/packages/…}, or {@code http://<tenant>.localhost:8090}
 * in the local stack — which proxies them here after stripping its own prefix. The application's
 * gateway has no route to {@code /served}: its table is a closed list under {@code /api} and an
 * unmatched path is a 404 rather than a default (see {@code Upstreams}), so nothing under here is
 * reachable from the origin the application's session lives on.
 *
 * <p><b>The path deliberately does not contain the word "packages".</b> An ArchUnit rule fails the
 * build on any mapping that does, in this module and in every other one, because the mistake
 * ADR-0105 is guarding against is a convenience route added on a Friday — and a rule that can be
 * satisfied by reading a comment is not a rule.
 *
 * <h2>Anonymous, on purpose, and that is the security property rather than a hole in it</h2>
 *
 * <p>Nothing here authenticates anybody. Presenting a credential to this origin is exactly what
 * the ADR forbids: an uploaded SCORM package is third-party JavaScript running in this document,
 * and anything the origin holds — a cookie, a token, a session — is something that code can reach.
 * So it holds nothing, and a launch URL is a capability (see {@link LaunchUrls} for what that
 * trades away and why).
 *
 * <h2>Every response says what it is, and forbids the browser from disagreeing</h2>
 *
 * <p>The content type comes from {@link MediaTypes} — set by us, from the extension, never sniffed
 * and never taken from the archive — and {@code X-Content-Type-Options: nosniff} is on every
 * response. Either alone is insufficient: without the allowlist a package chooses its own type,
 * and without {@code nosniff} the browser can decide a {@code .txt} was HTML after all.
 */
@RestController
@RequestMapping("/served")
public class ContentOriginResource {

    private final PackageLookup lookup;
    private final ObjectStore store;
    private final StorageProperties storage;
    private final LaunchUrls launchUrls;
    private final ContentOriginProperties origins;
    private final Wrapper wrapper;

    public ContentOriginResource(PackageLookup lookup, ObjectStore store,
            StorageProperties storage, LaunchUrls launchUrls, ContentOriginProperties origins,
            Wrapper wrapper) {
        this.lookup = lookup;
        this.store = store;
        this.storage = storage;
        this.launchUrls = launchUrls;
        this.origins = origins;
        this.wrapper = wrapper;
    }

    /**
     * The wrapper: the document the application puts in an iframe.
     *
     * <p>It implements the API a SCORM package's discovery walk looks for on {@code window.parent}
     * — same origin as the package, exactly as the standard expects — and forwards every call to
     * the application over {@code postMessage}. The package never learns anything unusual is
     * happening, and never touches a window that holds a session.
     *
     * <p><b>Never cached.</b> It carries the application's origin and the entry URL, which are
     * configuration; a wrapper cached from a previous deployment would be posting to an origin
     * that has moved.
     */
    @GetMapping(value = "/{tenantId}/{packageId}/launch", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> launch(@PathVariable String tenantId,
            @PathVariable UUID packageId) {
        PackageLookup.Launchable stored = lookup.launchable(tenantId, packageId)
            // 404 and not 403, for a package that is not READY as much as for one that does not
            // exist. Nothing on this origin is in a position to tell a caller which.
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        String html = wrapper.html(
            launchUrls.filesBase(tenantId, packageId) + stored.entryPath(),
            stored.profile(),
            packageId,
            origins.appOrigin(),
            launchUrls.origin(tenantId));

        return ResponseEntity.ok()
            .contentType(MediaType.valueOf("text/html; charset=utf-8"))
            .cacheControl(CacheControl.noStore())
            .header("X-Content-Type-Options", "nosniff")
            .body(html);
    }

    /**
     * One file out of the package.
     *
     * <p>The path is re-normalised here even though it was normalised at ingest, and that is not
     * belt and braces — it is the actual check. What was normalised at ingest was an entry name
     * from an archive; what arrives here is a path segment from a URL a browser built, which may
     * be anything at all. A request for {@code ../../other-tenant/x} must not become an object
     * key, and this is the only place standing between the two.
     */
    @GetMapping("/{tenantId}/{packageId}/files/**")
    public ResponseEntity<InputStreamResource> file(@PathVariable String tenantId,
            @PathVariable UUID packageId, HttpServletRequest request) {
        if (lookup.launchable(tenantId, packageId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        String requested = withinPackage(request, tenantId, packageId);
        String path;
        try {
            path = EntryPath.normalise(requested, 512);
        } catch (PackageRejected escaped) {
            // A traversal attempt. 404 rather than 400: this origin explains nothing to anybody.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (path == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        String contentType = MediaTypes.serveAs(path)
            // Unreachable for anything that was extracted, since the same allowlist decided what
            // to store. Kept because "unreachable" is a claim about today's ingest, and this
            // route outlives it.
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        ObjectStore.Retrieved object = store.get(storage.packagesBucket(),
                com.xenopsoftware.learn.packaging.bundle.PackageStorageKeys
                    .extracted(tenantId, packageId, path))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
            .contentType(MediaType.valueOf(contentType))
            .header("X-Content-Type-Options", "nosniff")
            /*
             * A YEAR, IMMUTABLE, AND THAT IS SAFE HERE FOR A REASON WORTH STATING.
             *
             * A package's files never change: an author who fixes a typo re-uploads, which is a
             * new package with a new id and therefore a new URL. Nothing at this URL can ever be
             * different, so there is no staleness to worry about — and the alternative, revalidating
             * every one of a course's four hundred assets on every launch, is what makes a SCORM
             * course feel slow on a phone.
             *
             * THE ONE THING THAT DOES GO STALE IS THE HEADERS, and it is worth knowing before it
             * costs somebody an hour. A cached response carries the Content-Security-Policy it was
             * fetched WITH, so changing the CSP on the content origin does not reach a browser that
             * already holds the file. Measured on exactly that change: the corrected header was on
             * the wire, and the browser kept enforcing the old one until the package was re-ingested
             * under a new id. In production a CSP change wants a purge at the edge; locally, a fresh
             * upload is a fresh id and therefore a fresh URL.
             */
            .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
        if ("application/pdf".equals(contentType)) {
            // Downloaded rather than rendered. A PDF viewer is a script engine, and one running
            // in this document would be running inside the package's own origin -- which is
            // survivable, and is still not something to hand a package for free.
            response.header(HttpHeaders.CONTENT_DISPOSITION, "attachment");
        }
        if (object.sizeBytes() >= 0) {
            response.contentLength(object.sizeBytes());
        }
        return response.body(new InputStreamResource(object.body()));
    }

    /** The part of the URL after {@code /files/}, undecoded by the framework's path variables. */
    private String withinPackage(HttpServletRequest request, String tenantId, UUID packageId) {
        String full = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        String prefix = "/" + tenantId + "/" + packageId + "/files/";
        int at = full == null ? -1 : full.indexOf(prefix);
        if (at < 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        String tail = full.substring(at + prefix.length());
        // Decoded here rather than trusted from the container: a %2e%2e%2f that the container left
        // encoded is a traversal that EntryPath would otherwise never see as one.
        return java.net.URLDecoder.decode(tail, java.nio.charset.StandardCharsets.UTF_8);
    }
}
