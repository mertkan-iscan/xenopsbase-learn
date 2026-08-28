package com.xenopsoftware.learn.streaming.media;

import java.net.URI;
import java.time.Instant;

/**
 * Where a client sends the bytes, and the opaque ref this asset will be known by from now on.
 *
 * @param providerRef opaque; stored beside the provider discriminator and shown to no one
 * @param uploadUrl   handed to the uploading client; our request threads never see the bytes
 * @param expiresAt   when the target stops accepting an upload
 */
public record UploadTarget(String providerRef, URI uploadUrl, Instant expiresAt) {}
