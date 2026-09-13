package com.xenopsoftware.learn.packaging.web.rest;

import com.xenopsoftware.learn.common.tenancy.TenantContext;
import com.xenopsoftware.learn.common.web.ProblemDocumentation;
import com.xenopsoftware.learn.packaging.bundle.ContentPackage;
import com.xenopsoftware.learn.packaging.bundle.PackageService;
import com.xenopsoftware.learn.packaging.launch.LaunchUrls;
import com.xenopsoftware.learn.packaging.runtime.LearnerIdentity;
import com.xenopsoftware.learn.packaging.runtime.PackageRuntime;
import com.xenopsoftware.learn.packaging.runtime.RuntimeService;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Where the CALLER got to inside a package (T-4.4).
 *
 * <h2>Under {@code /me/}, which is the whole authorization story</h2>
 *
 * <p>It takes no learner id and there is deliberately no version of it that answers about somebody
 * else. The person is resolved from the verified token through identity, so the only runtime a
 * caller can read or write is their own — which is what makes it safe for this to carry no
 * permission check at all. There is nothing here to be permitted to do.
 *
 * <h2>Why the application calls this and the wrapper does not</h2>
 *
 * <p>The wrapper is on the tenant's content origin and holds no credential — ADR-0105's decision,
 * and the reason an uploaded package cannot reach anything. So it posts the data model to the
 * application over {@code postMessage} and the application, which has the session, calls this.
 * A route that let the content origin save directly would be the credential on that origin the
 * whole decision exists to prevent.
 *
 * <h2>The client posts data, never a verdict</h2>
 *
 * <p>There is no {@code completed} field on the way in. "Completion is derived by the server" is
 * ADR-0107's title; the body carries the package's data model in the standard's own vocabulary and
 * this platform reads it. What comes BACK says whether the package is complete, because a screen
 * has to render it.
 */
@RestController
@RequestMapping("/api/v1/me/runtime")
public class RuntimeResource {

    private final RuntimeService runtimes;
    private final PackageService packages;
    private final LaunchUrls launchUrls;
    private final LearnerIdentity identities;

    public RuntimeResource(RuntimeService runtimes, PackageService packages, LaunchUrls launchUrls,
            LearnerIdentity identities) {
        this.runtimes = runtimes;
        this.packages = packages;
        this.launchUrls = launchUrls;
        this.identities = identities;
    }

    /**
     * What the wrapper is seeded with, and what a screen renders.
     *
     * @param data       the CMI data model as the package left it, given back verbatim. The
     *                   wrapper's {@code seed} puts it straight into its map
     * @param entry      {@code ab-initio} on a first launch and {@code resume} afterwards — read
     *                   from {@code launches} rather than from the map, because it is a fact about
     *                   this launch rather than something the package stored
     * @param launchUrl  where to point the iframe. Present only for a package that can be opened
     * @param contentOrigin the origin that URL is on, which the application needs as the exact
     *                   {@code targetOrigin} for every message it posts (ADR-0105)
     * @param sessionSeconds what the package says the current launch has lasted, and
     * @param totalSeconds every launch added together — two different numbers, which is the
     *                   distinction SCORM draws between {@code session_time} and {@code total_time}
     *                   and the one a report has to keep. Both are the PACKAGE's account of
     *                   itself; {@code secondsSpent} beside them is the browser's, kept as
     *                   corroboration and never as the decision (ADR-0107)
     * @param session    the launch this view belongs to. Quoted back on every save, because the
     *                   most recent launch owns the registration and the others are refused
     */
    public record RuntimeView(UUID packageId, UUID nodeId, String profile, Map<String, String> data,
                              String entry, boolean completed, Boolean passed, BigDecimal scoreRaw,
                              int launches, int secondsSpent, int sessionSeconds, int totalSeconds,
                              UUID session, Instant completedAt,
                              String launchUrl, String contentOrigin) {}

    /**
     * @param data         every element the package has written, not a delta. A package's
     *                     {@code Commit} means "this is the state", and a delta protocol would put
     *                     the burden of merging on whichever end had the weaker guarantee
     * @param addedSeconds how long they have been in the package since the last save. Corroboration
     *                     only — it never overrides what the package said (ADR-0107)
     * @param session      the launch this save belongs to, from the open that started it. A save
     *                     from a launch that has been superseded is refused rather than merged:
     *                     two tabs hold two whole data models, and there is no merge of them that
     *                     means anything to the package that wrote them
     */
    public record SaveRequest(Map<String, String> data, int addedSeconds, UUID session) {}

