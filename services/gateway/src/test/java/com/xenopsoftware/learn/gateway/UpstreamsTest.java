package com.xenopsoftware.learn.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.xenopsoftware.learn.gateway.config.GatewayProperties;
import com.xenopsoftware.learn.gateway.relay.Upstreams;
import org.junit.jupiter.api.Test;

/**
 * The routing table, and the collision it exists to get right (T-10.2).
 *
 * <p>Two services answer under {@code /api/v1/me}. If the general rule is consulted first, every
 * playback token goes to identity and comes back 404 — which, from the browser, is exactly what an
 * entitlement refusal looks like (T-2.4 renders those as a bare 404 on purpose). So the bug would
 * present as "this learner is not entitled to this video", against a learner who is.
 */
class UpstreamsTest {

    private static final String IDENTITY = "http://identity:8082";
    private static final String STREAMING = "http://streaming:8083";
    private static final String REPORTING = "http://reporting:8084";
    private static final String CATALOG = "http://catalog:8085";
    private static final String ASSESSMENT = "http://assessment:8086";
    private static final String PACKAGING = "http://packaging:8087";

    private final Upstreams upstreams = new Upstreams(new GatewayProperties(
        IDENTITY, STREAMING, REPORTING, CATALOG, ASSESSMENT, PACKAGING, "http://app"));

    @Test
    void theMoreSpecificRuleWins() {
        assertThat(upstreams.forPath("/api/v1/me/nodes/abc/playback-token")).isEqualTo(STREAMING);
        assertThat(upstreams.forPath("/api/v1/me")).isEqualTo(IDENTITY);
        assertThat(upstreams.forPath("/api/v1/me/reach/groups")).isEqualTo(IDENTITY);
    }

    /**
     * THE COLLISION THAT NEEDED A WILDCARD.
     *
     * <p>Streaming and catalog both answer under {@code /api/v1/me/nodes/{id}/…} — playback and
     * progress on one side, the interstitials pinned inside that node's timeline on the other
     * (T-5.4). They are told apart by the segment <b>after</b> the id, which no prefix can reach.
     *
     * <p>Getting it wrong routes a learner's interstitial read into streaming, which answers 404 —
     * and a player reading that as "there is nothing pinned in this video" would let the learner
     * straight past a question that is supposed to stop them.
     */
    @Test
    void twoServicesUnderOneNodePathAreToldApartBySegment() {
        assertThat(upstreams.forPath("/api/v1/me/nodes/abc/interstitials")).isEqualTo(CATALOG);
        assertThat(upstreams.forPath("/api/v1/me/nodes/abc/progress")).isEqualTo(STREAMING);
        assertThat(upstreams.forPath("/api/v1/me/nodes/abc/playback-token")).isEqualTo(STREAMING);
    }

    @Test
    void everythingUnderMeGoesToWhicheverServiceOwnsThatWord() {
        assertThat(upstreams.forPath("/api/v1/me/home")).isEqualTo(CATALOG);
        assertThat(upstreams.forPath("/api/v1/me/tests/abc/attempts")).isEqualTo(ASSESSMENT);
        assertThat(upstreams.forPath("/api/v1/me/attempts/abc/review")).isEqualTo(ASSESSMENT);
        assertThat(upstreams.forPath("/api/v1/me/monitoring")).isEqualTo(ASSESSMENT);
        assertThat(upstreams.forPath("/api/v1/me/timezone")).isEqualTo(IDENTITY);
    }

    @Test
    void eachServiceOwnsItsOwnPrefix() {
        assertThat(upstreams.forPath("/api/v1/telemetry/playback")).isEqualTo(REPORTING);
        assertThat(upstreams.forPath("/api/v1/videos/abc")).isEqualTo(STREAMING);
        assertThat(upstreams.forPath("/api/v1/courses/abc/modules")).isEqualTo(CATALOG);
        assertThat(upstreams.forPath("/api/v1/content-items")).isEqualTo(CATALOG);
        assertThat(upstreams.forPath("/api/v1/assignments")).isEqualTo(CATALOG);
        assertThat(upstreams.forPath("/api/v1/nodes/abc/interstitials")).isEqualTo(CATALOG);
        assertThat(upstreams.forPath("/api/v1/interstitials/abc")).isEqualTo(CATALOG);
        assertThat(upstreams.forPath("/api/v1/banks")).isEqualTo(ASSESSMENT);
        assertThat(upstreams.forPath("/api/v1/shared-banks/abc/copies")).isEqualTo(ASSESSMENT);
        assertThat(upstreams.forPath("/api/v1/questions/abc")).isEqualTo(ASSESSMENT);
        assertThat(upstreams.forPath("/api/v1/tests/abc/sections")).isEqualTo(ASSESSMENT);
        assertThat(upstreams.forPath("/api/v1/sections/abc/pool")).isEqualTo(ASSESSMENT);
        assertThat(upstreams.forPath("/api/v1/vocabulary/tags")).isEqualTo(ASSESSMENT);
        assertThat(upstreams.forPath("/api/v1/grading/queue")).isEqualTo(ASSESSMENT);
        assertThat(upstreams.forPath("/api/v1/users/abc")).isEqualTo(IDENTITY);
    }

