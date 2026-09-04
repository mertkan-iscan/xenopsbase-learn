package com.xenopsoftware.learn.streaming.media.cloudflare;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything the Stream adapter needs, and the only place vendor configuration lives — inside
 * the adapter package, so even the configuration cannot leak the vendor into domain code. The
 * values arrive with T-9.14 (the product's own accounts); until then the stack runs the fake
 * provider and none of this is read.
 *
 * @param accountId     the Cloudflare account the assets live under
 * @param apiToken      API token scoped to Stream; a secret, injected, never defaulted
 * @param customerSubdomain the account's delivery subdomain, as in
 *        {@code customer-<subdomain>.cloudflarestream.com} — the host every manifest URL is
 *        served from. Account-scoped and returned by the API; it arrives with T-9.14 like the
 *        rest of this record
 * @param signingKeyId  id of the Stream signing key pair, minted once via the API
 * @param webhookSecret the notification secret, used to verify inbound webhooks (T-3.3). A
 *        blank one means no webhook is trusted at all, which is the right default: an
 *        unverifiable notification is not a notification.
 * @param signingKeyJwk the private key as Cloudflare hands it back: a base64-encoded JWK.
 *                      Local signing is the point — minting a playback token must cost zero
 *                      network calls (ADR-0101: the backend only signs)
 */
@ConfigurationProperties(prefix = "streaming.cloudflare-stream")
public record CloudflareStreamProperties(
    String accountId,
    String apiToken,
    String customerSubdomain,
    String signingKeyId,
    String signingKeyJwk,
    String webhookSecret) {}
