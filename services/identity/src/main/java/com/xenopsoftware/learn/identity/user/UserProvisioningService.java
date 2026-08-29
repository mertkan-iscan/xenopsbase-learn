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
    private final com.xenopsoftware.learn.identity.authz.SystemRoleSeeder systemRoles;

    public UserProvisioningService(AppUserRepository repository,
            com.xenopsoftware.learn.identity.authz.SystemRoleSeeder systemRoles) {
        this.repository = repository;
        this.systemRoles = systemRoles;
    }

    public AppUser provision(Jwt jwt) {
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
        if (sameEmail.isPresent()) {
            LOG.warn("Provisioning refused: subject {} is unknown but email {} belongs to app_user {}. "
                + "If the IdP identity changed, re-link deliberately (docs/runbooks/identity.md).",
                sub, email, sameEmail.get().getId());
            throw new IdentityConflictException(
                "This email already belongs to an existing user with a different identity link. "
                + "Re-linking is a deliberate act; see docs/runbooks/identity.md.");
        }

        try {
            // saveAndFlush, not save: the row has to be real in the database the moment this
            // returns, because callers in the same transaction reach it with raw SQL that does
            // not see Hibernate pending inserts -- the audit log FK to app_user is the first
            // such caller (T-2.2). It also makes the concurrent-first-login race surface here,
            // at the insert, rather than later at commit.
            AppUser created = repository.saveAndFlush(new AppUser(email, displayNameFrom(jwt, email), sub));
            // A new person in a tenant nobody has logged into yet: its role templates are
            // projected now rather than at the next restart (T-2.7). Idempotent, so the second
            // and every later user costs one indexed lookup per template.
            systemRoles.ensureSeededFor(created.getTenantId());
            return created;
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
