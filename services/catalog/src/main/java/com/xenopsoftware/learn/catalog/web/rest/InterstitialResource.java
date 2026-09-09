package com.xenopsoftware.learn.catalog.web.rest;

import com.xenopsoftware.learn.catalog.home.LearnerIdentity;
import com.xenopsoftware.learn.catalog.interstitial.Interstitial;
import com.xenopsoftware.learn.catalog.interstitial.InterstitialService;
import com.xenopsoftware.learn.common.tenancy.TenantContext;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Interstitials: authoring them, and telling a player what to show (T-5.4).
 *
 * <p><b>There is no endpoint here that answers one.</b> That is the design and not a gap: an answer
 * is an assessment attempt, and a learner who could post "I answered it" straight to catalog would
 * have a one-request path past every blocking interstitial in the product. Answers arrive as
 * evidence, by event, into {@code InterstitialAnsweredHandler}.
 *
 * <p><b>The learner read is under {@code /me/} and takes no learner id</b>, for the reason the home
 * screen and the playback token do: the answer is only ever about the caller. It reports the
 * frontier as well as the markers, so a player renders one shape instead of deriving the rule a
 * second time — and the frontier it renders is the same integer streaming enforces (T-3.7). Two
 * computations of "where am I allowed to be" is one that disagrees.
 */
@RestController
@RequestMapping("/api/v1")
public class InterstitialResource {

    private final InterstitialService interstitials;
    private final LearnerIdentity identities;

    public InterstitialResource(InterstitialService interstitials, LearnerIdentity identities) {
        this.interstitials = interstitials;
        this.identities = identities;
    }

    /**
     * @param positionSeconds whole seconds into the item. Not checked against the video's length:
     *                        the duration is streaming's fact and catalog may not copy it
     *                        (ADR-0109), so an interstitial past the end is simply never reached
     * @param blocking        default true. "Answer this before going on" is what an author means
     *                        by putting a question inside a video
     * @param askAgain        default false. A learner who answered this and came back to re-watch
     *                        has answered it
     */
    public record InterstitialRequest(Integer positionSeconds, UUID questionId, Boolean blocking,
                                      Boolean askAgain) {}

    public record InterstitialView(UUID id, UUID nodeId, int positionSeconds, UUID questionId,
                                   boolean blocking, boolean askAgain) {}

    /**
     * @param frontierSecond the furthest second this learner may be credited for, or null when
     *                       nothing blocks them. <b>The player pauses here.</b> It is also what
     *                       streaming enforces, so a player that ignores it does not gain seconds
     *                       — it only stops rendering the question that would move it
     * @param answered       which of these this learner has satisfied, so the player can mark them
     *                       without asking one by one
     */
    public record PlayerView(UUID nodeId, Integer frontierSecond, List<InterstitialView> markers,
                             List<UUID> answered) {}

    @GetMapping("/nodes/{nodeId}/interstitials")
    public List<InterstitialView> on(@PathVariable UUID nodeId) {
        return interstitials.on(nodeId).stream().map(InterstitialResource::view).toList();
    }

    @PostMapping("/nodes/{nodeId}/interstitials")
    @ResponseStatus(HttpStatus.CREATED)
    public InterstitialView add(@PathVariable UUID nodeId,
            @RequestBody InterstitialRequest request) {
        if (request == null || request.positionSeconds() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "An interstitial sits at a second of the item; say which");
        }
        return view(interstitials.add(nodeId, request.positionSeconds(), request.questionId(),
            request.blocking() == null || request.blocking(),
            request.askAgain() != null && request.askAgain()));
    }

    /**
     * Moves one, or changes whether it blocks. Both in one verb because both are the same edit to
     * an author: this marker, but different.
     */
    @PatchMapping("/interstitials/{id}")
    public InterstitialView edit(@PathVariable UUID id, @RequestBody InterstitialRequest request) {
        if (request == null) {
            return view(interstitials.get(id));
        }
        Interstitial current = request.positionSeconds() == null
            ? interstitials.get(id) : interstitials.move(id, request.positionSeconds());
        if (request.blocking() != null || request.askAgain() != null) {
            current = interstitials.reconfigure(id,
                request.blocking() == null ? current.isBlocking() : request.blocking(),
                request.askAgain() == null ? current.isAskAgain() : request.askAgain());
        }
        return view(current);
    }

    @DeleteMapping("/interstitials/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable UUID id) {
        interstitials.remove(id);
    }

    /**
     * What the player needs to render this node's interruptions, for the caller.
     *
     * @param viewing the playback session, which only matters to a marker configured to ask again.
     *                Absent means "as though this were a fresh viewing", which is what an author
     *                previewing the node wants
     */
    @GetMapping("/me/nodes/{nodeId}/interstitials")
    public PlayerView forMe(@PathVariable UUID nodeId,
            @RequestParam(required = false) String viewing) {
        UUID learnerId = caller();
        return new PlayerView(nodeId, interstitials.frontierOf(nodeId, learnerId, viewing),
            interstitials.on(nodeId).stream().map(InterstitialResource::view).toList(),
            interstitials.answeredBy(nodeId, learnerId, viewing));
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
                // client's correct response is to try again.
                "This cannot be answered right now."));
    }

    private static InterstitialView view(Interstitial interstitial) {
        return new InterstitialView(interstitial.getId(), interstitial.getNodeId(),
            interstitial.getPositionSeconds(), interstitial.getQuestionId(),
            interstitial.isBlocking(), interstitial.isAskAgain());
    }
}
