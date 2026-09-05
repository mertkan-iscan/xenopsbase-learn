package com.xenopsoftware.learn.catalog.web.rest;

import com.xenopsoftware.learn.catalog.home.Home;
import com.xenopsoftware.learn.catalog.home.HomeView;
import com.xenopsoftware.learn.catalog.home.LearnerIdentity;
import com.xenopsoftware.learn.common.tenancy.TenantContext;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * What a learner sees on opening the platform (T-5.8).
 *
 * <p>Under {@code /me/} and taking no learner id, for the reason the playback token does (T-3.4):
 * the answer is only ever about the caller, and an endpoint that took an id would be one refactor
 * away from being one that answers about somebody else. An administrator looking at what a
 * particular person owes has {@code /api/v1/assignments/of/{learner}}, which is a different
 * question with a different authorization story.
 *
 * <p>One request, and everything the screen needs in it. The alternative — a call for assignments,
 * one for each course's structure, one for gates, one for progress — is the same N+1 moved into the
 * browser, where it also costs a round trip each.
 */
@RestController
@RequestMapping("/api/v1")
public class HomeResource {

    private final Home home;
    private final LearnerIdentity identities;

    public HomeResource(Home home, LearnerIdentity identities) {
        this.home = home;
        this.identities = identities;
    }

    @GetMapping("/me/home")
    public HomeView home() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            // Everything under /api is authenticated by the security chain, so reaching here
            // without a JWT is a wiring mistake rather than a caller error.
            throw new IllegalStateException("A home screen is only ever about a verified caller");
        }
        String tenantId = TenantContext.require();
        UUID learnerId = identities.current(tenantId, token.getToken().getSubject())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                // Not a 500 and not a 404: the request is fine, one dependency is not, and the
                // client's correct response is to try again rather than to tell the learner they
                // do not exist.
                "This screen cannot be built right now."));
        return home.of(learnerId);
    }
}
