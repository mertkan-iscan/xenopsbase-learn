package com.xenopsoftware.learn.gateway.relay;

import com.xenopsoftware.learn.gateway.config.GatewayProperties;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Which service answers a path (T-10.2).
 *
 * <h2>The order is the whole file, and it is the one thing to read twice</h2>
 *
 * <p>Two services answer under {@code /api/v1/me}: identity owns {@code /me} and
 * {@code /me/reach/...}, streaming owns {@code /me/nodes/{id}/playback-token} (T-3.4). A prefix
 * router cannot split those by prefix alone, so <b>the more specific rules come first and the
 * first match wins</b>. Put {@code /api} at the top and every playback-token request goes to
 * identity and answers 404 — which is indistinguishable, from the browser, from an entitlement
 * refusal.
 *
 * <p>{@code vite.config.ts} carries the same table for development, with the same warning. Two
 * copies of a routing table is one more than anybody wants, and they are two because one runs in
 * Node inside a developer's laptop and one runs in a JVM in a cluster. What keeps them honest is
 * that they are both short, both commented, and {@code UpstreamsTest} asserts the collision case
 * on this side.
 *
 * <h2>Nothing else is proxied</h2>
 *
 * <p>An unmatched path under {@code /api} is a 404 from here rather than a default route to
 * identity. A default would mean that adding an endpoint to any service silently exposes it
 * through the gateway, which is the opposite of a routing table being a decision.
 */
@Component
public class Upstreams {

    private final List<Route> routes;

    public Upstreams(GatewayProperties properties) {
        this.routes = List.of(
            new Route("/api/v1/telemetry", properties.reporting()),
            new Route("/api/v1/me/nodes", properties.streaming()),
            new Route("/api/v1/videos", properties.streaming()),
            new Route("/api", properties.identity()));
    }

    /** The base URL that should answer this path, or null when nothing here claims it. */
    public String forPath(String path) {
        for (Route route : routes) {
            if (path.equals(route.prefix()) || path.startsWith(route.prefix() + "/")) {
                return route.target();
            }
        }
        return null;
    }

    private record Route(String prefix, String target) {}
}
