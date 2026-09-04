package com.xenopsoftware.learn.streaming.media.cloudflare;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.SignedJWT;
import com.xenopsoftware.learn.streaming.media.MediaAssetState;
import com.xenopsoftware.learn.streaming.media.MediaAssetStatus;
import com.xenopsoftware.learn.streaming.media.PlaybackGrant;
import com.xenopsoftware.learn.streaming.media.PlaybackToken;
import com.xenopsoftware.learn.streaming.media.ProviderEvent;
import com.xenopsoftware.learn.streaming.media.UploadRequest;
import com.xenopsoftware.learn.streaming.media.UploadTarget;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Date;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * The adapter against a mock of Cloudflare's API — the request shapes, the state-vocabulary
 * mapping, and the one operation that must involve no HTTP at all: token minting is local
 * signing (ADR-0101), verified here against the very key pair the adapter was configured with.
 *
 * <p>What this deliberately cannot prove: that the real API accepts these requests. That proof
 * needs T-9.14's account, and pretending a mock provides it is the "green local build proves
 * edge delivery" mistake local-stack.md warns about.
 */
class CloudflareStreamAdapterTest {

    private static final String BASE = "https://api.cloudflare.com/client/v4/accounts/acct-1";
    private static final String WEBHOOK_SECRET = "notification-secret";

    private MockRestServiceServer server;
    private CloudflareStreamAdapter adapter;
    private RSAKey signingKey;

    @BeforeEach
    void bindAdapterToMockServer() throws Exception {
        signingKey = new RSAKeyGenerator(2048).keyID("key-1").generate();
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new CloudflareStreamAdapter(builder, new CloudflareStreamProperties(
            "acct-1", "api-token-1", "abc123", "key-1",
            Base64.getEncoder().encodeToString(
                signingKey.toJSONString().getBytes(StandardCharsets.UTF_8)),
            WEBHOOK_SECRET));
    }

