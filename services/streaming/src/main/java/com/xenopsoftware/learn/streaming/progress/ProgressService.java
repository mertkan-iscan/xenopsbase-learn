package com.xenopsoftware.learn.streaming.progress;

import com.xenopsoftware.learn.common.messaging.Outbox;
import com.xenopsoftware.learn.common.tenancy.TenantContext;
import com.xenopsoftware.learn.streaming.playback.ContentEntitlement;
import com.xenopsoftware.learn.streaming.playback.NodeEntitlement;
import com.xenopsoftware.learn.streaming.playback.PlaybackAudit;
import com.xenopsoftware.learn.streaming.playback.PlaybackRefusedException;
import com.xenopsoftware.learn.streaming.playback.RefusalReason;
import com.xenopsoftware.learn.streaming.playback.Viewer;
import com.xenopsoftware.learn.streaming.video.VideoAsset;
import com.xenopsoftware.learn.streaming.video.VideoAssetRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * WHERE COMPLETION IS DECIDED (T-3.7, ADR-0107). Everything this platform will ever say about
 * whether a person watched their training is derived in {@link #record}, from intervals, on the
 * server.
 *
 * <h2>The order, and why it is this one</h2>
 *
 * <ol>
 *   <li><b>Shape.</b> A batch whose samples are malformed or too many is refused before anything
 *       is read, because the work per request has to be bounded before the request can cost
 *       anything.</li>
 *   <li><b>Who.</b> The durable id, cached (ADR-0104 forbids keying this by an IdP subject).</li>
 *   <li><b>Entitlement</b>, when there is no row yet or the copied policy has aged out. Not per
 *       heartbeat: at one post per learner per ten seconds a catalog hop here would be the
 *       most-called cross-service call in the product.</li>
 *   <li><b>Seek rule</b>, before the merge, so a claim an honest player would not have made is
 *       refused rather than merged and then regretted.</li>
 *   <li><b>Merge</b>, in memory, over a capped set.</li>
 *   <li><b>Rate sanity</b>, against what the merge actually credited — not against what the batch
 *       claimed. A duplicate claims plenty and credits nothing, and this is the ordering that makes
 *       re-delivery free instead of suspicious.</li>
 *   <li><b>Derive</b>, persist, and announce a completion that has just happened.</li>
 * </ol>
 *
 * <h2>What this deliberately does not defend against</h2>
 *
 * ADR-0107 is explicit and this class inherits it: the bar is a bored learner with developer
 * tools, not a paid attacker. Dragging the scrubber, posting a completion flag and finishing an
 * hour of video in thirty seconds are all stopped here. A script posting honest-looking intervals
 * at real-time pace is not, and neither is leaving the tab playing — <b>the cheapest remaining
 * attack costs as much wall-clock time as watching would</b>, which is the bar the ADR chose on
 * purpose rather than the bar it failed to reach.
 */
@Service
public class ProgressService {

    private static final Logger LOG = LoggerFactory.getLogger(ProgressService.class);

    /** The one value this module writes. The others are other modules' to write (ADR-0107). */
    private static final String DERIVED = "DERIVED";

    /** What a completion is announced as. Catalog folds it into node state (ADR-0109, T-5.3). */
    static final String COMPLETED_SUBJECT = "streaming.node.completed";

    private final JdbcTemplate jdbc;
    private final ContentEntitlement entitlement;
    private final VideoAssetRepository videoAssets;
    private final ViewerIdentities identities;
    private final ProgressRefusals refusals;
    private final ProgressMetrics metrics;
    private final PlaybackAudit audit;
    private final ProgressProperties properties;
    private final Outbox outbox;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();

    public ProgressService(DataSource dataSource, ContentEntitlement entitlement,
            VideoAssetRepository videoAssets, ViewerIdentities identities,
            ProgressRefusals refusals, ProgressMetrics metrics, PlaybackAudit audit,
            ProgressProperties properties, ObjectProvider<Outbox> outbox, Clock clock) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.entitlement = entitlement;
        this.videoAssets = videoAssets;
        this.identities = identities;
        this.refusals = refusals;
        this.metrics = metrics;
        this.audit = audit;
        this.properties = properties;
        this.outbox = outbox.getIfAvailable();
        this.clock = clock;
        if (this.outbox == null) {
            LOG.warn("No outbox is configured, so a derived completion is recorded here and "
                + "announced to nobody: catalog will not open the gate behind it (T-5.3) and "
                + "reporting will not see it. Set platform.outbox.enabled=true.");
        }
    }

    /**
     * Merge what a player says it showed, and derive what follows from it.
     *
     * @return the learner's progress as it now stands — the same view {@link #current} returns, so
     *         a player that posts and a player that reloads render from one shape
     */
    @Transactional
    public LearnerProgress record(UUID nodeId, ProgressBatch batch) {
        Viewer viewer = currentViewer();
        validate(batch);
        UUID learnerId = identities.require(viewer);

        Row row = lockRow(viewer.tenantId(), learnerId, nodeId);
        NodeEntitlement node = null;
        if (row == null || policyIsStale(row)) {
            node = entitlementOf(nodeId, viewer, learnerId);
        }
        if (row == null) {
            row = createRow(viewer.tenantId(), learnerId, nodeId, node);
        } else if (node != null) {
            row = refreshPolicy(row, node);
        }

        List<Coverage.Fragment> claims = claimsOf(batch);
        if (!row.allowSeekForward()) {
            refuseSkippingAhead(viewer.tenantId(), learnerId, row, claims);
        }

        Coverage.Merge merge = row.coverage().merge(claims, properties.coalesceGapSeconds(),
            properties.maxFragments());
        checkAgainstWallClock(viewer.tenantId(), learnerId, row, merge);

        Integer extent = extentOf(row.videoAssetId());
        int coveredSeconds = merge.coverage().seconds();
        boolean crossedNow = row.completedAt() == null
            && extent != null
            && coveredSeconds >= requiredSeconds(extent, row.thresholdPercent());
        Instant now = clock.instant();

        jdbc.update("""
            UPDATE learner_node_progress
               SET covered = ?::int4multirange,
                   covered_seconds = ?,
                   fragments = ?,
                   approximate = approximate OR ?,
                   furthest_second = GREATEST(furthest_second, ?),
                   extent_seconds = ?,
                   completed_at = COALESCE(completed_at, ?),
                   session_hash = ?,
                   session_started_at = CASE WHEN session_hash IS DISTINCT FROM ?
                                             THEN ? ELSE session_started_at END,
                   updated_at = ?
             WHERE id = ?
            """,
            merge.coverage().toMultirange(), coveredSeconds, merge.coverage().fragmentCount(),
            merge.approximated(), merge.coverage().furthestSecond(), extent,
            crossedNow ? java.sql.Timestamp.from(now) : null,
            sessionHash(batch), sessionHash(batch), java.sql.Timestamp.from(now),
            java.sql.Timestamp.from(now), row.id());

        metrics.merged(merge.newlySeconds(), merge.coverage().fragmentCount());
        if (crossedNow) {
            announceCompletion(viewer.tenantId(), learnerId, row, coveredSeconds, extent, now);
            metrics.completed();
        }

        Row updated = row.after(merge, coveredSeconds, extent, crossedNow ? now : row.completedAt());
        return view(nodeId, updated);
    }

    /**
     * What the player needs before it plays anything: where to resume, and which rules it is
     * expected to enforce.
     *
     * <p>Read-only, and it creates nothing. A learner who has never opened this node has no row,
     * and inventing one here would put a write on a page load — which is also how a row would
     * appear for every node a curious person opened once.
     */
    @Transactional(readOnly = true)
    public LearnerProgress current(UUID nodeId) {
        Viewer viewer = currentViewer();
        UUID learnerId = identities.require(viewer);
        Row row = readRow(viewer.tenantId(), learnerId, nodeId);
        if (row != null) {
            return view(nodeId, row.withExtent(extentOf(row.videoAssetId())));
        }
        // No coverage yet, so the answer is zeros — but the policy still has to be real, because
        // the player enforces it and a default that disagreed with the item would be a rule
        // applied on one side only.
        NodeEntitlement node = entitlementOf(nodeId, viewer, learnerId);
        int threshold = node.thresholdPercent() == null
            ? properties.defaultThresholdPercent() : node.thresholdPercent();
        Integer extent = extentOf(node.videoAssetId());
        return new LearnerProgress(nodeId, 0, extent, 0, threshold, false, null, DERIVED, 0,
            node.allowSeekForward(), node.allowSeekForward() ? null : 0, 0, false);
    }

    // ------------------------------------------------------------------ the checks

    private void validate(ProgressBatch batch) {
        if (batch == null || batch.playbackToken() == null || batch.playbackToken().isBlank()) {
            throw reject(ProgressRejection.MISSING_ATTRIBUTION,
                "a batch names the playback session it came from");
        }
        List<ProgressBatch.Sample> samples = batch.samples();
        if (samples == null || samples.isEmpty()) {
            throw reject(ProgressRejection.EMPTY_BATCH, "no samples");
        }
        if (samples.size() > properties.maxSamplesPerBatch()) {
            throw reject(ProgressRejection.BATCH_TOO_LARGE,
                samples.size() + " samples exceeds " + properties.maxSamplesPerBatch());
        }
        for (ProgressBatch.Sample sample : samples) {
            if (sample == null || sample.observedAt() == null) {
                throw reject(ProgressRejection.MALFORMED_INTERVAL, "a sample with no interval or time");
            }
            if (sample.fromSecond() < 0 || sample.toSecond() <= sample.fromSecond()) {
                throw reject(ProgressRejection.MALFORMED_INTERVAL,
                    "[" + sample.fromSecond() + ", " + sample.toSecond() + ")");
            }
            if (sample.toSecond() - sample.fromSecond() > properties.maxIntervalSeconds()) {
                throw reject(ProgressRejection.MALFORMED_INTERVAL,
                    "one sample claims " + (sample.toSecond() - sample.fromSecond()) + "s");
            }
            if (sample.rate() < properties.minRate() || sample.rate() > properties.maxRate()) {
                throw reject(ProgressRejection.IMPLAUSIBLE_RATE, "rate " + sample.rate());
            }
        }
    }

    /**
     * THE WALL-CLOCK CHECK (ADR-0107's second criterion).
     *
     * <p>Coverage credited over this row's life may not exceed the time that has passed since its
     * first heartbeat, at the fastest rate the player offers, plus one buffer's grace. It compares
     * against the <b>credited</b> total rather than the claimed one, which is what makes a repeat
     * of yesterday's batch free rather than an accusation.
     *
     * <p>Anchored to the row rather than to the playback session, deliberately. A learner returning
     * from ten minutes offline posts ten minutes of buffered samples under a token that expired
     * while they were away; a per-session bound would reject exactly that honest case, and the
     * attack it would catch — a script pacing itself under a fresh token — is not stopped by a
     * tighter window anyway.
     */
    private void checkAgainstWallClock(String tenantId, UUID learnerId, Row row,
            Coverage.Merge merge) {
        long elapsed = Math.max(0, Duration.between(row.firstSeenAt(), clock.instant()).toSeconds());
        long allowed = (long) (elapsed * properties.maxRate()) + properties.rateGrace().toSeconds();
        int credited = merge.coverage().seconds();
        if (credited > allowed) {
            String detail = credited + "s of content credited " + elapsed + "s after this learner "
                + "started, which wall clock does not allow at " + properties.maxRate() + "x";
            refusals.record(tenantId, learnerId, row.nodeId(), ProgressRejection.IMPLAUSIBLE_RATE,
                detail);
            throw reject(ProgressRejection.IMPLAUSIBLE_RATE, detail);
        }
    }

    /**
     * The server half of "seek-forward can be disallowed per item".
     *
     * <p>The player is told the same rule and enforces it — the rule crosses the iframe boundary
     * as a field rather than as trust (ADR-0110). This is what makes the rule real: a client that
     * ignores it, or a script that never ran it, is refused the coverage it claims past the end of
     * what it has actually been shown.
     */
    private void refuseSkippingAhead(String tenantId, UUID learnerId, Row row,
            List<Coverage.Fragment> claims) {
        int ceiling = row.coverage().contiguousEnd() + properties.seekToleranceSeconds();
        for (Coverage.Fragment claim : claims) {
            if (claim.from() > ceiling) {
                String detail = "a claim starting at " + claim.from() + "s on an item watched to "
                    + ceiling + "s";
                refusals.record(tenantId, learnerId, row.nodeId(),
                    ProgressRejection.SEEK_NOT_ALLOWED, detail);
                throw reject(ProgressRejection.SEEK_NOT_ALLOWED, detail);
            }
        }
    }

    // ------------------------------------------------------------------ the row

    private NodeEntitlement entitlementOf(UUID nodeId, Viewer viewer, UUID learnerId) {
        NodeEntitlement node = entitlement.lookUp(nodeId, viewer)
            .orElseThrow(() -> refused(viewer, learnerId, nodeId, RefusalReason.UNKNOWN_NODE, null));
        if (!node.assigned()) {
            throw refused(viewer, learnerId, nodeId, RefusalReason.NOT_ASSIGNED, null);
        }
        if (!node.reachable()) {
            throw refused(viewer, learnerId, nodeId, RefusalReason.GATED, node.gateReason());
        }
        if (node.videoAssetId() == null) {
            throw refused(viewer, learnerId, nodeId, RefusalReason.NOT_PLAYABLE,
                "the node points at no video");
        }
        return node;
    }

    private Row createRow(String tenantId, UUID learnerId, UUID nodeId, NodeEntitlement node) {
        // The asset is read through the repository so the tenant discriminator applies (T-1.1):
        // a catalog answer naming another company's asset resolves to nothing here.
        VideoAsset asset = videoAssets.findById(node.videoAssetId())
            .orElseThrow(() -> new PlaybackRefusedException(RefusalReason.NOT_PLAYABLE,
                "no such video asset in this tenant: " + node.videoAssetId()));
        Instant now = clock.instant();
        int threshold = node.thresholdPercent() == null
            ? properties.defaultThresholdPercent() : node.thresholdPercent();
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO learner_node_progress (id, tenant_id, learner_id, node_id, video_asset_id,
                    covered, covered_seconds, fragments, approximate, furthest_second,
                    threshold_percent, allow_seek_forward, policy_seen_at, completion_source,
                    first_seen_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, '{}'::int4multirange, 0, 0, false, 0, ?, ?, ?, ?, ?, ?, ?)
            """, id, tenantId, learnerId, nodeId, asset.getId(), threshold, node.allowSeekForward(),
            java.sql.Timestamp.from(now), DERIVED, java.sql.Timestamp.from(now),
            java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));
        return new Row(id, nodeId, Coverage.empty(), 0, false, 0, threshold,
            node.allowSeekForward(), now, null, null, now, asset.getId());
    }

    private Row refreshPolicy(Row row, NodeEntitlement node) {
        int threshold = node.thresholdPercent() == null
            ? properties.defaultThresholdPercent() : node.thresholdPercent();
        Instant now = clock.instant();
        jdbc.update("""
            UPDATE learner_node_progress
               SET threshold_percent = ?, allow_seek_forward = ?, policy_seen_at = ?
             WHERE id = ?
            """, threshold, node.allowSeekForward(), java.sql.Timestamp.from(now), row.id());
        return row.withPolicy(threshold, node.allowSeekForward(), now);
    }

    private boolean policyIsStale(Row row) {
        return row.policySeenAt().plus(properties.policyRefresh()).isBefore(clock.instant());
    }

    private Row lockRow(String tenantId, UUID learnerId, UUID nodeId) {
        // FOR UPDATE, because the merge is a read-modify-write and a learner with two tabs open is
        // ordinary. Two heartbeats interleaving without the lock would let one overwrite the
        // other's coverage with a set that never saw it -- the one failure mode of this design
        // that would silently lose watched time.
        return jdbc.query(SELECT_ROW + " FOR UPDATE", ProgressService::mapRow, tenantId,
            learnerId, nodeId).stream().findFirst().orElse(null);
    }

    private Row readRow(String tenantId, UUID learnerId, UUID nodeId) {
        return jdbc.query(SELECT_ROW, ProgressService::mapRow, tenantId, learnerId, nodeId)
            .stream().findFirst().orElse(null);
    }

    private static final String SELECT_ROW = """
        SELECT id, node_id, covered::text AS covered, covered_seconds, approximate, furthest_second,
               threshold_percent, allow_seek_forward, policy_seen_at, extent_seconds,
               completed_at, first_seen_at, video_asset_id
          FROM learner_node_progress
         WHERE tenant_id = ? AND learner_id = ? AND node_id = ?
        """;

    private static Row mapRow(ResultSet rows, int index) throws SQLException {
        return new Row(rows.getObject("id", UUID.class),
            rows.getObject("node_id", UUID.class),
            Coverage.parse(rows.getString("covered")),
            rows.getInt("covered_seconds"),
            rows.getBoolean("approximate"),
            rows.getInt("furthest_second"),
            rows.getInt("threshold_percent"),
            rows.getBoolean("allow_seek_forward"),
            rows.getTimestamp("policy_seen_at").toInstant(),
            (Integer) rows.getObject("extent_seconds"),
            rows.getTimestamp("completed_at") == null
                ? null : rows.getTimestamp("completed_at").toInstant(),
            rows.getTimestamp("first_seen_at").toInstant(),
            rows.getObject("video_asset_id", UUID.class));
    }

    /**
     * The extent, from the provider and never from the client (ADR-0107).
     *
     * <p>Whole seconds, rounded DOWN. A 600.4-second video whose threshold is 90% needs 540 rather
     * than 541 seconds, and the rounding that decides that goes in the learner's favour — the
     * fractional second at the end of an encode is not something anybody watched or did not watch.
     */
    private Integer extentOf(UUID videoAssetId) {
        return videoAssets.findById(videoAssetId)
            .map(VideoAsset::getDurationSeconds)
            .map(seconds -> (int) Math.floor(seconds))
            .orElse(null);
    }

    static int requiredSeconds(int extentSeconds, int thresholdPercent) {
        return (int) Math.ceil(extentSeconds * (thresholdPercent / 100.0));
    }

    // ------------------------------------------------------------------ the announcement

    private void announceCompletion(String tenantId, UUID learnerId, Row row, int coveredSeconds,
            Integer extent, Instant completedAt) {
        if (outbox == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tenantId", tenantId);
        payload.put("learnerId", learnerId.toString());
        payload.put("nodeId", row.nodeId().toString());
        payload.put("videoAssetId", row.videoAssetId().toString());
        payload.put("coveredSeconds", coveredSeconds);
        payload.put("extentSeconds", extent);
        payload.put("thresholdPercent", row.thresholdPercent());
        payload.put("approximate", row.approximate());
        // The source travels with the event because catalog and reporting both have to keep it:
        // a completion this platform measured and one a package asserted are different claims and
        // every report says which (ADR-0107).
        payload.put("source", DERIVED);
        payload.put("completedAt", completedAt.toString());
        // In the same transaction as the row that completed. An event for a completion that rolled
        // back would open a gate for training nobody finished; a completion with no event would
        // leave the gate shut forever (T-9.8).
        outbox.publish(tenantId, COMPLETED_SUBJECT, "NodeCompleted",
            json.writeValueAsString(payload));
    }

    // ------------------------------------------------------------------ plumbing

    private List<Coverage.Fragment> claimsOf(ProgressBatch batch) {
        List<Coverage.Fragment> claims = new ArrayList<>(batch.samples().size());
        for (ProgressBatch.Sample sample : batch.samples()) {
            claims.add(new Coverage.Fragment(sample.fromSecond(), sample.toSecond()));
        }
        return claims;
    }

    private LearnerProgress view(UUID nodeId, Row row) {
        Integer extent = row.extentSeconds();
        int percent = extent == null || extent == 0 ? 0
            : Math.min(100, (int) Math.floor(row.coveredSeconds() * 100.0 / extent));
        return new LearnerProgress(nodeId, row.coveredSeconds(), extent, percent,
            row.thresholdPercent(), row.completedAt() != null, row.completedAt(), DERIVED,
            row.furthestSecond(), row.allowSeekForward(),
            row.allowSeekForward() ? null : row.coverage().contiguousEnd(),
            row.coverage().fragmentCount(), row.approximate());
    }

    /**
     * The session, as a hash. The token itself is a bearer credential for the edge and is not
     * stored here — knowing which session a batch belonged to needs an identifier, not the
     * credential.
     */
    private String sessionHash(ProgressBatch batch) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                digest.digest(batch.playbackToken().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required of every JVM", impossible);
        }
    }

    private ProgressRejectedException reject(ProgressRejection reason, String detail) {
        // Counted where it is decided, so a rejection path added later cannot forget to count.
        metrics.rejected(reason);
        return new ProgressRejectedException(reason, detail);
    }

    private PlaybackRefusedException refused(Viewer viewer, UUID learnerId, UUID nodeId,
            RefusalReason reason, String detail) {
        // The same audit table as a refused token, because it is the same question with the same
        // answer: "it says I cannot watch this" and "it says I did not watch this" are asked by
        // the same person about the same node.
        audit.recordRefusal(viewer.tenantId(), learnerId, nodeId, reason, detail);
        return new PlaybackRefusedException(reason, detail);
    }

    private static Viewer currentViewer() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            // Everything under /api is authenticated by the security chain, so reaching here
            // without a JWT is a wiring mistake rather than a caller error.
            throw new IllegalStateException("Progress is only ever about a verified caller");
        }
        return new Viewer(TenantContext.require(), token.getToken().getSubject());
    }

    /** The stored row, as this service needs it. */
    private record Row(UUID id, UUID nodeId, Coverage coverage, int coveredSeconds,
                       boolean approximate, int furthestSecond, int thresholdPercent,
                       boolean allowSeekForward, Instant policySeenAt, Integer extentSeconds,
                       Instant completedAt, Instant firstSeenAt, UUID videoAssetId) {

        Row withPolicy(int threshold, boolean allowSeekForward, Instant seenAt) {
            return new Row(id, nodeId, coverage, coveredSeconds, approximate, furthestSecond,
                threshold, allowSeekForward, seenAt, extentSeconds, completedAt, firstSeenAt,
                videoAssetId);
        }

        Row withExtent(Integer extent) {
            return new Row(id, nodeId, coverage, coveredSeconds, approximate, furthestSecond,
                thresholdPercent, allowSeekForward, policySeenAt, extent, completedAt, firstSeenAt,
                videoAssetId);
        }

        Row after(Coverage.Merge merge, int coveredSeconds, Integer extent, Instant completedAt) {
            return new Row(id, nodeId, merge.coverage(), coveredSeconds,
                approximate || merge.approximated(),
                Math.max(furthestSecond, merge.coverage().furthestSecond()), thresholdPercent,
                allowSeekForward, policySeenAt, extent, completedAt, firstSeenAt, videoAssetId);
        }
    }
}
