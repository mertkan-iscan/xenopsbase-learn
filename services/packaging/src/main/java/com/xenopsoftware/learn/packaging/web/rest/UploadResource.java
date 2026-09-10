package com.xenopsoftware.learn.packaging.web.rest;

import com.xenopsoftware.learn.common.tenancy.TenantContext;
import com.xenopsoftware.learn.common.web.ProblemDocumentation;
import com.xenopsoftware.learn.packaging.bundle.ContentPackage;
import com.xenopsoftware.learn.packaging.bundle.PackageKind;
import com.xenopsoftware.learn.packaging.bundle.PackageService;
import com.xenopsoftware.learn.packaging.launch.LaunchUrls;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Uploading a SCORM, cmi5 or slides package (T-4.1, T-4.2).
 *
 * <h2>Why this is {@code /api/v1/uploads} and not {@code /api/v1/packages}</h2>
 *
 * <p>Because an ArchUnit rule refuses the second, in this module and in every other one
 * ({@code TechnicalStructureTest}), and the rule is right. {@code /packages/…} is the path the
 * CONTENT ORIGIN serves — it is in the local stack's Caddyfile and it will be in a CDN rule —
 * and the failure ADR-0105 is guarding against is not somebody arguing for the same origin. It is
 * somebody adding a convenience route on the application origin, on a Friday, to make a demo work.
 * A blunt ban on the word means no route here can ever be mistaken for that one, and the cost is
 * this paragraph.
 *
 * <p>The resource is honestly named either way: what a caller creates here is an <em>upload</em>,
 * and the id they get back is the {@code packageId} catalog stores in a content item's payload.
 *
 * <h2>The order of the three calls</h2>
 *
 * <ol>
 *   <li>{@code POST /api/v1/uploads} — reserves the package, answers with a signed URL
 *   <li>the browser {@code PUT}s the archive to that URL, <b>directly to object storage</b>. No
 *       byte of it passes through this service, this gateway or this request thread
 *   <li>{@code POST /api/v1/uploads/{id}/ingest} — this service reads it, checks it against
 *       ADR-0105's list, extracts what survives and reads the manifest. Synchronous, so a refusal
 *       arrives as a sentence rather than as a state to poll
 * </ol>
 *
 * <h2>What is deliberately absent</h2>
 *
 * <p><b>No {@code @PreAuthorize}.</b> Not because uploading a package should be open — it very
 * much should not — but because this module joins catalog and assessment in the gap ADR-0109 and
 * T-9.11 name: authoring permissions are not enforced at the API yet, anywhere. Adding a check
 * here alone would secure one door in a building with none, while suggesting to a reader that the
 * others are locked. The console says so on screen ({@code NotEnforcedYet}).
 */
@RestController
@RequestMapping("/api/v1/uploads")
public class UploadResource {

    private final PackageService packages;
    private final LaunchUrls launchUrls;

    public UploadResource(PackageService packages, LaunchUrls launchUrls) {
        this.packages = packages;
        this.launchUrls = launchUrls;
    }

    /**
     * @param kind        {@code scorm}, {@code cmi5} or {@code slides}. Not {@code video}: video
     *                    goes straight to the delivery provider and never through here (ADR-0101)
     * @param filename    what the author called it, kept only to say back to them
     * @param sizeBytes   how large the archive is. <b>Signed into the upload target</b>, so it is
     *                    a ceiling storage enforces rather than a number to trust
     */
    public record CreateUploadRequest(String kind, String filename, long sizeBytes) {}

    /**
     * @param uploadUrl the URL to {@code PUT} the archive to. Sensitive in the ordinary way — it
     *                  is a write capability for one key, for a bounded time
     */
    public record IssuedUploadView(UUID id, String state, URI uploadUrl, Instant uploadExpiresAt) {}

