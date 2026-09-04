package com.xenopsoftware.learn.streaming.media;

/**
 * A provider telling us an asset moved (T-3.3), in our vocabulary rather than theirs.
 *
 * <p>Produced by {@link MediaProvider#interpretWebhook} — which is where the vendor's payload
 * shape and its signature scheme stay. A controller that parsed Cloudflare's JSON would be a
 * controller that has to change when the delivery decision does (ADR-0101), and the port exists
 * precisely so that does not happen.
 *
 * @param eventId the provider's own id for this delivery, which is what makes the handler
 *        idempotent; a provider that does not supply one gets a deterministic id derived from
 *        the payload, so a redelivery still collides
 */
public record ProviderEvent(String eventId, String providerRef, MediaAssetState state,
                            Double durationSeconds, String error) {}
