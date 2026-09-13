package com.xenopsoftware.learn.packaging.runtime;

import com.xenopsoftware.learn.common.tenancy.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * Where one learner got to inside one package (T-4.4).
 *
 * <p>The row a SCORM launch reads at the start and writes at the end. Everything a package stored
 * comes back verbatim in {@link #data}; the three facts the platform acts on are lifted out beside
 * it by {@link Cmi} and are the only parts anything queries.
 */
@Entity
@Table(name = "package_runtime")
public class PackageRuntime extends TenantOwned {

    @Id
    @UuidGenerator
    @Column(name = "id")
    private UUID id;

    @Column(name = "package_id", nullable = false, updatable = false)
    private UUID packageId;

    @Column(name = "learner_id", nullable = false, updatable = false)
    private UUID learnerId;

    @Column(name = "node_id", updatable = false)
    private UUID nodeId;

    /**
     * The CMI data model, as the package left it.
     *
     * <p>{@code jsonb} rather than a text column, and mapped as a {@code Map<String, String>} —
     * every CMI value is a string by the standard's own definition, and a package that stores
     * {@code 0.85} and reads back {@code 0.85000000001} because something helpfully made it a
     * number is a package that fails its own comparison.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "data", nullable = false, columnDefinition = "jsonb")
    private Map<String, String> data = Map.of();

    @Column(name = "completed", nullable = false)
    private boolean completed;

    /** Null when the package says nothing about passing, which is not the same as failing. */
    @Column(name = "passed")
    private Boolean passed;

    @Column(name = "score_raw")
    private BigDecimal scoreRaw;

    @Column(name = "launches", nullable = false)
    private int launches;

    @Column(name = "seconds_spent", nullable = false)
    private int secondsSpent;

    /** What the package says the CURRENT launch has lasted. Reset by the next launch. */
    @Column(name = "session_seconds", nullable = false)
    private int sessionSeconds;

    /** Every session added together — the number {@code cmi.total_time} is formatted from. */
    @Column(name = "total_seconds", nullable = false)
    private int totalSeconds;

    /**
     * What {@link #totalSeconds} was when this launch began.
     *
     * <p>{@code session_time} is cumulative within a launch, so accumulation is
     * {@code base + session} rather than {@code total + session}. Adding each report to a running
     * total counts every minute once per commit — see V5.
     */
    @Column(name = "session_base_seconds", nullable = false)
    private int sessionBaseSeconds;

    /**
     * The launch that owns this registration, or null for a row written before V5.
     *
     * <p>The most recent launch wins and every other one is refused, because two tabs hold two
     * whole data models and there is no merge of them that means anything (V5).
     */
    @Column(name = "active_session")
    private UUID activeSession;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected PackageRuntime() {}

    public PackageRuntime(UUID packageId, UUID learnerId, UUID nodeId, Instant now) {
        this.packageId = packageId;
        this.learnerId = learnerId;
        this.nodeId = nodeId;
        this.startedAt = now;
        this.updatedAt = now;
    }

    /**
     * They have opened it, and this launch now owns the registration.
     *
     * <p>What {@code cmi.core.entry} answers depends on the count; what a save from the tab they
     * left open ten minutes ago is allowed to do depends on the session id (V5).
     *
     * <p>The session's time starts at whatever the total already was, so the accumulation the
     * standard describes — {@code total_time} grows by each session's {@code session_time} — is
     * arithmetic rather than a running sum that double-counts every commit.
     */
    public void launched(UUID session, Instant now) {
        this.launches++;
        this.activeSession = session;
        this.sessionBaseSeconds = this.totalSeconds;
        this.sessionSeconds = 0;
        this.updatedAt = now;
    }

    /**
     * Whether a save quoting this session is the one allowed to write.
     *
     * <p><b>An unknown session is allowed when the row has never seen one.</b> Every row written
     * before V5 has a null {@code active_session}, and refusing those would make the deploy of
     * this rule the moment a learner mid-course lost their place. A caller that quotes no session
     * at all is allowed for the same reason and one more: the runtime endpoint is also how a
     * screen saves without a wrapper in front of it.
     */
    public boolean ownedBy(UUID session) {
        return this.activeSession == null || session == null || this.activeSession.equals(session);
    }

    public UUID getActiveSession() {
        return activeSession;
    }

    /**
     * Stores what the package left, and derives what follows from it.
     *
     * @return true when this save is the one that COMPLETED it — the caller announces on that and
     *         on nothing else, so a package that keeps saying "completed" on every later commit
     *         produces one event rather than one per commit
     */
    public boolean save(Map<String, String> next, int addedSeconds, Instant now) {
        this.data = Map.copyOf(next);
        /*
         * SILENCE NEVER ERASES AN OUTCOME, and this is the same argument as the ratchet below
         * rather than a second one.
         *
         * A conformant SCORM package reopened after it was finished reports `incomplete` and stops
         * mentioning the score — that is what a new attempt looks like in the standard's
         * vocabulary. Deriving these two straight from the current map therefore replaced a
         * recorded 88 and a recorded pass with nulls, because the learner clicked back into a
         * course they had already passed. Found by walking a real package through a resume.
         *
         * So a NEW answer replaces the old one — a retake that scores differently is recorded, and
         * a package that changes its mind is believed — and no answer leaves it alone.
         */
        this.passed = Cmi.passed(next).orElse(this.passed);
        this.scoreRaw = Cmi.scoreRaw(next).orElse(this.scoreRaw);
        // Bounded by the caller before it gets here; this only adds. Time in a package is
        // corroboration and never the decision (ADR-0107).
        this.secondsSpent += Math.max(addedSeconds, 0);
        /*
         * THE PACKAGE'S OWN CLOCK, kept separately from the browser's.
         *
         * `session_time` is what the package says this launch has lasted, and it is cumulative
         * within the launch rather than a delta -- so the total is the total this session STARTED
         * at plus the latest report, and re-sending a commit produces the same total rather than a
         * larger one. Silence leaves both where they are: a package that never writes it has not
         * said the session took no time, and `seconds_spent` above already knows roughly how long
         * the learner was there.
         */
        Timespan.sessionSeconds(next).ifPresent(session -> {
            this.sessionSeconds = (int) Math.min(session, Integer.MAX_VALUE - this.sessionBaseSeconds);
            this.totalSeconds = this.sessionBaseSeconds + this.sessionSeconds;
        });
        this.updatedAt = now;

        boolean nowComplete = Cmi.completed(next);
        /*
         * COMPLETION IS A RATCHET, and both halves of that matter.
         *
         * It only ever goes from false to true, so a package that reports `incomplete` on a later
         * launch -- which conformant ones do, on purpose, when a learner reopens a finished course
         * -- cannot revoke a compliance record. And `completedAt` is set once and never moved,
         * because the date is the thing an auditor reads: a course reopened in March must not
         * report as completed in March when it was finished in January.
         */
        if (nowComplete && !this.completed) {
            this.completed = true;
            this.completedAt = now;
            return true;
        }
        return false;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPackageId() {
        return packageId;
    }

    public UUID getLearnerId() {
        return learnerId;
    }

    public UUID getNodeId() {
        return nodeId;
    }

    /** The CMI map, for the wrapper to seed itself from. */
    public Map<String, String> getData() {
        return data == null ? Map.of() : data;
    }

    public boolean isCompleted() {
        return completed;
    }

    public Boolean getPassed() {
        return passed;
    }

    public BigDecimal getScoreRaw() {
        return scoreRaw;
    }

    public int getLaunches() {
        return launches;
    }

    public int getSecondsSpent() {
        return secondsSpent;
    }

    /** What the package says the current launch has lasted. */
    public int getSessionSeconds() {
        return sessionSeconds;
    }

    /** Every session added together, which is what {@code cmi.total_time} is formatted from. */
    public int getTotalSeconds() {
        return totalSeconds;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
