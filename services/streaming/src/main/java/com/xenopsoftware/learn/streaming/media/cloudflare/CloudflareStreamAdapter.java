package com.xenopsoftware.learn.streaming.media.cloudflare;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
import com.xenopsoftware.learn.streaming.media.UploadRequest;
import com.xenopsoftware.learn.streaming.media.UploadTarget;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
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

    private final RestClient api;
    private final String signingKeyId;
    private final RSASSASigner signer;

    public CloudflareStreamAdapter(RestClient.Builder restClientBuilder, CloudflareStreamProperties properties) {
        this.api = restClientBuilder
            .baseUrl("https://api.cloudflare.com/client/v4/accounts/" + properties.accountId())
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiToken())
            .build();
        this.signingKeyId = properties.signingKeyId();
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
            .build();
        SignedJWT jwt = new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKeyId).build(), claims);
        try {
            jwt.sign(signer);
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not sign a playback token", e);
        }
        return new PlaybackToken(jwt.serialize(), expiresAt);
    }

    @Override
    public void delete(String providerRef) {
        try {
            api.delete().uri("/stream/{uid}", providerRef).retrieve().toBodilessEntity();
        } catch (HttpClientErrorException.NotFound alreadyGone) {
            // Idempotent by contract: the caller wanted it gone, and it is.
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
