package com.xenopsoftware.learn.streaming.media.cloudflare;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.xenopsoftware.learn.streaming.media.MediaAssetState;
import com.xenopsoftware.learn.streaming.media.MediaAssetStatus;
import com.xenopsoftware.learn.streaming.media.MediaProvider;
import com.xenopsoftware.learn.streaming.media.PlaybackGrant;
import com.xenopsoftware.learn.streaming.media.PlaybackToken;
import com.xenopsoftware.learn.streaming.media.ProviderEvent;
import com.xenopsoftware.learn.streaming.media.UploadRequest;
import com.xenopsoftware.learn.streaming.media.UploadTarget;
import java.net.URI;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Cloudflare Stream behind the {@link MediaProvider} port. The vocabulary of this vendor —
 * account ids, {@code uid}s, {@code pendingupload} — enters and leaves within this package;
 * the ArchUnit rule in {@code TechnicalStructureTest} makes that a build failure to violate.
 *
 * <p>Two deliberate shapes. Uploads are <i>direct creator uploads</i>: we ask for a one-time
 * upload URL and hand it to the client, so video bytes never pass through a request thread of
 * ours (T-3.2). And playback tokens are signed <b>locally</b> with the Stream signing key —
 * zero network calls on the hot path, which is what "the backend only signs for it" (ADR-0101)
 * means in code.
 */
public class CloudflareStreamAdapter implements MediaProvider {

    public static final String PROVIDER_ID = "cloudflare-stream";

