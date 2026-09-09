package com.xenopsoftware.learn.catalog.interstitial;

import com.xenopsoftware.learn.catalog.structure.CourseNodeRepository;
import com.xenopsoftware.learn.common.tenancy.TenantContext;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Authoring interstitials, and answering the one question playback asks about them (T-5.4).
 *
 * <p>Two audiences, and the split is worth reading before the code. <b>An author</b> puts a
 * question at a second and says whether it blocks — {@link #add}, {@link #move}, {@link #remove}.
 * <b>Playback</b> asks {@link #frontierOf}, which is one integer: how far this learner may be
 * credited right now. That asymmetry is deliberate. Everything an author configures collapses, for
 * the hot path, into a single number that streaming can cache and enforce (T-3.7) without learning
 * what an interstitial is.
 */
@Service
public class InterstitialService {

    private final InterstitialRepository interstitials;
    private final InterstitialResponses responses;
    private final CourseNodeRepository nodes;

    public InterstitialService(InterstitialRepository interstitials,
            InterstitialResponses responses, CourseNodeRepository nodes) {
        this.interstitials = interstitials;
        this.responses = responses;
        this.nodes = nodes;
    }

    @Transactional
    public Interstitial add(UUID nodeId, int positionSeconds, UUID questionId, boolean blocking,
            boolean askAgain) {
        if (nodes.findById(nodeId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such node");
        }
        if (positionSeconds < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "An interstitial sits at a second of the item, and there is no second before zero");
        }
        if (questionId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "An interstitial asks a question. Its id is assessment's (T-6.2), not its text");
        }
        return save(Interstitial.at(nodeId, positionSeconds, questionId, blocking, askAgain));
    }

    /**
     * Moves one along the timeline.
     *
     * <p><b>Answers already recorded are not discarded, and that is a decision rather than an
     * omission.</b> An author nudging a marker from 300s to 305s has not asked a new question, and
     * a learner who answered it should not meet it again — the question is the identity here, and
     * the second is where it is shown. An author who wants it asked afresh removes it and adds it,
     * which is two deliberate requests rather than a surprise inside one.
     */
    @Transactional
    public Interstitial move(UUID id, int positionSeconds) {
        Interstitial interstitial = require(id);
        if (positionSeconds < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "There is no second before zero");
        }
        interstitial.moveTo(positionSeconds);
        return save(interstitial);
    }

    @Transactional
    public Interstitial reconfigure(UUID id, boolean blocking, boolean askAgain) {
        Interstitial interstitial = require(id);
        interstitial.reconfigure(blocking, askAgain);
        return save(interstitial);
    }

    @Transactional
    public void remove(UUID id) {
        interstitials.delete(require(id));
    }

    @Transactional(readOnly = true)
    public List<Interstitial> on(UUID nodeId) {
        return interstitials.findByNodeIdOrderByPositionSecondsAsc(nodeId);
    }

    /**
     * How far into this node this learner may be credited, or null when nothing blocks them.
     *
     * <p>This is what crosses the boundary to streaming. Not the list, not the questions, not the
     * blocking flags — one second, which is all the completion accounting needs and all it should
     * be given (ADR-0109).
     */
    @Transactional(readOnly = true)
    public Integer frontierOf(UUID nodeId, UUID learnerId, String viewing) {
        return responses.frontierOf(TenantContext.require(), nodeId, learnerId, viewing);
    }

    /** Which of this node's interstitials this learner has satisfied. */
    @Transactional(readOnly = true)
    public List<UUID> answeredBy(UUID nodeId, UUID learnerId, String viewing) {
        return responses.answeredOn(TenantContext.require(), nodeId, learnerId, viewing);
    }

    /** One, by id. */
    @Transactional(readOnly = true)
    public Interstitial get(UUID id) {
        return require(id);
    }

    private Interstitial require(UUID id) {
        return interstitials.findById(id).orElseThrow(() ->
            new ResponseStatusException(HttpStatus.NOT_FOUND, "No such interstitial"));
    }

    private Interstitial save(Interstitial interstitial) {
        try {
            return interstitials.saveAndFlush(interstitial);
        } catch (DataIntegrityViolationException clash) {
            // Two interstitials at the same instant have no order a player could show them in and
            // no order this table could report, so the ambiguity is refused rather than resolved
            // by whatever the planner returns.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "This node already has an interstitial at " + interstitial.getPositionSeconds()
                + "s", clash);
        }
    }
}
