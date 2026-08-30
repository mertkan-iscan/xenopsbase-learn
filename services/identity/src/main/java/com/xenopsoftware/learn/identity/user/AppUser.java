package com.xenopsoftware.learn.identity.user;

import com.xenopsoftware.learn.common.tenancy.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * A person, owned by us (T-1.2, ADR-0104).
 *
 * <p>{@link #id} is the identity every other table in every service references. {@link #idpSub}
 * is the only place this platform stores a Keycloak {@code sub}: a nullable, unique link that
 * {@link #relinkTo(String)} can repair in one column when an identity provider change or a realm
 * rebuild regenerates it. Both halves of that sentence are enforced — an ArchUnit rule and a
 * schema test fail the build on a sub-shaped field or column anywhere else.
 */
@Entity
@Table(name = "app_user")
public class AppUser extends TenantOwned {

    @Id
    @UuidGenerator
    @Column(name = "id")
    private UUID id;

    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private UserStatus status;

    @Column(name = "idp_sub", unique = true)
    private String idpSub;

    @Column(name = "invited_at")
    private Instant invitedAt;

    @Column(name = "invitation_expires_at")
    private Instant invitationExpiresAt;

    /** The SHA-256 of the open invitation token, hex — never the token (T-1.9). */
    @Column(name = "invitation_token_hash")
    private String invitationTokenHash;

    @Column(name = "deactivated_at")
    private Instant deactivatedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AppUser() {}

    public AppUser(String email, String displayName, String idpSub) {
        this.email = email;
        this.displayName = displayName;
        this.idpSub = idpSub;
        this.status = UserStatus.ACTIVE;
    }

    /**
     * An invited person: a row with no identity link yet (T-1.5).
     *
     * <p>This is what "the first admin is invited, not created with a password by us" means in
     * data. We never hold their credential — Keycloak does, and the row waits with
     * {@code idp_sub} null until they sign in for the first time and
     * {@link #acceptInvitation} links it.
     */
    public static AppUser invited(String email, String displayName) {
        AppUser invitee = new AppUser(email, displayName, null);
        invitee.status = UserStatus.INVITED;
        return invitee;
    }

    /**
     * Links an invited row to the identity that just signed in.
     *
     * <p>Distinct from {@link #relinkTo} on purpose: re-linking moves an account from one
     * identity to another and is a deliberate admin act, while this claims a row that no
     * identity has ever owned. The caller checks the email is verified — an unverified email is
     * an email anybody can claim, and this is the one path where that would hand over an
     * account nobody has signed into yet.
     */
    public void acceptInvitation(String idpSub) {
        if (this.idpSub != null) {
            throw new IllegalStateException(
                "User " + id + " already has an identity link; this is a re-link, not an invitation");
        }
        this.idpSub = idpSub;
        this.status = UserStatus.ACTIVE;
        // Single-use, and this is where that is true (T-1.9): the verifier is gone the moment
        // the invitation is spent, so a token read from a backup taken a minute ago opens
        // nothing.
        this.invitationTokenHash = null;
        this.invitationExpiresAt = null;
    }

    /**
     * Offers (or re-offers) an invitation: the verifier for a token the caller has just minted,
     * and the moment it stops working (T-1.9).
     *
     * <p>Re-inviting rotates rather than adds. One open invitation per person is the whole model
     * — two live tokens for one account is two ways in, and revoking the one somebody forwarded
     * to the wrong address would leave the other working.
     */
    public void offerInvitation(String tokenHash, Instant expiresAt, Instant now) {
        if (idpSub != null) {
            throw new IllegalStateException(
                "User " + id + " has already signed in; there is nothing to invite them to");
        }
        this.status = UserStatus.INVITED;
        this.invitationTokenHash = tokenHash;
        this.invitationExpiresAt = expiresAt;
        this.invitedAt = now;
    }

    public boolean hasInvitationExpiredAt(Instant now) {
        return invitationExpiresAt == null || !invitationExpiresAt.isAfter(now);
    }

    /**
     * Out, without being deleted (T-1.9). Everything they did stays exactly where it is, still
     * pointing at this id — including their group memberships and role assignments, because
     * reactivation has to restore access rather than rebuild it.
     */
    public void deactivate(Instant now) {
        this.status = UserStatus.DEACTIVATED;
        this.deactivatedAt = now;
    }

    /**
     * Back in, as the same person. Somebody who had signed in returns to ACTIVE; somebody
     * deactivated before they ever accepted goes back to waiting, because reactivating them into
     * ACTIVE would leave a row that is allowed to act and has no identity behind it.
     */
    public void reactivate() {
        this.status = idpSub == null ? UserStatus.INVITED : UserStatus.ACTIVE;
        this.deactivatedAt = null;
    }

    /**
     * The same row, a new address (T-1.9). Every attempt, score, membership and audit entry
     * references {@link #id}, so history follows without being touched — which is the entire
     * reason ADR-0104 refuses to key people by email or by {@code sub}.
     */
    public void changeEmailTo(String email) {
        this.email = email;
    }

    public void rename(String displayName) {
        this.displayName = displayName;
    }

    /**
     * The repair ADR-0104 exists for: point this person at their new IdP identity. Everything
     * that references them keeps referencing {@link #id}, so this is the entire migration.
     */
    public void relinkTo(String newIdpSub) {
        this.idpSub = newIdpSub;
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public UserStatus getStatus() {
        return status;
    }

    public String getIdpSub() {
        return idpSub;
    }

    public Instant getInvitedAt() {
        return invitedAt;
    }

    public Instant getInvitationExpiresAt() {
        return invitationExpiresAt;
    }

    public Instant getDeactivatedAt() {
        return deactivatedAt;
    }
}
