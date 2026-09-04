package com.xenopsoftware.learn.streaming.web.rest;

import com.xenopsoftware.learn.streaming.media.MediaProvider;
import com.xenopsoftware.learn.streaming.media.ProviderEvent;
import com.xenopsoftware.learn.streaming.video.EncodeStateService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where the provider tells us an encode finished (T-3.3).
 *
 * <p><b>Outside the /api prefix on purpose.</b> Everything under it is authenticated by a
 * tenant token, and a provider has none: its credential is the signature on the body. Being
 * outside also keeps it clear of the tenant status gate (T-1.4) -- a suspended customer encode
 * still finishes, and refusing the news would leave the asset stuck forever after they are
 * reinstated.
 *
 * <p>The controller does not parse the body. It hands the bytes and headers to the adapter,
 * which verifies before parsing and knows the vendor shape -- the same boundary that keeps the
 * delivery decision reversible (ADR-0101).
 */
@RestController
@RequestMapping("/webhooks/media")
public class MediaWebhookResource {

    private static final Logger LOG = LoggerFactory.getLogger(MediaWebhookResource.class);

    private final MediaProvider mediaProvider;
    private final EncodeStateService encodeState;

    public MediaWebhookResource(MediaProvider mediaProvider, EncodeStateService encodeState) {
        this.mediaProvider = mediaProvider;
        this.encodeState = encodeState;
    }

    @PostMapping
    public ResponseEntity<Void> receive(HttpServletRequest request, @RequestBody byte[] body) {
        Optional<ProviderEvent> event = mediaProvider.interpretWebhook(headersOf(request), body);
        if (event.isEmpty()) {
            // Unsigned, badly signed, stale, or a shape we do not know -- answered identically,
            // because telling an unsigned caller which it was is free reconnaissance.
            LOG.warn("Rejected a media webhook: unverifiable or uninterpretable");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        EncodeStateService.Outcome outcome = encodeState.apply(mediaProvider.providerId(), event.get());
        // 200 for every accepted delivery, duplicates included: a provider that retries until
        // it gets a 2xx has to be told to stop, and "I already had this" is a success.
        LOG.debug("Webhook for {} was {}", event.get().providerRef(), outcome);
        return ResponseEntity.ok().build();
    }

    private static Map<String, String> headersOf(HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            headers.put(name.toLowerCase(Locale.ROOT), request.getHeader(name));
        }
        return headers;
    }
}