    /**
     * A prefix is a path segment, not a string.
     *
     * <p>{@code /api/v1/videosomething} is not under {@code /api/v1/videos}, and matching it by
     * {@code startsWith} alone would route a future identity endpoint into streaming for no reason
     * anybody could see from either side.
     */
    @Test
    void aPrefixMatchesOnSegmentBoundaries() {
        assertThat(upstreams.forPath("/api/v1/videosomething")).isEqualTo(IDENTITY);
        assertThat(upstreams.forPath("/api/v1/testsomething")).isEqualTo(IDENTITY);
        assertThat(upstreams.forPath("/api/v1/banksomething")).isEqualTo(IDENTITY);
    }

    /**
     * A provider webhook is not a browser's business, and it is absent from the table.
     *
     * <p>{@code /api/v1/webhooks/media} is streaming's (T-3.1). No rule here claims it, so it falls
     * through to identity's catch-all and answers 404 there — which is the point: <b>it never
     * reaches streaming through this door.</b>
     *
     * <p>Stated precisely because the weaker version is easy to believe: the path is not
     * unroutable, it is unrouted <em>to the service that would act on it</em>. If the catch-all
     * ever narrows, this assertion should become {@code isNull()} rather than being deleted.
     */
    @Test
    void aProviderWebhookNeverReachesStreamingThroughThisDoor() {
        assertThat(upstreams.forPath("/api/v1/webhooks/media"))
            .isNotEqualTo(STREAMING)
            .isEqualTo(IDENTITY);
    }

    /**
     * ONLY THE MANAGEMENT HALF OF PACKAGING IS BEHIND THIS DOOR (ADR-0105).
     *
     * <p>{@code /api/v1/uploads} is where an author reserves a package and asks for the archive to
     * be processed, and it routes like anything else. {@code /served/**} is where that same service
     * answers the CONTENT origin, and it must never be reachable here: an uploaded package served
     * through this gateway is an uploaded package on the application's origin, with the
     * application's DOM, cookies and session in reach — which is the compromise the whole decision
     * exists to prevent.
     *
     * <p>Nothing has to be REMOVED for that to break. It breaks by somebody ADDING a route, on a
     * Friday, to make a demo work — so the absence is asserted rather than assumed, next to the
     * webhook rule above, which exists for the same reason.
     */
    @Test
    void onlyTheManagementHalfOfPackagingIsReachableThroughThisDoor() {
        assertThat(upstreams.forPath("/api/v1/uploads")).isEqualTo(PACKAGING);
        assertThat(upstreams.forPath("/api/v1/uploads/abc/ingest")).isEqualTo(PACKAGING);

        // The content origin's route, and the path prefix a browser reaches it by. Neither is in
        // the table at all, so this door does not open onto either.
        assertThat(upstreams.forPath("/served/acme/abc/launch")).isNull();
        assertThat(upstreams.forPath("/served/acme/abc/files/index.html")).isNull();
        assertThat(upstreams.forPath("/packages/acme/abc/files/index.html")).isNull();

        /*
         * Under /api the answer is identity rather than null, because the table's last rule is a
         * catch-all for the service that owns the most of it -- and that is exactly as safe.
         *
         * The property being asserted is not "these paths are refused", it is "these paths cannot
         * reach PACKAGING". A request that lands on identity gets a 404 from a service that has
         * never heard of a package and holds none of the files, which is the same outcome by a
         * different route. What would break the decision is either of these answering
         * `PACKAGING`.
         */
        assertThat(upstreams.forPath("/api/v1/served/acme/abc/launch")).isEqualTo(IDENTITY);
        assertThat(upstreams.forPath("/api/v1/packages")).isEqualTo(IDENTITY);
    }

    @Test
    void nothingOutsideTheTableIsProxied() {
        // Not a default to identity: a default means every endpoint any service ever adds is
        // exposed here the moment it exists.
        assertThat(upstreams.forPath("/internal/metrics")).isNull();
        assertThat(upstreams.forPath("/apidocs")).isNull();
    }
}
