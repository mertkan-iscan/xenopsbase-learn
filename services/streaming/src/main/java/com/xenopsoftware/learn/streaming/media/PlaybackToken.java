package com.xenopsoftware.learn.streaming.media;

import java.time.Instant;

/**
 * The signed proof the edge honors without calling us. Opaque to every caller; only the edge
 * verifies it, which is what lets playback survive this service being down (T-3.10).
 */
public record PlaybackToken(String token, Instant expiresAt) {}