    /**
     * A package as the console sees it.
     *
     * @param title      what the manifest called it, offered as a suggestion for the content
     *                   item's title and never imposed
     * @param profile    which runtime the wrapper presents: {@code scorm-1.2}, {@code scorm-2004},
     *                   {@code cmi5}, or null for slides
     * @param error      why a REJECTED package was refused, in a sentence the author can act on,
     *                   or why a FAILED one failed, which is ours
     * @param launchUrl  present only when the package is READY, because a launch URL for anything
     *                   else would be a link to a 404
     */
    public record PackageView(UUID id, String kind, String state, String sourceName, String title,
                              String entryPath, String profile, Integer fileCount,
                              Long unpackedBytes, Long sourceBytes, String sha256, String error,
                              String launchUrl, String contentOrigin, Instant createdAt) {}

    @PostMapping
    @ApiResponse(responseCode = "200", description = "The package is reserved and the target issued")
    @ApiResponse(responseCode = "413",
        description = "The declared size is past the per-package ceiling",
        content = @Content(mediaType = ProblemDocumentation.PROBLEM_JSON,
            schema = @Schema(ref = ProblemDocumentation.REF)))
    public IssuedUploadView create(@RequestBody CreateUploadRequest request) {
        PackageKind kind;
        try {
            kind = PackageKind.of(request.kind());
        } catch (IllegalArgumentException notAKind) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, notAKind.getMessage());
        }
        PackageService.IssuedUpload issued =
            packages.create(kind, request.filename(), request.sizeBytes());
        return new IssuedUploadView(issued.stored().getId(), issued.stored().getState().name(),
            issued.uploadUrl(), issued.expiresAt());
    }

    /**
     * Reads what was uploaded and turns it into a package.
     *
     * <p><b>200 with a state, not 4xx, when the archive is refused.</b> The call itself worked —
     * this service read the file and reached a verdict — and the verdict is the answer. A 400 here
     * would say the REQUEST was malformed, which it was not, and would push clients into reading
     * error bodies to tell "your course has a bad path" from "you sent me nonsense".
     */
    @PostMapping("/{id}/ingest")
    @ApiResponse(responseCode = "200",
        description = "The archive was processed. `state` is READY, REJECTED or FAILED; on the "
            + "last two, `error` says why")
    @ApiResponse(responseCode = "409", description = "Nothing has been uploaded for this package yet",
        content = @Content(mediaType = ProblemDocumentation.PROBLEM_JSON,
            schema = @Schema(ref = ProblemDocumentation.REF)))
    public PackageView ingest(@PathVariable UUID id) {
        return view(packages.ingest(id));
    }

    @GetMapping
    public List<PackageView> list() {
        return packages.list().stream().map(this::view).toList();
    }

    @GetMapping("/{id}")
    public PackageView one(@PathVariable UUID id) {
        return view(packages.require(id));
    }

    /**
     * 202, and it means what it says: the request is accepted and the package no longer launches.
     * The row claims DELETED only once storage has confirmed the objects are gone (T-3.8's rule).
     */
    @DeleteMapping("/{id}")
    @ApiResponse(responseCode = "202",
        description = "Accepted. `state` is DELETED once the objects are actually gone, and "
            + "DELETING while they are not")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.ACCEPTED)
    public PackageView delete(@PathVariable UUID id) {
        return view(packages.delete(id));
    }

    private PackageView view(ContentPackage stored) {
        String tenant = TenantContext.require();
        return new PackageView(
            stored.getId(),
            stored.getKind().code(),
            stored.getState().name(),
            stored.getSourceName(),
            stored.getTitle(),
            stored.getEntryPath(),
            stored.getProfile(),
            stored.getFileCount(),
            stored.getUnpackedBytes(),
            stored.getSourceBytes(),
            stored.getSourceSha256(),
            stored.getError(),
            // Only for a package that can actually be opened. Handing out a launch URL for a
            // REJECTED package would be handing out a link to a 404 and inviting somebody to
            // report the wrong bug.
            stored.getState().isLaunchable() ? launchUrls.launchUrl(tenant, stored.getId()) : null,
            launchUrls.origin(tenant),
            stored.getCreatedAt());
    }
}
