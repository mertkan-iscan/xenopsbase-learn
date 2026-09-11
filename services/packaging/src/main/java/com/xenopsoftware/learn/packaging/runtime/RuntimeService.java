package com.xenopsoftware.learn.packaging.runtime;

import com.xenopsoftware.learn.common.messaging.Outbox;
import com.xenopsoftware.learn.common.tenancy.TenantContext;
import com.xenopsoftware.learn.packaging.bundle.ContentPackage;
import com.xenopsoftware.learn.packaging.bundle.ContentPackageRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Loading and saving where a learner got to inside a package (T-4.4, ADR-0107).
 *
 * <h2>The chain this sits at the end of, because nothing else explains the shape</h2>
 *
 * <ol>
 *   <li>The package calls {@code LMSSetValue} / {@code LMSCommit} on the wrapper, which is on the
 *       tenant's content origin and <b>holds no credential of ours</b> — that is ADR-0105's whole
 *       decision, and it is why the wrapper cannot save anything itself.
 *   <li>The wrapper posts the CMI map to the application over {@code postMessage}, naming the
 *       application's origin exactly.
 *   <li>The application, which does have the learner's session, calls this.
 * </ol>
 *
 * <p>So the relay runs in the learner's own browser. That is not a weakness this design introduced
 * — a conformant LMS believes what a package says, and the package runs on the learner's machine
 * either way (ADR-0107's "bored learner with developer tools" bar). What the platform owes in
 * return is that the resulting completion is recorded as {@code SELF_REPORTED} and every report
 * says so, which is what the event below and catalog's {@code source} column are for.
 *
 * <h2>What is derived here and what is stored verbatim</h2>
 *
 * <p>The wrapper posts <b>the data model</b> and never a verdict. "Completion is derived by the
 * server" is ADR-0107's title, and a client that could post {@code completed: true} would make the
 * compliance record a boolean uploaded JavaScript computed. {@link Cmi} does the reading, here,
 * from the standard's own vocabulary.
 */
@Service
public class RuntimeService {

    private static final Logger LOG = LoggerFactory.getLogger(RuntimeService.class);

    /** Catalog folds this into node state and records it as SELF_REPORTED (ADR-0107, T-5.3). */
    static final String COMPLETED_SUBJECT = "packaging.node.completed";

    /**
     * The most CMI elements one save may carry.
     *
     * <p>A package writes {@code cmi.interactions.n.*} per question, so a long assessment
     * legitimately reaches a few hundred. Two thousand is an order of magnitude above anything
     * real and bounds what one request can put in one row — this is a learner-writable JSON blob,
     * and the only thing standing between it and a database full of whatever somebody likes is a
     * number.
     */
    private static final int MAX_ELEMENTS = 2_000;

    /**
     * The most any one element may be, in characters.
     *
     * <p>{@code cmi.suspend_data} is the one this is really about: it is where an authoring tool
     * puts its own serialised state, and it is the only element that is routinely large. The
     * standards set two different floors for it — SCORM 1.2 requires an LMS to accept 4,096
     * characters and SCORM 2004 raises that to 64,000 — and both are minimums rather than
     * ceilings, so the number here has to be chosen rather than read off.
     *
     * <p>65,536 clears both, and clears 2004's by enough that a package sizing its blob to exactly
     * the 64,000 it is promised has room rather than a boundary to sit on.
     *
     * <p><b>Exceeding it is an error and never a truncation.</b> A suspend blob is opaque and
     * usually a serialised object graph; half of one does not deserialise, so a package handed
     * back a truncated blob does not resume at slide thirty — it fails to start, on the next
     * launch, with no message that names the cause. Refusing the save keeps the last blob that was
     * whole. The wrapper enforces the same number at {@code SetValue}, so the package hears about
     * it in its own vocabulary at the moment it wrote it (error 405 / 407) rather than finding out
     * from a network call it never sees.
     */
    static final int MAX_VALUE_LENGTH = 65_536;

    /**
     * The most time one save may claim.
     *
     * <p>The client reports how long the learner has been in the package since the last save, and
     * this bounds what that can add. Not a security control — time in a package is corroboration
     * and never the decision (ADR-0107) — but an unbounded number a browser sends is a column
     * that eventually holds a century.
     */
    private static final int MAX_ADDED_SECONDS = 4 * 60 * 60;

    /**
     * Elements the platform answers and a package may not store.
     *
     * <p>Both spellings of {@code total_time}, which is read-only by the standard in both
     * vocabularies: the LMS accumulates it from each session and hands it back, so it is derived
     * on the way out ({@link #seeded}) rather than kept in the map on the way in.
     */
    private static final java.util.Set<String> SERVER_OWNED =
        java.util.Set.of("cmi.core.total_time", "cmi.total_time");

    private final PackageRuntimeRepository runtimes;
    private final ContentPackageRepository packages;
    private final CommitBudget budget;
    private final Outbox outbox;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();

    public RuntimeService(PackageRuntimeRepository runtimes, ContentPackageRepository packages,
            CommitBudget budget, ObjectProvider<Outbox> outbox, Clock clock) {
        this.runtimes = runtimes;
        this.packages = packages;
        this.budget = budget;
        this.outbox = outbox.getIfAvailable();
        this.clock = clock;
        if (this.outbox == null) {
            LOG.warn("No outbox is configured, so a package's completion is recorded here and "
                + "announced to nobody: catalog will not open the gate behind it (T-5.3) and no "
                + "compliance report will see it. Set platform.outbox.enabled=true.");
        }
    }

    /**
     * The learner's runtime for this package, created on first launch.
     *
     * <p>Creating on a READ is deliberate and is what {@code launches} counts. The first thing a
     * launch does is ask for its state, and a package's {@code cmi.core.entry} has to answer
     * {@code ab-initio} the first time and {@code resume} afterwards — which is only knowable if
     * the read is the thing that records the launch.
     */
    @Transactional
    public PackageRuntime open(UUID packageId, UUID nodeId, UUID learnerId) {
        ContentPackage stored = packages.findById(packageId)
            // Another company's package is simply not found (ADR-0102), and so is a package id
            // somebody invented.
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!stored.getState().isLaunchable()) {
            // A runtime for a package that cannot be opened would be a row nothing will ever read,
            // and creating one on request is a way to fill a table with ids somebody guessed.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "This package is not ready to be opened.");
        }
        Instant now = clock.instant();
        PackageRuntime runtime = runtimes.find(learnerId, packageId, nodeId)
            .orElseGet(() -> new PackageRuntime(packageId, learnerId, nodeId, now));
        /*
         * THIS LAUNCH NOW OWNS THE REGISTRATION, and the one before it is finished (V5).
         *
         * Minted here rather than by the caller: a session id a client chose would let a tab keep
         * ownership by re-sending the same one, which is precisely the tab this rule exists to
         * stop. Opening the package is the act that takes ownership, and opening is a thing only
         * this method does.
         */
        runtime.launched(UUID.randomUUID(), now);
        return runtimes.saveAndFlush(runtime);
    }

    /**
     * Stores what the package left, and announces a completion if this is the save that made one.
     *
     * @param session      the launch this save belongs to, from the open that started it. A save
     *                     quoting a superseded launch is refused; see {@link PackageRuntime#ownedBy}
     * @param addedSeconds how long the learner has been in the package since the last save, as the
     *                     client reports it. Clamped; see {@link #MAX_ADDED_SECONDS}
     * @throws ResponseStatusException {@code 409} when another launch has taken the registration,
     *                     {@code 429} when this registration is over its write budget
     */
    @Transactional
    public PackageRuntime save(UUID packageId, UUID nodeId, UUID learnerId, UUID session,
            Map<String, String> data, int addedSeconds) {
        /*
         * BEFORE THE ROW IS READ, so that a storm costs one Valkey INCR rather than a SELECT ...
         * FOR UPDATE on the row it is trying to protect. Checking after the read would still
         * refuse, and would still have done the expensive half of the work first.
         */
        if (!budget.permit(learnerId, packageId, nodeId)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                "This course is saving more often than it needs to. The next save will keep "
                + "everything, because every save carries the whole data model.");
        }
        PackageRuntime runtime = runtimes.find(learnerId, packageId, nodeId)
            // A save with no open before it. Not an error -- a reload mid-course, a tab restored --
            // and the alternative is losing the learner's answers to a bookkeeping detail.
            .orElseGet(() -> new PackageRuntime(packageId, learnerId, nodeId, clock.instant()));

        if (!runtime.ownedBy(session)) {
            /*
             * A SECOND TAB HAS TAKEN THE COURSE, and this one is holding a data model that
             * diverged from it (V5).
             *
             * 409 rather than 403: nothing about the caller is wrong, and they were entitled to
             * write this until somebody -- almost always themselves, in another tab -- opened it
             * again. The client stops committing on this and says so on screen, which is the whole
             * value of the rule: the failure it replaces was a learner's progress being replaced
             * by an empty map with nothing anywhere recording that it had happened.
             */
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "This course was opened somewhere else, so this window has stopped saving.");
        }

        boolean justCompleted = runtime.save(sanitise(data), Math.min(addedSeconds, MAX_ADDED_SECONDS),
            clock.instant());
        PackageRuntime saved = runtimes.saveAndFlush(runtime);

        if (justCompleted) {
            announce(saved);
        }
        return saved;
    }

    /**
     * The data model as the package should find it, which is what was stored plus what we owe it.
     *
     * <p>{@code total_time} is the LMS's to answer: the standard makes it read-only precisely
     * because a package cannot know what happened in the sessions before this one. A course that
     * displays "time spent" reads this element, and until it is filled in the course displays
     * nothing or displays a parse error inside somebody else's JavaScript.
     *
     * <p><b>Both spellings, always, and each in its own format.</b> The obvious version of this
     * writes only the one the declared profile calls for — and it is wrong for the same reason
     * {@link Cmi} asks for every vocabulary on the way in: the declaration is not the truth about
     * the JavaScript inside. It is also wrong for a reason of our own. The wrapper decides which
     * defaults to seed from {@code profile === 'scorm-2004'}, so a {@code cmi5} package gets the
     * 1.2 names there; a server that sent only {@code cmi.total_time} for it would leave the
     * wrapper to default {@code cmi.core.total_time} to nought, and the package would read a total
     * of zero however many hours the learner had spent. Two map entries costs nothing and removes
     * the whole class of disagreement.
     *
     * @param profile the package's declared profile. Unused today, and kept because the day a
     *                profile needs a value of its own, this is the method that owes it
     */
    public Map<String, String> seeded(PackageRuntime runtime, String profile) {
        Map<String, String> data = new HashMap<>(runtime.getData());
        data.put("cmi.total_time", Timespan.format(runtime.getTotalSeconds(), true));
        data.put("cmi.core.total_time", Timespan.format(runtime.getTotalSeconds(), false));
        return data;
    }

    /**
     * What a browser is allowed to put in the row.
     *
     * <p>Every value in the CMI model is a string, and the keys are generated at runtime from
     * content nobody here has seen — so there is no allowlist to check against and the bounds are
     * the check. An element name longer than a database column or a suspend blob larger than the
     * standard's own ceiling is not a package doing something legitimate.
     */
    private Map<String, String> sanitise(Map<String, String> data) {
        if (data == null || data.isEmpty()) {
            return Map.of();
        }
        if (data.size() > MAX_ELEMENTS) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                "A package may store at most " + MAX_ELEMENTS + " data-model elements.");
        }
        Map<String, String> clean = new HashMap<>(data.size());
        for (Map.Entry<String, String> element : data.entrySet()) {
            String key = element.getKey();
            String value = element.getValue();
            if (key == null || key.isBlank() || value == null) {
                // A null value is "never written" in the CMI model, not "written as nothing", so
                // dropping it is what the standard means rather than a convenience.
                continue;
            }
            if (key.length() > 255) {
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "\"" + key.substring(0, 60) + "…\" is not a data-model element name.");
            }
            if (value.length() > MAX_VALUE_LENGTH) {
                // NOT truncated. See MAX_VALUE_LENGTH: half a suspend blob does not deserialise,
                // so a package handed one back fails to start rather than resuming badly.
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "The value of \"" + key + "\" is longer than the " + MAX_VALUE_LENGTH
                    + " characters one data-model element may hold.");
            }
            if (SERVER_OWNED.contains(key)) {
                /*
                 * DROPPED, not refused, and not stored.
                 *
                 * `total_time` is a read-only element the LMS fills in -- the platform derives it
                 * from the accumulated sessions and injects it when the wrapper is seeded, so a
                 * copy inside the stored map would be a second answer to the same question that
                 * goes stale the moment the first one changes. A package that writes to it has
                 * done something the standard forbids and the wrapper already refused (error
                 * 403 / 404); a copy arriving here anyway is somebody replaying a request by hand.
                 */
                continue;
            }
            clean.put(key, value);
        }
        return clean;
    }

    /**
     * Tells the rest of the platform that a learner finished a package.
     *
     * <p><b>Only when there is a node.</b> A completion is a fact about a learner and a place in a
     * course; a preview launch by an author checking their upload has no place in a course and
     * announcing one would open a gate for training nobody was assigned.
     *
     * <p>Written to the outbox inside the same transaction as the row, so a completion that rolled
     * back announces nothing and a completion that committed cannot fail to announce (T-9.8).
     */
    private void announce(PackageRuntime runtime) {
        if (runtime.getNodeId() == null) {
            LOG.debug("Package {} completed outside a course by {}; nothing to announce",
                runtime.getPackageId(), runtime.getLearnerId());
            return;
        }
        if (outbox == null) {
            return;
        }
        String tenantId = TenantContext.require();
        Map<String, Object> payload = new HashMap<>();
        payload.put("tenantId", tenantId);
        payload.put("learnerId", runtime.getLearnerId().toString());
        payload.put("nodeId", runtime.getNodeId().toString());
        payload.put("packageId", runtime.getPackageId().toString());
        payload.put("passed", runtime.getPassed());
        payload.put("scoreRaw", runtime.getScoreRaw());
        payload.put("secondsSpent", runtime.getSecondsSpent());
        // What the PACKAGE said it took, beside what the browser measured. Reporting needs both:
        // one is the standard's own number and the other is the corroboration ADR-0107 keeps.
        payload.put("totalSeconds", runtime.getTotalSeconds());
        payload.put("completedAt", runtime.getCompletedAt().toString());
        /*
         * The source is NOT in this payload, deliberately.
         *
         * Catalog reads provenance from the SUBJECT, not from a field, because a field is a claim
         * the sender makes -- see PackageCompletionHandler. Everything on this subject is
         * self-reported by construction, and there is no value this service could put here that
         * would be more trustworthy than the routing that got it there.
         */
        outbox.publish(tenantId, COMPLETED_SUBJECT, "NodeCompleted",
            json.writeValueAsString(payload));
    }
}