    /**
     * Opens the package for the caller, creating their runtime on a first launch.
     *
     * @param nodeId the place in a course this launch belongs to, or absent for a preview. Part of
     *               the key: the same package in two courses is two runtimes, because finishing it
     *               in the onboarding course is not finishing it in the annual refresher
     */
    @GetMapping("/{packageId}")
    @ApiResponse(responseCode = "200", description = "Their place in this package")
    @ApiResponse(responseCode = "409", description = "The package is not ready to be opened",
        content = @Content(mediaType = ProblemDocumentation.PROBLEM_JSON,
            schema = @Schema(ref = ProblemDocumentation.REF)))
    public RuntimeView open(@PathVariable UUID packageId,
            @RequestParam(required = false) UUID nodeId) {
        UUID learnerId = caller();
        return view(runtimes.open(packageId, nodeId, learnerId), packages.require(packageId));
    }

    /**
     * Stores what the package left.
     *
     * <p>{@code PUT} because the body is the whole state and sending it twice is sending it once —
     * which matters here more than usual: a browser closing mid-commit will retry, and a SCORM
     * package's own {@code Commit} is defined as idempotent.
     */
    @PutMapping("/{packageId}")
    @ApiResponse(responseCode = "200", description = "Saved, with what this platform derived from it")
    @ApiResponse(responseCode = "409", description = "Another launch has taken this registration, "
        + "so this one has stopped saving",
        content = @Content(mediaType = ProblemDocumentation.PROBLEM_JSON,
            schema = @Schema(ref = ProblemDocumentation.REF)))
    @ApiResponse(responseCode = "413", description = "More data than one runtime may hold",
        content = @Content(mediaType = ProblemDocumentation.PROBLEM_JSON,
            schema = @Schema(ref = ProblemDocumentation.REF)))
    @ApiResponse(responseCode = "429", description = "This registration is over its write budget. "
        + "Nothing is lost: the next save carries the whole data model again",
        content = @Content(mediaType = ProblemDocumentation.PROBLEM_JSON,
            schema = @Schema(ref = ProblemDocumentation.REF)))
    public RuntimeView save(@PathVariable UUID packageId,
            @RequestParam(required = false) UUID nodeId, @RequestBody SaveRequest request) {
        UUID learnerId = caller();
        PackageRuntime saved = runtimes.save(packageId, nodeId, learnerId, request.session(),
            request.data(), request.addedSeconds());
        return view(saved, packages.require(packageId));
    }

    private RuntimeView view(PackageRuntime runtime, ContentPackage stored) {
        String tenant = TenantContext.require();
        return new RuntimeView(
            runtime.getPackageId(),
            runtime.getNodeId(),
            stored.getProfile(),
            // What was stored PLUS what the platform owes the package -- `total_time` is the LMS's
            // to answer, and a package cannot know what happened in the sessions before this one.
            runtimes.seeded(runtime, stored.getProfile()),
            // Their FIRST launch is the one that just happened, so one launch means ab-initio.
            runtime.getLaunches() <= 1 ? "ab-initio" : "resume",
            runtime.isCompleted(),
            runtime.getPassed(),
            runtime.getScoreRaw(),
            runtime.getLaunches(),
            runtime.getSecondsSpent(),
            runtime.getSessionSeconds(),
            runtime.getTotalSeconds(),
            runtime.getActiveSession(),
            runtime.getCompletedAt(),
            stored.getState().isLaunchable() ? launchUrls.launchUrl(tenant, stored.getId()) : null,
            launchUrls.origin(tenant));
    }

    private UUID caller() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            // Everything under /api is authenticated by the security chain, so reaching here
            // without a JWT is a wiring mistake rather than a caller error.
            throw new IllegalStateException("This is only ever about a verified caller");
        }
        return identities.current(TenantContext.require(), token.getToken().getSubject())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                // Not a 500 and not a 404: the request is fine, one dependency is not, and the
                // client's correct response is to try again -- which for a learner mid-course
                // means their answers are not lost.
                "This cannot be answered right now."));
    }
}
