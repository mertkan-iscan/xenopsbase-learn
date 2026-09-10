package com.xenopsoftware.learn.identity.web.rest;

import com.xenopsoftware.learn.common.tenancy.TenantContext;
import com.xenopsoftware.learn.identity.user.AppUser;
import com.xenopsoftware.learn.identity.user.AppUserRepository;
import com.xenopsoftware.learn.identity.user.UserProvisioningService;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Who the caller is, and who a referenced person is (T-1.2).
 *
 * <p>{@code /me} is where first-login provisioning happens: the first authenticated call creates
 * the {@link AppUser} row, every later call returns it. {@code /users/{id}} is the published
 * lookup other services use instead of copying display names into tables they would never keep
 * current — they hold the id, and resolve it here when they need a person to render.
 */
@RestController
@RequestMapping("/api/v1")
public class UserResource {

    private final UserProvisioningService provisioningService;
    private final AppUserRepository repository;

    public UserResource(UserProvisioningService provisioningService, AppUserRepository repository) {
        this.provisioningService = provisioningService;
        this.repository = repository;
    }

    /**
     * What the caller looks like as an {@link AppUser}: id, email, display name, status, and how
     * they want the product rendered (T-10.9).
     *
     * <p><b>The preferences ride along on {@code /me} rather than needing a second call</b>, and
     * that is a rendering decision rather than a saving of bytes. Language and theme have to be
     * applied before the first screen is painted; fetched separately they arrive a round trip
     * later, and the product visibly changes language and colour in front of somebody who already
     * told us. {@code PUT /api/v1/users/me/preferences} is where they are changed.
     *
     * @param language a BCP-47 tag, or null when they have not told us — <b>which is not a choice
     *                 of English</b>. The caller falls back to what the browser prefers
     * @param theme    {@code LIGHT}, {@code DARK}, {@code SYSTEM}, or null when they have not told
     *                 us — which renders like {@code SYSTEM} and does not mean it
     */
    public record Me(UUID id, String tenant, String email, String displayName, String status,
                     String language, String theme) {}

    /** The cross-service shape: everything another module may hold or render about a person. */
    public record UserSummary(UUID id, String displayName) {}

    @GetMapping("/me")
    public Me me(@AuthenticationPrincipal Jwt jwt) {
        requireTenantSide();
        // Under an impersonation session this answers with the person being impersonated, not
        // the engineer (T-2.8). Reproducing what somebody sees starts here -- every screen asks
        // /me first -- and provisioning the caller instead would both give the wrong answer and
        // create their account inside the customer's company.
        AppUser user = com.xenopsoftware.learn.identity.impersonation.ImpersonationContext
            .impersonatedUserId()
            .flatMap(repository::findById)
            .orElseGet(() -> provisioningService.provision(jwt));
        return new Me(user.getId(), TenantContext.require(), user.getEmail(),
            user.getDisplayName(), user.getStatus().name(),
            user.getLanguage(),
            // `null` stays null all the way to the browser. Substituting SYSTEM here would be the
            // one line that erases V14's distinction, and it would do it invisibly.
            user.getTheme() == null ? null : user.getTheme().name());
    }

    @GetMapping("/users/{id}")
    public UserSummary user(@PathVariable UUID id) {
        requireTenantSide();
        // Tenant-filtered by the persistence layer: another tenant's id is simply not found,
        // which is the 404-not-403 shape the isolation claim (ADR-0102) promises.
        return repository.findById(id)
            .map(user -> new UserSummary(user.getId(), user.getDisplayName()))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private void requireTenantSide() {
        if (TenantContext.get() == null) {
            // Platform staff are not app_users -- they belong to no customer, and their
            // identity story is E2's (support impersonation is T-2.8). Refusing here is
            // clearer than the CannotCreateTransaction failure the session would produce.
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Platform-side callers have no tenant identity here");
        }
    }
}
