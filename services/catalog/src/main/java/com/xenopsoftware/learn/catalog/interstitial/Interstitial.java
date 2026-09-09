package com.xenopsoftware.learn.catalog.interstitial;

import com.xenopsoftware.learn.common.tenancy.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A question pinned inside a video's timeline (T-5.4).
 *
 * <p><b>Why this is not a node with a fractional ordinal.</b> Everything else in a course is
 * ordered relative to other nodes, and {@link com.xenopsoftware.learn.catalog.structure.Ordinals}
 * exists to make that cheap. This one is positioned <em>inside</em> another item, so it is bound to
 * {@code (node, second)} — and a second is not an ordinal: it cannot be moved by averaging its
 * neighbours, it is comparable with a video's duration, and it has to survive a player seeking,
 * pausing, going offline and reloading mid-interruption.
 *
 * <p><b>A blocking one is enforced by arithmetic, not by a pause.</b> A pause a player performs is
 * a pause a player can decline to perform. What holds instead is that streaming will not credit
 * coverage past an unanswered blocking interstitial (T-3.7), so a learner whose player skipped it
 * watches the rest and still does not complete the item. {@link #getPositionSeconds()} is a
 * frontier in the completion accounting; the pause the learner sees is the player rendering it.
 */
@Entity
@Table(name = "node_interstitial")
public class Interstitial extends TenantOwned {

    @Id
    private UUID id;

    @Column(name = "node_id", nullable = false)
    private UUID nodeId;

    @Column(name = "position_seconds", nullable = false)
    private int positionSeconds;

    @Column(nullable = false)
    private boolean blocking;

    @Column(name = "ask_again", nullable = false)
    private boolean askAgain;

    /**
     * Assessment's question (T-6.2), by id and never by copy.
     *
     * <p>The stem, the options and the answer key belong to the module that versions them
     * (ADR-0106). A stem duplicated here would be the stem that stops matching the question a
     * learner is actually shown, and the first anybody would know is a report about a question
     * nobody asked.
     */
    @Column(name = "question_id", nullable = false)
    private UUID questionId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Interstitial() {
        // Hibernate.
    }

    public static Interstitial at(UUID nodeId, int positionSeconds, UUID questionId,
            boolean blocking, boolean askAgain) {
        Interstitial interstitial = new Interstitial();
        interstitial.id = UUID.randomUUID();
        interstitial.nodeId = nodeId;
        interstitial.positionSeconds = positionSeconds;
        interstitial.questionId = questionId;
        interstitial.blocking = blocking;
        interstitial.askAgain = askAgain;
        interstitial.createdAt = Instant.now();
        interstitial.updatedAt = interstitial.createdAt;
        return interstitial;
    }

    /** Moves it along the timeline. The node it belongs to is not a thing that changes. */
    public void moveTo(int newPositionSeconds) {
        this.positionSeconds = newPositionSeconds;
        this.updatedAt = Instant.now();
    }

    public void reconfigure(boolean nowBlocking, boolean nowAskAgain) {
        this.blocking = nowBlocking;
        this.askAgain = nowAskAgain;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getNodeId() {
        return nodeId;
    }

    public int getPositionSeconds() {
        return positionSeconds;
    }

    public boolean isBlocking() {
        return blocking;
    }

    public boolean isAskAgain() {
        return askAgain;
    }

    public UUID getQuestionId() {
        return questionId;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
