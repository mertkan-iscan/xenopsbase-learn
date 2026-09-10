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

    /**
     * An IANA zone id, or null when they have not told us (T-5.6).
     *
     * <p>Null is a state, not a missing value: catalog's deadlines fall back to UTC and say so,
     * which keeps the people who have never set one findable. Defaulting this to UTC would make
     * them indistinguishable from everybody who deliberately chose it.
     */
    @Column(name = "time_zone", length = 64)
    private String timeZone;

    /**
     * The language they read this product in, or null when they have not told us (T-10.9).
     *
     * <p>A BCP-47 tag, normalised but not checked against the set the product currently ships in.
     * That set is a frontend decision that changes without a migration, and a column that refused
     * anything outside today's two would start refusing valid rows on the day a third arrives.
     * An unrecognised tag falls back where it is read, which is what {@code localeFrom} in the
     * web application already does.
     */
    @Column(name = "language", length = 16)
    private String language;

    /**
     * Which palette they read it in, or null when they have not told us (T-10.9).
     *
     * <p>Null and {@link Theme#SYSTEM} render the same way and are still different answers — see
     * {@link Theme}. Nothing may treat the null as a choice of anything.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "theme", length = 16)
    private Theme theme;

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
     * Sets the timezone deadlines are reckoned in (T-5.6).
     *
     * <p>Validated as a zone the platform can actually resolve, because the failure of an
     * unparseable one is remote and quiet: a reminder at the wrong hour, or a learner marked late
     * on a day they were not.
     */
    public void moveTo(String zoneId) {
        if (zoneId == null || zoneId.isBlank()) {
            this.timeZone = null;
            return;
        }
        try {
            this.timeZone = java.time.ZoneId.of(zoneId).getId();
        } catch (java.time.DateTimeException e) {
            throw new IllegalArgumentException(
                "\"" + zoneId + "\" is not a timezone this platform knows. Use an IANA id such "
                + "as Europe/Istanbul.", e);
        }
    }

    /**
     * Sets the language this product is rendered in for them (T-10.9).
     *
     * <p>Normalised through {@link java.util.Locale}, which lowercases the language subtag and
     * canonicalises the rest, so {@code TR}, {@code tr} and {@code tr-TR} do not become three
     * different stored answers to one question. Blank clears it, for the same reason
     * {@link #moveTo} accepts an empty zone: somebody should be able to stop having told us.
     *
     * <p><b>Not validated against the languages the product ships in</b>, deliberately — see the
     * field, and V14. A tag nothing translates falls back at render time and costs a person the
     * language they asked for; a column that refused it would cost them the request entirely, and
     * would do so on the day a third language shipped.
     */
    public void prefersLanguage(String tag) {
        if (tag == null || tag.isBlank()) {
            this.language = null;
            return;
        }
        java.util.Locale parsed = java.util.Locale.forLanguageTag(tag.strip());
        if (parsed.getLanguage().isEmpty()) {
            // `forLanguageTag` does not throw. It answers with an undetermined locale, whose
            // language subtag is empty -- which is the only signal that the tag was nonsense,
            // and is easy to store by accident as a row that means nothing.
            throw new IllegalArgumentException(
                "\"" + tag + "\" is not a language tag. Use a BCP-47 tag such as tr or en-GB.");
        }
        this.language = parsed.toLanguageTag();
    }

    /**
     * Sets which palette they read the product in (T-10.9).
     *
     * <p>Blank clears it. {@link Theme#SYSTEM} does not: choosing to follow the operating system
     * is an answer, and storing it as "no answer" would lose the difference the column exists to
     * keep.
     */
    public void prefersTheme(String value) {
        this.theme = Theme.parse(value).orElse(null);
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

    /** Their timezone, or null when they have not told us. */
    public String getTimeZone() {
        return timeZone;
    }

    /** Their language as a BCP-47 tag, or null when they have not told us. */
    public String getLanguage() {
        return language;
    }

    /** Their palette, or null when they have not told us — which is not {@link Theme#SYSTEM}. */
    public Theme getTheme() {
        return theme;
    }
}
