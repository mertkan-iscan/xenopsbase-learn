package com.xenopsoftware.learn.identity.web.rest;

import com.xenopsoftware.learn.identity.user.AppUser;
import com.xenopsoftware.learn.identity.user.UserLifecycleService;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The people-administration surface (T-1.9): invite, accept, deactivate, reactivate, correct an
 * address, and import a spreadsheet.
 *
 * <p>These are the first tenant-side endpoints in this service to carry a real check.
 * {@code user:manage} has been in the catalog since T-2.1 and grantable since T-2.3, and
 * {@code CatalogCoverageTest} has been holding a note saying "T-1.9 will wire it" ever since;
 * this is that.
 *
 * <p>Acceptance is the exception, and it has to be: the person accepting holds nothing yet —
 * that is what accepting means — so the token is the whole credential.
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserLifecycleResource {

    private final UserLifecycleService lifecycle;
    private final com.xenopsoftware.learn.identity.audit.CurrentUser currentUser;

    public UserLifecycleResource(UserLifecycleService lifecycle,
            com.xenopsoftware.learn.identity.audit.CurrentUser currentUser) {
        this.lifecycle = lifecycle;
        this.currentUser = currentUser;
    }

    public record InviteRequest(String email, String displayName) {}

    public record AcceptRequest(String token) {}

    public record UpdateUserRequest(String email, String displayName) {}

    /**
     * @param timeZone an IANA zone id such as {@code Europe/Istanbul}, or empty to clear it and
     *                 go back to having not said
     */
    public record TimeZoneRequest(String timeZone) {}

    /** The token appears here and nowhere else, ever again. */
    public record InvitationView(UUID userId, String email, String displayName, String token,
                                 Instant expiresAt) {}

    /**
     * @param timeZone the IANA zone their deadlines are reckoned in, or null when they have not
     *                 said (T-5.6). Returned because it is normalised on the way in — a caller
     *                 that set one should be able to see what was actually stored rather than
     *                 trust that their string survived
     */
    public record PersonView(UUID id, String email, String displayName, String status,
                             Instant deactivatedAt, String timeZone) {

        static PersonView of(AppUser user) {
            return new PersonView(user.getId(), user.getEmail(), user.getDisplayName(),
                user.getStatus().name(), user.getDeactivatedAt(), user.getTimeZone());
        }
    }

    @PostMapping("/invitations")
    @PreAuthorize("hasPermission('user', 'manage')")
    public InvitationView invite(@RequestBody InviteRequest request) {
        UserLifecycleService.Invitation invitation =
            lifecycle.invite(request.email(), request.displayName());
        return new InvitationView(invitation.userId(), invitation.email(), invitation.displayName(),
            invitation.token(), invitation.expiresAt());
    }

    /**
     * No {@code @PreAuthorize}, deliberately: the caller is somebody signing in for the first
     * time with an invitation in their hand. Requiring a permission would require a grant, and a
     * grant requires the account this call is what creates.
     */
    @PostMapping("/invitations/accept")
    public PersonView accept(@RequestBody AcceptRequest request,
            @AuthenticationPrincipal Jwt caller) {
        return PersonView.of(lifecycle.accept(request.token(), caller));
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasPermission('user', 'manage')")
    public PersonView deactivate(@PathVariable UUID id) {
        return PersonView.of(lifecycle.deactivate(id));
    }

    @PostMapping("/{id}/reactivate")
    @PreAuthorize("hasPermission('user', 'manage')")
    public PersonView reactivate(@PathVariable UUID id) {
        return PersonView.of(lifecycle.reactivate(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasPermission('user', 'manage')")
    public PersonView update(@PathVariable UUID id, @RequestBody UpdateUserRequest request) {
        return PersonView.of(lifecycle.update(id, request.email(), request.displayName()));
    }

    /**
     * Where somebody is, for the deadlines they are held to (T-5.6).
     *
     * <p><b>No {@code @PreAuthorize}, and it takes no id.</b> It always changes the CALLER's own
     * timezone. Moving to Berlin is not an administrative act, and an endpoint that let one person
     * set another's would be one that moved somebody else's compliance deadline — so the identity
     * comes from the token rather than from the path, and there is nothing to get wrong.
     */
    @PutMapping("/me/timezone")
    public PersonView moveTo(@RequestBody TimeZoneRequest request) {
        return PersonView.of(lifecycle.moveTo(currentUser.requireId(), request.timeZone()));
    }

    /**
     * The spreadsheet. {@code dryRun} defaults to true: an import that runs by default is an
     * import somebody runs by accident, and the report of what it <em>would</em> do costs one
     * extra call to read.
     */
    @PostMapping(value = "/import", consumes = "text/csv")
    @PreAuthorize("hasPermission('user', 'manage')")
    public UserLifecycleService.ImportReport importUsers(@RequestBody String csv,
            @RequestParam(name = "dryRun", defaultValue = "true") boolean dryRun) {
        return lifecycle.importUsers(csv, dryRun);
    }

    /** What an administrator needs to see about somebody: status included, unlike the summary. */
    @GetMapping("/{id}/status")
    @PreAuthorize("hasPermission('user', 'manage')")
    public PersonView status(@PathVariable UUID id) {
        return PersonView.of(lifecycle.get(id));
    }
}
