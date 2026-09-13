package com.xenopsoftware.learn.identity.web.rest;

import com.xenopsoftware.learn.common.tenancy.TenantContext;
import com.xenopsoftware.learn.common.web.ProblemDocumentation;
import com.xenopsoftware.learn.identity.impersonation.ImpersonationContext;
import com.xenopsoftware.learn.identity.user.AppUser;
import com.xenopsoftware.learn.identity.user.AppUserRepository;
import com.xenopsoftware.learn.identity.user.UserPreferencesService;
import com.xenopsoftware.learn.identity.user.UserProvisioningService;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The caller's own language and theme (T-10.9).
 *
 * <p><b>No {@code @PreAuthorize}, and it takes no id</b> — the same shape, and the same reason, as
 * {@code PUT /api/v1/users/me/timezone} next door in {@code UserLifecycleResource}. It always
 * changes the CALLER's preferences. An endpoint that let one person set another's would be an
 * endpoint that could put a colleague's product into a language they do not read, and there is no
 * administrative need it would serve: nobody chooses somebody else's palette.
 *
 * <p><b>Whose preferences, under an impersonation session.</b> The person being impersonated, not
 * the engineer — which matches {@code GET /api/v1/me}, and matters because the whole point of a
 * support session is to see and fix what the customer sees. {@code CurrentUser} deliberately
 * answers the other way (the actor, for audit attribution), so this resolves the subject the way
 * {@code /me} does rather than borrowing it. A support session is read-only unless the engineer
 * holds {@code support:impersonate_write}, so reaching this endpoint at all during one is already
 * a deliberate act ({@code ImpersonationFilter}).
 *
 * <p><b>Why {@code PUT} on a whole preferences document rather than {@code PATCH}.</b> The body's
 * fields are individually optional — an absent field leaves the stored value alone — which is
 * PATCH semantics wearing a PUT method, and it is worth naming rather than hiding. It is a PUT
 * because the resource is one small document a client replaces the interesting half of, and
 * because the alternative reading of PUT here (absent means clear) is the behaviour that would
 * make the theme switcher wipe the language somebody set on their phone. The parameter
 * documentation on {@link Preferences} is the contract; the method name is not.
 */
@RestController
@RequestMapping("/api/v1/users/me")
public class PreferencesResource {

    private final UserPreferencesService preferences;
    private final UserProvisioningService provisioning;
    private final AppUserRepository repository;

    public PreferencesResource(UserPreferencesService preferences,
            UserProvisioningService provisioning, AppUserRepository repository) {
        this.preferences = preferences;
        this.provisioning = provisioning;
        this.repository = repository;
    }

    /**
     * What somebody has told us about how to render the product.
     *
     * <p>Each field is three-valued on the way IN and two-valued on the way out:
     *
     * <ul>
     *   <li><b>absent / null</b> on a request means "leave this as it is". On a response it means
     *       "they have not told us", which is not a choice of anything and must not be rendered
     *       as one.
     *   <li><b>empty string</b> on a request means "clear it" — put me back to not having said.
     *   <li><b>a value</b> means what it says.
     * </ul>
     *
     * @param language a BCP-47 tag such as {@code tr} or {@code en-GB}. Not checked against the
     *                 languages the product currently ships in: that set changes without a
     *                 migration, and an unknown tag falls back where it is rendered
     * @param theme    {@code light}, {@code dark} or {@code system}. A closed set, so an unknown
     *                 value is a 400 rather than something to fall back from
     */
    public record Preferences(String language, String theme) {}

    @GetMapping("/preferences")
    public Preferences get(@AuthenticationPrincipal Jwt caller) {
        return view(subject(caller));
    }

    @PutMapping("/preferences")
    @ApiResponse(responseCode = "200", description = "The preferences as they now stand")
    @ApiResponse(responseCode = "400",
        description = "A theme this product does not have, or a string that is not a language tag",
        content = @Content(mediaType = ProblemDocumentation.PROBLEM_JSON,
            schema = @Schema(ref = ProblemDocumentation.REF)))
    public Preferences put(@RequestBody Preferences request, @AuthenticationPrincipal Jwt caller) {
        AppUser subject = subject(caller);
        return view(preferences.update(subject.getId(), request.language(), request.theme()));
    }

    /** The person these preferences belong to: the impersonated user if any, else the caller. */
    private AppUser subject(Jwt caller) {
        if (TenantContext.get() == null) {
            // Platform staff have no app_user row to hold a preference on. Refusing here says so;
            // letting it through produces the CannotCreateTransaction failure a tenant-less
            // session gives, which names nothing.
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Platform-side callers have no tenant identity here");
        }
        return ImpersonationContext.impersonatedUserId()
            .flatMap(repository::findById)
            // First sight provisions, exactly as /me does -- the same idempotent path, so a
            // person whose very first request happens to be this one gets an answer rather than
            // a 404 about themselves.
            .orElseGet(() -> provisioning.provision(caller));
    }

    private static Preferences view(AppUser user) {
        return new Preferences(user.getLanguage(),
            user.getTheme() == null ? null : user.getTheme().name());
    }
}
