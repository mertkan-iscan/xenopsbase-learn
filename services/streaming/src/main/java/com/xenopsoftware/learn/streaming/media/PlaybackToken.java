package com.xenopsoftware.learn.streaming.media;

import java.net.URI;
import java.time.Instant;

/**
 * The signed proof the edge honors without calling us, and the place to present it.
 *
 * <p>Opaque to every caller; only the edge verifies it, which is what lets playback survive this
 * service being down (T-3.10).
 *
 * <p>{@code manifestUrl} is here rather than composed by the player for the reason the whole
 * port exists: the URL shape is vendor knowledge — a customer subdomain, a {@code /manifest/}
 * path, whether the token rides in the query or a cookie — and a player that built it would know
 * the vendor's name, which is precisely what ADR-0101 keeps swappable. The player receives a URL
 * and plays it; the day the adapter changes, nothing in the browser does (T-3.5).
 *
 * @param manifestUrl the HLS manifest to play, with this token already applied
 */
public record PlaybackToken(String token, URI manifestUrl, Instant expiresAt) {}
