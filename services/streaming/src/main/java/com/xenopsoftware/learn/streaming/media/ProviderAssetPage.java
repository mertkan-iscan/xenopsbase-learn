package com.xenopsoftware.learn.streaming.media;

import java.util.List;

/**
 * One page of the refs a provider is holding, for the orphan sweep (T-3.8).
 *
 * <p>Refs and nothing else. The sweep's question is "is there a row for this", which needs an
 * identifier and no vendor metadata, and a page carrying titles and sizes would be a page whose
 * shape a second provider has to imitate for no reader's benefit.
 *
 * @param refs   the provider refs on this page, opaque as everywhere else
 * @param cursor what to pass back for the next page, or null when this was the last one. Opaque to
 *               the caller: each adapter encodes whatever its vendor paginates by, and a sweep that
 *               understood it would be a sweep that had learned the vendor's API
 */
public record ProviderAssetPage(List<String> refs, String cursor) {

    public static ProviderAssetPage last(List<String> refs) {
        return new ProviderAssetPage(refs, null);
    }
}