    @Test
    void createUploadTargetSpeaksTusAndReturnsTheOpaqueRefFromTheHeaders() {
        server.expect(requestTo(BASE + "/stream?direct_user=true"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Bearer api-token-1"))
            .andExpect(header("Tus-Resumable", "1.0.0"))
            // The declared length is binding at ingest -- what makes the pre-issuance quota
            // check (T-3.2) an enforcement rather than a hope.
            .andExpect(header("Upload-Length", "5000000000"))
            .andRespond(withStatus(HttpStatus.CREATED)
                .header("stream-media-id", "cf-uid-123")
                .header("Location", "https://upload.cloudflarestream.com/tus/cf-uid-123"));

        UploadTarget target = adapter.createUploadTarget(new UploadRequest(3600, 5_000_000_000L));

        assertThat(target.providerRef()).isEqualTo("cf-uid-123");
        assertThat(target.uploadUrl().toString())
            .isEqualTo("https://upload.cloudflarestream.com/tus/cf-uid-123");
        server.verify();
    }

    @Test
    void statusMapsTheVendorVocabularyOntoOurs() {
        server.expect(requestTo(BASE + "/stream/cf-uid-123"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("""
                {"success": true, "result": {
                    "uid": "cf-uid-123",
                    "status": {"state": "ready", "errorReasonText": ""},
                    "duration": 542.5,
                    "readyToStream": true}}
                """, MediaType.APPLICATION_JSON));

        MediaAssetStatus status = adapter.status("cf-uid-123");

        assertThat(status.state()).isEqualTo(MediaAssetState.READY);
        assertThat(status.durationSeconds()).isEqualTo(542.5);
    }

    @Test
    void aVendor404IsGoneNotAnException() {
        server.expect(requestTo(BASE + "/stream/cf-uid-gone"))
            .andRespond(withStatus(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"success\": false, \"result\": null}"));

        assertThat(adapter.status("cf-uid-gone").state()).isEqualTo(MediaAssetState.GONE);
    }

    @Test
    void anUnknownVendorStateDegradesToProcessingInsteadOfBreaking() {
        server.expect(requestTo(BASE + "/stream/cf-uid-odd"))
            .andRespond(withSuccess("""
                {"success": true, "result": {
                    "uid": "cf-uid-odd",
                    "status": {"state": "live-connected"},
                    "duration": -1}}
                """, MediaType.APPLICATION_JSON));

        assertThat(adapter.status("cf-uid-odd").state()).isEqualTo(MediaAssetState.PROCESSING);
    }

    @Test
    void deleteIsIdempotentAcrossTheVendors404() {
        server.expect(requestTo(BASE + "/stream/cf-uid-123"))
            .andExpect(method(HttpMethod.DELETE))
            .andRespond(withSuccess());
        server.expect(requestTo(BASE + "/stream/cf-uid-123"))
            .andExpect(method(HttpMethod.DELETE))
            .andRespond(withStatus(HttpStatus.NOT_FOUND));

        adapter.delete("cf-uid-123");
        adapter.delete("cf-uid-123");
        server.verify();
    }

    @Test
    void mintingAPlaybackTokenSignsLocallyWithZeroNetworkCalls() throws Exception {
        // No server.expect(): any HTTP request here fails the test, which is the point --
        // ADR-0101's hot path is an entitlement decision and a signature, nothing else.
        PlaybackToken token = adapter.mintPlaybackToken("cf-uid-123",
            new PlaybackGrant("sub-viewer", Duration.ofMinutes(10)));

        // The delivery host is the account's own subdomain and the signed token IS the path
        // segment -- Stream's shape, and knowledge that lives only in this package (T-3.1).
        assertThat(token.manifestUrl().toString())
            .isEqualTo("https://customer-abc123.cloudflarestream.com/" + token.token()
                + "/manifest/video.m3u8");
        assertThat(token.manifestUrl().getHost())
            .as("never our API, and never this service")
            .doesNotContain("api.cloudflare.com");

        SignedJWT jwt = SignedJWT.parse(token.token());
        assertThat(jwt.verify(new RSASSAVerifier(signingKey.toRSAPublicKey()))).isTrue();
        assertThat(jwt.getHeader().getKeyID()).isEqualTo("key-1");
        assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo("cf-uid-123");
        assertThat(jwt.getJWTClaimsSet().getStringClaim("kid")).isEqualTo("key-1");
        // Signed, and deliberately not enforced by the vendor -- PlaybackGrant says why at
        // length. It makes a token attributable, not unshareable.
        assertThat(jwt.getJWTClaimsSet().getStringClaim("viewer")).isEqualTo("sub-viewer");
        assertThat(jwt.getJWTClaimsSet().getExpirationTime())
            .isCloseTo(Date.from(token.expiresAt()), 1000);
        server.verify();
    }

    @Test
    void anUnsignedWebhookIsNotAMessage() {
        // Verify before parsing: an unsigned body never reaches the JSON at all, which is what
        // keeps a parser from being an attack surface.
        assertThat(adapter.interpretWebhook(Map.of(), READY_BODY.getBytes(StandardCharsets.UTF_8)))
            .isEmpty();
        assertThat(adapter.interpretWebhook(
            Map.of("webhook-signature", "time=" + now() + ",sig1=deadbeef"),
            READY_BODY.getBytes(StandardCharsets.UTF_8))).isEmpty();
    }

    @Test
    void aCorrectlySignedWebhookIsInterpretedInOurVocabulary() throws Exception {
        long time = now();
        Map<String, String> headers = Map.of("webhook-signature",
            "time=" + time + ",sig1=" + sign(time + "." + READY_BODY));

        ProviderEvent event = adapter.interpretWebhook(headers,
            READY_BODY.getBytes(StandardCharsets.UTF_8)).orElseThrow();

        assertThat(event.providerRef()).isEqualTo("cf-uid-123");
        assertThat(event.state()).isEqualTo(MediaAssetState.READY);
        assertThat(event.durationSeconds()).isEqualTo(542.5);
        // Derived, because Cloudflare sends no event id: a redelivery of this notification
        // collides, a genuinely different state for the same asset does not.
        assertThat(event.eventId()).isEqualTo("cf-uid-123:ready");
    }

    @Test
    void aCorrectlySignedBodyFromLastWeekIsStillRefused() throws Exception {
        long stale = now() - 3600;
        Map<String, String> headers = Map.of("webhook-signature",
            "time=" + stale + ",sig1=" + sign(stale + "." + READY_BODY));

        // The signature is valid. Replaying it is the attack this refuses.
        assertThat(adapter.interpretWebhook(headers, READY_BODY.getBytes(StandardCharsets.UTF_8)))
            .isEmpty();
    }

    @Test
    void anEncodeFailureCarriesTheProvidersOwnReason() throws Exception {
        String body = """
            {"uid":"cf-uid-9","status":{"state":"error","errorReasonText":"audio codec not supported"}}""";
        long time = now();
        Map<String, String> headers = Map.of("webhook-signature",
            "time=" + time + ",sig1=" + sign(time + "." + body));

        ProviderEvent event = adapter.interpretWebhook(headers,
            body.getBytes(StandardCharsets.UTF_8)).orElseThrow();

        assertThat(event.state()).isEqualTo(MediaAssetState.ERRORED);
        // An author told "encoding failed" opens a ticket; one told this re-exports the file.
        assertThat(event.error()).isEqualTo("audio codec not supported");
    }

    private static final String READY_BODY =
        "{\"uid\":\"cf-uid-123\",\"status\":{\"state\":\"ready\"},\"duration\":542.5}";

    private static long now() {
        return java.time.Instant.now().getEpochSecond();
    }

    private static String sign(String payload) throws Exception {
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(
            WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return java.util.HexFormat.of().formatHex(
            mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }
}
