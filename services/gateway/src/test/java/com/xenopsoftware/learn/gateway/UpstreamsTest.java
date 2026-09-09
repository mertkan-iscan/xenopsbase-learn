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

    private final Upstreams upstreams = new Upstreams(new GatewayProperties(
        "http://identity:8082", "http://streaming:8083", "http://reporting:8084", "http://app"));

    @Test
    void theMoreSpecificRuleWins() {
        assertThat(upstreams.forPath("/api/v1/me/nodes/abc/playback-token"))
            .isEqualTo("http://streaming:8083");
        assertThat(upstreams.forPath("/api/v1/me")).isEqualTo("http://identity:8082");
        assertThat(upstreams.forPath("/api/v1/me/reach/groups")).isEqualTo("http://identity:8082");
    }

    @Test
    void eachServiceOwnsItsOwnPrefix() {
        assertThat(upstreams.forPath("/api/v1/telemetry/playback")).isEqualTo("http://reporting:8084");
        assertThat(upstreams.forPath("/api/v1/videos/abc")).isEqualTo("http://streaming:8083");
        assertThat(upstreams.forPath("/api/v1/banks")).isEqualTo("http://identity:8082");
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
        assertThat(upstreams.forPath("/api/v1/videosomething")).isEqualTo("http://identity:8082");
    }

    @Test
    void nothingOutsideTheTableIsProxied() {
        // Not a default to identity: a default means every endpoint any service ever adds is
        // exposed here the moment it exists.
        assertThat(upstreams.forPath("/internal/metrics")).isNull();
        assertThat(upstreams.forPath("/apidocs")).isNull();
    }
}