    private static final Logger LOG = LoggerFactory.getLogger(CloudflareStreamAdapter.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestClient api;
    private final String webhookSecret;
    private final String signingKeyId;
    private final String customerSubdomain;
    private final RSASSASigner signer;

    public CloudflareStreamAdapter(RestClient.Builder restClientBuilder, CloudflareStreamProperties properties) {
        this.api = restClientBuilder
            .baseUrl("https://api.cloudflare.com/client/v4/accounts/" + properties.accountId())
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiToken())
            .build();
        this.webhookSecret = properties.webhookSecret();
        this.signingKeyId = properties.signingKeyId();
        this.customerSubdomain = properties.customerSubdomain();
        this.signer = signerFor(properties.signingKeyJwk());
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public UploadTarget createUploadTarget(UploadRequest request) {
        // The tus flow, not the simpler direct_upload one: a two-hour master over a domestic
        // connection WILL be interrupted (T-3.2), and tus resumes at the byte offset instead of
        // starting over. The declared Upload-Length is binding at ingest, which is what lets
        // quota be enforced before the target is issued rather than after the bytes arrive.
        var response = api.post()
            .uri("/stream?direct_user=true")
            .header("Tus-Resumable", "1.0.0")
            .header("Upload-Length", String.valueOf(request.sizeBytes()))
            .header("Upload-Metadata", "maxDurationSeconds " + Base64.getEncoder()
                .encodeToString(String.valueOf(request.maxDurationSeconds())
                    .getBytes(StandardCharsets.US_ASCII)))
            .retrieve()
            .toBodilessEntity();
        String uid = response.getHeaders().getFirst("stream-media-id");
        URI uploadUrl = response.getHeaders().getLocation();
        if (uid == null || uploadUrl == null) {
            throw new IllegalStateException(
                "Stream's tus create answered without stream-media-id or Location; "
                + "the API contract moved and this adapter must move with it");
        }
        return new UploadTarget(uid, uploadUrl, Instant.now().plus(Duration.ofHours(1)));
    }

    @Override
    public MediaAssetStatus status(String providerRef) {
        VideoEnvelope envelope;
        try {
            envelope = api.get().uri("/stream/{uid}", providerRef).retrieve().body(VideoEnvelope.class);
        } catch (HttpClientErrorException.NotFound gone) {
            return MediaAssetStatus.of(MediaAssetState.GONE);
        }
        VideoResult video = envelope.result();
        String vendorState = video.status() == null ? "" : String.valueOf(video.status().state());
        MediaAssetState state = switch (vendorState) {
            case "pendingupload" -> MediaAssetState.PENDING_UPLOAD;
            case "downloading", "queued", "inprogress" -> MediaAssetState.PROCESSING;
            case "ready" -> MediaAssetState.READY;
            case "error" -> MediaAssetState.ERRORED;
            default -> {
                // A vendor vocabulary change must not break playback handling; PROCESSING is
                // the state T-3.3's reconciliation already knows to re-poll.
                LOG.warn("Unknown Stream state '{}' for a video; treating as PROCESSING", vendorState);
                yield MediaAssetState.PROCESSING;
            }
        };
        return new MediaAssetStatus(state, video.duration(),
            video.status() == null ? null : video.status().errorReasonText());
    }

    @Override
    public PlaybackToken mintPlaybackToken(String providerRef, PlaybackGrant grant) {
        Instant expiresAt = Instant.now().plus(grant.validity());
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .subject(providerRef)
            .claim("kid", signingKeyId)
            .expirationTime(Date.from(expiresAt))
            // Signed but not enforced: Stream has no per-viewer rule, so this makes a token
            // attributable rather than unshareable. PlaybackGrant says so at length, because
            // the difference is one somebody will otherwise assume the wrong way round.
            .claim("viewer", grant.viewerSubject())
            .build();
        SignedJWT jwt = new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKeyId).build(), claims);
        try {
            jwt.sign(signer);
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not sign a playback token", e);
        }
        String signed = jwt.serialize();
        // The delivery host is the account's own subdomain, never api.cloudflare.com: the API is
        // where we manage assets and the delivery domain is where a learner's browser goes, and
        // the whole point of ADR-0101 is that the second one is not us and not our API either.
        // Built here because this is the only package allowed to know the shape (T-3.1).
        return new PlaybackToken(signed,
            URI.create("https://customer-" + customerSubdomain + ".cloudflarestream.com/"
                + signed + "/manifest/video.m3u8"), expiresAt);
    }

    /**
     * Cloudflare signs a webhook as {@code Webhook-Signature: time=<unix>,sig1=<hex>}, the
     * signature being HMAC-SHA256 over {@code time.body} with the notification secret.
     *
     * <p>Three checks before a byte is parsed, and each has cost somebody an incident
     * somewhere: the signature, compared in constant time so it cannot be guessed by timing;
     * the timestamp, because a correctly signed body replayed next week is still correctly
     * signed; and nothing else, because a parser reached before verification is an attack
     * surface rather than a convenience.
     */
    @Override
    public Optional<ProviderEvent> interpretWebhook(Map<String, String> headers, byte[] body) {
        String header = headers.get("webhook-signature");
        if (header == null || webhookSecret == null || webhookSecret.isBlank()) {
            return Optional.empty();
        }
        String time = null;
        String signature = null;
        for (String part : header.split(",")) {
            String[] pair = part.split("=", 2);
            if (pair.length == 2 && pair[0].trim().equals("time")) {
                time = pair[1].trim();
            } else if (pair.length == 2 && pair[0].trim().equals("sig1")) {
                signature = pair[1].trim();
            }
        }
        if (time == null || signature == null || !fresh(time)) {
            return Optional.empty();
        }
        String expected = hmac(time + "." + new String(body, StandardCharsets.UTF_8));
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                signature.getBytes(StandardCharsets.US_ASCII))) {
            return Optional.empty();
        }
        return parse(body);
    }

    @Override
    public void delete(String providerRef) {
        try {
            api.delete().uri("/stream/{uid}", providerRef).retrieve().toBodilessEntity();
        } catch (HttpClientErrorException.NotFound alreadyGone) {
            // Idempotent by contract: the caller wanted it gone, and it is.
        }
    }

    /** Five minutes each way: enough for clock skew, not enough to replay yesterday. */
    private static boolean fresh(String time) {
        try {
            return Math.abs(Instant.now().getEpochSecond() - Long.parseLong(time)) <= 300;
        } catch (NumberFormatException notATimestamp) {
            return false;
        }
    }

    private String hmac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException | java.security.InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 is not optional", e);
        }
    }

    /** The notification body, mapped onto our vocabulary exactly as {@code status} maps it. */
    private Optional<ProviderEvent> parse(byte[] body) {
        try {
            JsonNode event = JSON.readTree(body);
            String uid = event.path("uid").asText("");
            String vendorState = event.path("status").path("state").asText("");
            if (uid.isEmpty() || vendorState.isEmpty()) {
                return Optional.empty();
            }
            MediaAssetState state = switch (vendorState) {
                case "ready" -> MediaAssetState.READY;
                case "error" -> MediaAssetState.ERRORED;
                case "pendingupload" -> MediaAssetState.PENDING_UPLOAD;
                case "downloading", "queued", "inprogress" -> MediaAssetState.PROCESSING;
                default -> {
                    LOG.warn("Unknown Stream state {} in a webhook; treating as PROCESSING", vendorState);
                    yield MediaAssetState.PROCESSING;
                }
            };
            Double duration = event.path("duration").asDouble(0) > 0
                ? event.path("duration").asDouble() : null;
            // Cloudflare sends no event id, so it is derived from what the event says: a
            // redelivery of the same notification collides, a genuinely new state does not.
            return Optional.of(new ProviderEvent(uid + ":" + vendorState, uid, state, duration,
                event.path("status").path("errorReasonText").asText(null)));
        } catch (IOException malformed) {
            return Optional.empty();
        }
    }

    private static RSASSASigner signerFor(String base64Jwk) {
        try {
            RSAKey key = RSAKey.parse(
                new String(Base64.getDecoder().decode(base64Jwk), StandardCharsets.UTF_8));
            return new RSASSASigner(key);
        } catch (ParseException | JOSEException e) {
            // At construction, on purpose: a service that cannot sign must fail to start, not
            // fail at the first learner's play button.
            throw new IllegalStateException(
                "streaming.cloudflare-stream.signing-key-jwk is not a usable RSA key", e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record VideoEnvelope(boolean success, VideoResult result) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record VideoResult(String uid, VideoStatus status, Double duration) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record VideoStatus(String state, String errorReasonText) {}
}
