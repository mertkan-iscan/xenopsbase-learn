package com.xenopsoftware.learn.identity.user;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * First sight of a verified token becomes an {@link AppUser} row, idempotently (T-1.2).
 *
 * <p>The race this must survive is ordinary, not exotic: a first-time user's browser fires two
 * requests, both threads find no row, both insert. The unique constraint on {@code idp_sub}
 * arbitrates — the loser catches the violation and reads the winner's row. That is why
 * {@link #provision} is deliberately <b>not</b> {@code @Transactional}: the re-read after a
 * constraint violation needs a fresh transaction, and each repository call brings its own.
 *
 * <p>What this refuses to do is auto-relink: an unknown {@code sub} arriving with a known email
 * is a conflict a human resolves ({@code docs/runbooks/identity.md}), because email ownership
 * must not be equivalent to account takeover. {@link #relink} is that resolution as code — a
 * deliberate act behind an admin's decision, one column, everything else follows because
 * everything else points at {@link AppUser#getId()}.
 */
@Service
public class UserProvisioningService {

    private static final Logger LOG = LoggerFactory.getLogger(UserProvisioningService.class);

    private final AppUserRepository repository;
    private final AppUserCreator creator;

    public UserProvisioningService(AppUserRepository repository, AppUserCreator creator) {
        this.repository = repository;
        this.creator = creator;
    }

    public AppUser provision(Jwt jwt) {
        com.xenopsoftware.learn.identity.impersonation.ImpersonationContext.current()
            .ifPresent(session -> {
                // The one place this is unambiguously wrong, made loud (T-2.8). Under a session
                // the bound tenant is the CUSTOMER's, so provisioning the caller would silently
                // create a support engineer's account inside a company that never invited them --
                // visible to that company, counted in its seats, and never explained. Every
                // caller that legitimately needs an identity here already asks the session for
                // it; a new one that does not gets this instead of the quiet version.
                throw new IllegalStateException(
                    "Refusing to provision " + jwt.getSubject() + " into " + session.tenantId()
                    + " under impersonation session " + session.sessionId()
                    + ". Ask ImpersonationContext who is being acted as.");
            });
        String sub = jwt.getSubject();
        Optional<AppUser> linked = repository.findByIdpSub(sub);
        if (linked.isPresent()) {
            return linked.get();
        }

        String email = jwt.getClaimAsString("email");
        if (email == null || email.isBlank()) {
            // Not a user problem: every seeded and every provisioned user has an email, so a
            // token without one means the realm's email scope stopped applying -- the same
            // failure shape as the clientScopes trap documented on T-9.9.
            throw new IllegalStateException(
                "Token for subject " + sub + " carries no email claim; cannot provision. "
                + "Check the realm's email scope before checking anything else.");
        }

        Optional<AppUser> sameEmail = repository.findByEmailIgnoreCase(email);
        if (sameEmail.isPresent() && sub.equals(sameEmail.get().getIdpSub())) {
            // Lost the concurrent-first-login race in the window BETWEEN the two lookups above:
            // the winner committed after our sub lookup missed and before our email lookup ran.
            // An email match carrying our own sub is our own row, not somebody else's account,
            // and treating it as a conflict would fail a legitimate first login roughly whenever
            // a browser fires two requests at once.
            return sameEmail.get();
        }
        if (sameEmail.isPresent() && sameEmail.get().getIdpSub() == null
            && sameEmail.get().getStatus() == UserStatus.INVITED) {
            // An invitation being accepted (T-1.5): the row was created for this email and no
            // identity has ever owned it, so linking is not a takeover -- there is nothing to
            // take over. The email must be VERIFIED, because this is the one path where an
            // unverified claim would hand over an account.
            if (!Boolean.TRUE.equals(jwt.getClaim("email_verified"))) {
                LOG.warn("Refusing to accept the invitation for {}: the token email is not verified", email);
                throw new IdentityConflictException(
                    "This email has an open invitation, but the sign-in did not prove ownership "
                    + "of it. Verify the address with your identity provider and try again.");
            }
            AppUser invitee = sameEmail.get();
            invitee.acceptInvitation(sub);
            return repository.saveAndFlush(invitee);
        }
        if (sameEmail.isPresent()) {
            LOG.warn("Provisioning refused: subject {} is unknown but email {} belongs to app_user {}. "
                + "If the IdP identity changed, re-link deliberately (docs/runbooks/identity.md).",
                sub, email, sameEmail.get().getId());
            throw new IdentityConflictException(
                "This email already belongs to an existing user with a different identity link. "
                + "Re-linking is a deliberate act; see docs/runbooks/identity.md.");
        }

        try {
            // In its own transaction (AppUserCreator), so the person survives whatever the
            // caller was doing failing -- and so an audit entry written in a second transaction
            // cannot block on a foreign key to a row this one has not committed.
            return creator.create(email, displayNameFrom(jwt, email), sub);
        } catch (DataIntegrityViolationException raceOrCollision) {
            // Lost the concurrent-first-login race: the winner's row is the answer.
            Optional<AppUser> winner = repository.findByIdpSub(sub);
            if (winner.isPresent()) {
                return winner.get();
            }
            // Not the race -- the email collided between our pre-check and the insert.
            throw new IdentityConflictException(
                "This email already belongs to an existing user with a different identity link. "
                + "Re-linking is a deliberate act; see docs/runbooks/identity.md.");
        }
    }

    /**
     * Points an existing user at a new IdP identity. The runbook's UPDATE, as code, with the
     * same guards: matched inside the tenant by email, never a deactivated account, never a sub
     * that already belongs to someone else.
     */
    @Transactional
    public AppUser relink(String email, String newIdpSub) {
        AppUser user = repository.findByEmailIgnoreCase(email)
            .orElseThrow(() -> new IllegalArgumentException(
                "No user with email " + email + " in this tenant; nothing to re-link."));
        if (user.getStatus() == UserStatus.DEACTIVATED) {
            throw new IllegalArgumentException(
                "User " + user.getId() + " is deactivated; re-linking would reactivate an "
                + "account nobody decided to reactivate.");
        }
        Optional<AppUser> holder = repository.findByIdpSub(newIdpSub);
        if (holder.isPresent() && !holder.get().getId().equals(user.getId())) {
            throw new IdentityConflictException(
                "Subject already linked to app_user " + holder.get().getId());
        }
        user.relinkTo(newIdpSub);
        return repository.save(user);
    }

    private static String displayNameFrom(Jwt jwt, String email) {
        String name = jwt.getClaimAsString("name");
        if (name != null && !name.isBlank()) {
            return name;
        }
        String username = jwt.getClaimAsString("preferred_username");
        if (username != null && !username.isBlank()) {
            return username;
        }
        return email;
    }
}
