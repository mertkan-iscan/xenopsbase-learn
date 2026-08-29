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
}
