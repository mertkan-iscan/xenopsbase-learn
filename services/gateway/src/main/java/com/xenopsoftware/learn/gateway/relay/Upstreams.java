package com.xenopsoftware.learn.gateway.relay;

import com.xenopsoftware.learn.gateway.config.GatewayProperties;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Which service answers a path (T-10.2).
 *
 * <h2>The order is the whole file, and it is the one thing to read twice</h2>
 *
 * <p>Six services answer under {@code /api}, and four of them answer under {@code /api/v1/me}:
 * identity owns {@code /me} itself, streaming owns {@code /me/nodes/{id}/playback-token} and
 * {@code /progress}, catalog owns {@code /me/home} and {@code /me/nodes/{id}/interstitials},
 * assessment owns {@code /me/tests}, {@code /me/attempts} and {@code /me/monitoring}, and
 * packaging owns {@code /me/runtime}.
 *
 * <p><b>The more specific rules come first and the first match wins.</b> Put {@code /api} at the
 * top and every playback-token request goes to identity and answers 404 — which is
 * indistinguishable, from the browser, from an entitlement refusal (T-2.4 renders those as a bare
 * 404 on purpose). The bug would present as "this learner is not entitled to this video", against
 * a learner who is.
 *
 * <h2>One rule cannot be expressed as a prefix, and that is why {@code *} exists</h2>
 *
 * <p>Streaming and catalog both answer under {@code /api/v1/me/nodes/{id}/…} — streaming for
 * playback and progress, catalog for the interstitials pinned inside that node's timeline (T-5.4).
 * The two are told apart by the segment <em>after</em> the id, which no prefix can reach.
 *
 * <p>So a pattern segment may be {@code *}, matching exactly one segment. It is deliberately the
 * only wildcard: a table that can express anything is a table nobody can read, and the moment a
 * second one is needed the honest answer is probably that two services are sharing a noun they
 * should not.
 *
 * <h2>Nothing else is proxied</h2>
 *
 * <p>An unmatched path under {@code /api} is a 404 from here rather than a default route to
 * identity. A default would mean that adding an endpoint to any service silently exposes it
 * through the gateway, which is the opposite of a routing table being a decision.
 *
 * <p>Two things are deliberately absent. {@code /api/v1/webhooks/media} is streaming's, and it is
 * a provider calling us rather than a browser: it must not be reachable through the door the
 * browser uses. And {@code /internal/…} is service-to-service (T-9.11), which is the same argument
 * with a different prefix.
 *
 * <p><b>There is one table.</b> {@code vite.config.ts} forwards {@code /api} wholesale to this
 * gateway in development rather than carrying a copy — an earlier version of this comment said
 * otherwise, and it was describing the world before the gateway existed.
 */
@Component
public class Upstreams {

    private final List<Route> routes;

    public Upstreams(GatewayProperties properties) {
        this.routes = List.of(
            // The one that needs a wildcard, first, because it is the most specific thing here.
            new Route("/api/v1/me/nodes/*/interstitials", properties.catalog()),

            // Then everything under /me, by the segment that follows it.
            new Route("/api/v1/me/nodes", properties.streaming()),
            new Route("/api/v1/me/home", properties.catalog()),
            new Route("/api/v1/me/tests", properties.assessment()),
            new Route("/api/v1/me/attempts", properties.assessment()),
            new Route("/api/v1/me/monitoring", properties.assessment()),
            // Where a learner got to inside a SCORM, cmi5 or HTML5 package (T-4.4). Packaging's
            // only learner-facing path, and the one the application calls on behalf of a wrapper
            // that holds no credential of its own (ADR-0105).
            new Route("/api/v1/me/runtime", properties.packaging()),

            new Route("/api/v1/telemetry", properties.reporting()),
            new Route("/api/v1/videos", properties.streaming()),

            /*
             * Packaging, and ONLY its management half.
             *
             * `/api/v1/uploads` is where an author reserves a package, gets an upload target and
             * asks for the archive to be processed. What is deliberately absent is any route to
             * `/served/**`, which is where packaging answers the CONTENT origin: an uploaded
             * package reached through this gateway would be an uploaded package on the
             * application's origin, with the application's DOM, cookies and session in reach --
             * the exact compromise ADR-0105 exists to prevent.
             *
             * That absence is not left to memory. This table has no default route (an unmatched
             * /api path is a 404 from here), and an ArchUnit rule in every module fails the build
             * on any mapping whose path contains "packages" -- which is why the resource below is
             * called `uploads` rather than the noun a reader would expect.
             */
            new Route("/api/v1/uploads", properties.packaging()),

            // Catalog: what training exists, who it reaches, and what is inside a video.
            new Route("/api/v1/content-items", properties.catalog()),
            new Route("/api/v1/courses", properties.catalog()),
            new Route("/api/v1/assignments", properties.catalog()),
            new Route("/api/v1/nodes", properties.catalog()),
            new Route("/api/v1/interstitials", properties.catalog()),

            // Assessment: banks, questions, tests, and everything about marking them.
            new Route("/api/v1/banks", properties.assessment()),
            new Route("/api/v1/shared-banks", properties.assessment()),
            new Route("/api/v1/questions", properties.assessment()),
            new Route("/api/v1/tests", properties.assessment()),
            new Route("/api/v1/sections", properties.assessment()),
            new Route("/api/v1/vocabulary", properties.assessment()),
            new Route("/api/v1/grading", properties.assessment()),

            // Identity last, and only for what nothing above claimed.
            new Route("/api", properties.identity()));
    }

    /** The base URL that should answer this path, or null when nothing here claims it. */
    public String forPath(String path) {
        String[] segments = path.split("/", -1);
        for (Route route : routes) {
            if (route.matches(segments)) {
                return route.target();
            }
        }
        return null;
    }

    private record Route(String pattern, String target) {

        /**
         * Whether this pattern is a segment-wise prefix of the path.
         *
         * <p>Segment-wise, not {@code startsWith}: {@code /api/v1/videosomething} is not under
         * {@code /api/v1/videos}, and matching by string prefix would route a future identity
         * endpoint into streaming for no reason anybody could see from either side.
         */
        boolean matches(String[] pathSegments) {
            String[] patternSegments = pattern.split("/", -1);
            if (pathSegments.length < patternSegments.length) {
                return false;
            }
            for (int index = 0; index < patternSegments.length; index++) {
                if (patternSegments[index].equals("*")) {
                    continue;
                }
                if (!patternSegments[index].equals(pathSegments[index])) {
                    return false;
                }
            }
            return true;
        }
    }
}
