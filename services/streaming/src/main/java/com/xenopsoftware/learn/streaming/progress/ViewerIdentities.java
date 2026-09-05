package com.xenopsoftware.learn.streaming.progress;

import com.xenopsoftware.learn.streaming.playback.Viewer;
import com.xenopsoftware.learn.streaming.playback.ViewerDirectory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * The caller's {@code app_user.id}, remembered for a while (T-3.7).
 *
 * <h2>Why a cache exists at all</h2>
 *
 * {@link ViewerDirectory} answers by calling identity, and it was written for a path that asks
 * roughly never: T-3.4 resolves the person only while auditing a refusal. Progress asks on every
 * heartbeat — one per learner per ten seconds — which would make it the most-called cross-service
 * call in the product, to re-learn an answer that changes about once in a person's employment.
 *
 * <h2>Why in the process and not in Valkey</h2>
 *
 * A shared cache would save each replica its own first miss and cost every hit a network round
 * trip, to store a UUID per signed-in person. With a handful of replicas that trade is the wrong
 * way round. It also keeps this off the failure path Valkey already owns here (T-1.4's status
 * lookup): a cache whose miss is one HTTP call needs no availability story of its own.
 *
 * <h2>The staleness this accepts, stated</h2>
 *
 * The mapping changes when identity relinks a person (T-1.7's repair script), and for as long as
 * the entry lives this service would credit coverage to the id it learned first. Fifteen minutes
 * bounds that, and the repair's own runbook already assumes people sign in afterwards. What is
 * <em>not</em> cached is anything about permission or account status — those are decided per mint
 * (T-3.4) and at the edge (T-1.4), and a cache here would quietly extend both.
 */
@Component
public class ViewerIdentities {

    /** Long enough to make the call rare, short enough that a relink lands the same afternoon. */
    private static final Duration TTL = Duration.ofMinutes(15);

    /**
     * Bounded, because an unbounded map keyed by whatever a caller presents is a memory leak
     * wearing a cache's clothes. At the bound the map is cleared rather than evicted cleverly:
     * this is a small map of cheap-to-refill entries, and an eviction policy would be more code
     * than the thing it protects.
     */
    private static final int MAX_ENTRIES = 20_000;

    private record Known(UUID id, Instant expiresAt) {}

    private final Map<String, Known> byViewer = new ConcurrentHashMap<>();
    private final ViewerDirectory directory;
    private final Clock clock;

    public ViewerIdentities(ViewerDirectory directory, Clock clock) {
        this.directory = directory;
        this.clock = clock;
    }

    /** The current caller's durable id, asking identity only when this does not already know. */
    public UUID require(Viewer viewer) {
        String key = viewer.tenantId() + " " + viewer.subject();
        Known known = byViewer.get(key);
        if (known != null && known.expiresAt().isAfter(clock.instant())) {
            return known.id();
        }
        UUID resolved = directory.currentAppUserId()
            .orElseThrow(() -> new LearnerUnresolvedException(
                "identity could not resolve the caller, so there is no durable id to credit"));
        if (byViewer.size() >= MAX_ENTRIES) {
            byViewer.clear();
        }
        byViewer.put(key, new Known(resolved, clock.instant().plus(TTL)));
        return resolved;
    }

    /** Forget everything. Exists for tests that change who the directory answers with. */
    public void forget() {
        byViewer.clear();
    }

    /** What is known without asking. Used by nothing but tests today. */
    Optional<UUID> peek(Viewer viewer) {
        Known known = byViewer.get(viewer.tenantId() + " " + viewer.subject());
        return Optional.ofNullable(known).map(Known::id);
    }
}
