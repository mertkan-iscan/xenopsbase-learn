package com.xenopsoftware.learn.reporting.telemetry;

import com.xenopsoftware.learn.common.tenancy.TenantContext;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Validate, then append (T-3.6).
 *
 * <p>The whole method is an insert. There is no read-back, no lookup of the node, no call to
 * identity to resolve the caller, and no merge into anything — because every one of those would
 * make ingest depend on something that can be slow or down, and this is the path that must keep
 * working when the rest does not. {@code docs/reporting-inputs.md} states it as a rule: nothing is
 * fetched synchronously while a learner waits, and a sample this service cannot yet interpret is
 * kept and interpreted when the event that explains it lands.
 *
 * <p>Which is also why merging is not here. ADR-0107's union of watched intervals is a
 * read-modify-write against a row other requests want; an append is not. T-3.7 does that work
 * afterwards, where being late is survivable.
 *
 * <h2>Plain JDBC, one round trip</h2>
 *
 * A batched {@code INSERT} rather than JPA, for the reason the upload reaper is also plain JDBC:
 * there is no entity here whose identity or lifecycle anybody cares about, and a persistence
 * context that dirty-checks sixty rows on flush is doing work this path exists to avoid. The
 * tenant is passed explicitly because Hibernate's discriminator does not reach native SQL — the
 * lesson T-1.3 already paid for.
 */
@Service
public class HeartbeatIngestService {

    private final JdbcTemplate jdbc;
    private final IngestProperties properties;
    private final IngestMetrics metrics;
    private final Clock clock;

    public HeartbeatIngestService(DataSource dataSource, IngestProperties properties,
            IngestMetrics metrics, Clock clock) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * @param subject the IdP subject from the verified token — the only identifier available
     *        without the synchronous call this path is forbidden to make
     * @return how many samples were written
     */
    @Transactional
    public int record(HeartbeatBatch batch, String subject) {
        validate(batch);

        Instant receivedAt = clock.instant();
        String tenantId = TenantContext.require();
        List<PlaybackSample> samples = batch.samples();

        jdbc.batchUpdate("""
            INSERT INTO playback_heartbeat (id, tenant_id, subject, node_id, from_second,
                                            to_second, rate, observed_at, received_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement statement, int index) throws SQLException {
                    PlaybackSample sample = samples.get(index);
                    statement.setObject(1, UUID.randomUUID());
                    statement.setString(2, tenantId);
                    statement.setString(3, subject);
                    statement.setObject(4, batch.nodeId());
                    statement.setInt(5, sample.fromSecond());
                    statement.setInt(6, sample.toSecond());
                    statement.setDouble(7, sample.rate());
                    statement.setTimestamp(8, Timestamp.from(sample.observedAt()));
                    statement.setTimestamp(9, Timestamp.from(receivedAt));
                }

                @Override
                public int getBatchSize() {
                    return samples.size();
                }
            });

        // Lag measured from the OLDEST sample in the batch, not the newest. The newest is always
        // about ten seconds old by construction and would report a healthy number during exactly
        // the incident this metric exists to show: a client draining a backlog after an outage.
        Instant oldest = samples.stream().map(PlaybackSample::observedAt)
            .min(Instant::compareTo).orElse(receivedAt);
        metrics.accepted(samples.size(), Duration.between(oldest, receivedAt));
        return samples.size();
    }

    /**
     * Everything checked before a row is written, and every failure a named reason rather than a
     * 500 — a client that cannot tell "split this" from "stop sending this" retries both, which
     * turns one broken player into sustained load.
     */
    private void validate(HeartbeatBatch batch) {
        if (batch.nodeId() == null || batch.playbackToken() == null || batch.playbackToken().isBlank()) {
            throw reject(RejectionReason.MISSING_ATTRIBUTION, "a batch names its node and its token");
        }
        List<PlaybackSample> samples = batch.samples();
        if (samples == null || samples.isEmpty()) {
            throw reject(RejectionReason.EMPTY_BATCH, "no samples");
        }
        if (samples.size() > properties.maxSamplesPerBatch()) {
            throw reject(RejectionReason.BATCH_TOO_LARGE,
                samples.size() + " samples exceeds " + properties.maxSamplesPerBatch());
        }
        for (PlaybackSample sample : samples) {
            if (sample == null || sample.observedAt() == null) {
                throw reject(RejectionReason.MALFORMED_INTERVAL, "a sample with no interval or time");
            }
            if (sample.fromSecond() < 0 || sample.toSecond() <= sample.fromSecond()) {
                throw reject(RejectionReason.MALFORMED_INTERVAL,
                    "[" + sample.fromSecond() + ", " + sample.toSecond() + ")");
            }
            if (sample.toSecond() - sample.fromSecond() > properties.maxIntervalSeconds()) {
                throw reject(RejectionReason.MALFORMED_INTERVAL,
                    "one sample claims " + (sample.toSecond() - sample.fromSecond()) + "s");
            }
            if (sample.rate() < properties.minRate() || sample.rate() > properties.maxRate()) {
                throw reject(RejectionReason.IMPLAUSIBLE_RATE, "rate " + sample.rate());
            }
        }
    }

    private BatchRejectedException reject(RejectionReason reason, String detail) {
        // Counted where it is decided, so no rejection path can be added later that forgets to.
        metrics.rejected(reason);
        return new BatchRejectedException(reason, detail);
    }
}
