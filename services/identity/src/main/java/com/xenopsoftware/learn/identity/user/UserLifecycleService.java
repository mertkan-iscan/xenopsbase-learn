package com.xenopsoftware.learn.identity.user;

import com.xenopsoftware.learn.identity.audit.AuditLogger;
import com.xenopsoftware.learn.identity.audit.CurrentUser;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Invite, accept, deactivate, reactivate, re-address and import (T-1.9).
 *
 * <p><b>The rule that decides every method here: nothing deletes.</b> A person who leaves still
 * has to appear in last year's compliance report, and the report has to distinguish "no longer
 * with the company" from "never did the training" — the same row unless the model keeps them
 * apart. So leaving is a status and a timestamp, returning is the same row again, and changing
 * an address is one column, because everything that references a person references
 * {@code app_user.id} (ADR-0104).
 *
 * <p><b>What deactivation does not do here, and where it lands instead.</b> It stops this
 * service on the next request ({@code DeactivatedUserFilter}). It does not revoke the browser
 * session, which the gateway holds (T-1.4), and it does not refuse playback tokens, which
 * {@code streaming} mints (T-3.4). Both read the same status; neither exists yet, and asserting
 * either here would be a test of nothing.
 *
 * <p>Status is deliberately <em>not</em> an authorization version bump. What somebody may do has
 * not changed — whether they may act at all has, and that is checked against the row on every
 * request rather than cached (T-2.5).
 */
@Service
public class UserLifecycleService {

    /**
     * Deliberately permissive: something, an {@code @}, something with a dot. Address syntax is
     * not a proof of anything — an address is proved by somebody signing in with it verified —
     * and a stricter pattern here would reject real addresses in an import of five hundred and
     * teach customers that the file must be edited to please us.
     */
    private static final Pattern LOOKS_LIKE_EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final AppUserRepository repository;
    private final InvitationProperties invitations;
    private final AuditLogger audit;
    private final CurrentUser currentUser;

    public UserLifecycleService(AppUserRepository repository, InvitationProperties invitations,
            AuditLogger audit, CurrentUser currentUser) {
        this.repository = repository;
        this.invitations = invitations;
        this.audit = audit;
        this.currentUser = currentUser;
    }

    /** An invitation, and the one moment its token exists outside the invitee's hands. */
    public record Invitation(UUID userId, String email, String displayName, String token,
                             Instant expiresAt) {}

    /** What an import did, or would do. */
    public record ImportReport(boolean dryRun, int invited, int reinvited, int skipped, int failed,
                               List<ImportRow> rows) {}

    /** One line of the file, and what became of it. {@code token} is null unless it was minted. */
    public record ImportRow(int line, String email, ImportAction action, String detail,
                            String token) {}

    /** What an import row did. Reported per row, never as one aggregate success or failure. */
    public enum ImportAction { INVITE, REINVITE, SKIP, ERROR }

    /**
     * Invites somebody, or re-invites somebody who has not accepted yet — which rotates the
     * token rather than issuing a second one.
     */
    @Transactional
    public Invitation invite(String email, String displayName) {
        Invitation invitation = offer(email, displayName);
        audit.record("user.invite", "user", invitation.userId(),
            Map.of("email", invitation.email(), "expiresAt", invitation.expiresAt().toString()));
        return invitation;
    }

    /**
     * Accepts an invitation with its token, linking the row to whoever is signed in.
     *
     * <p>The token is the proof, and that is a deliberate choice with a consequence worth
     * stating: whoever holds it becomes this person, even if they sign in with an address that
     * is not the one invited. That is what a bearer invitation is, and it is why the token is
     * single-use, expiring, and never mailed by us. The other acceptance path — signing in with
     * a <em>verified</em> address that matches the invited one (T-1.5) — needs no token at all
     * and is the one an ordinary employee will use.
     */
    @Transactional
    public AppUser accept(String token, Jwt caller) {
        if (token == null || token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such invitation");
        }
        AppUser invitee = repository.findByInvitationTokenHash(InvitationToken.hash(token))
            // 404 rather than 401: a token that does not exist and a token for another company
            // must be indistinguishable, and both are simply "no such invitation".
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such invitation"));

        if (invitee.hasInvitationExpiredAt(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.GONE,
                "This invitation has expired. Ask an administrator to send a new one.");
        }
        if (invitee.getStatus() == UserStatus.DEACTIVATED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "This account is deactivated; accepting an invitation must not reactivate it.");
        }
        Optional<AppUser> alreadyLinked = repository.findByIdpSub(caller.getSubject());
        if (alreadyLinked.isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "You are already signed in as " + alreadyLinked.get().getEmail()
                + "; accepting would give one identity two accounts.");
        }

        invitee.acceptInvitation(caller.getSubject());
        AppUser accepted = repository.saveAndFlush(invitee);
        audit.record("user.invitation.accept", "user", accepted.getId(),
            Map.of("email", accepted.getEmail()));
        return accepted;
    }

    /** One person, as an administrator sees them — status and all. */
    public AppUser get(UUID userId) {
        return require(userId);
    }

    /** Out, keeping everything they did. */
    @Transactional
    public AppUser deactivate(UUID userId) {
        AppUser user = require(userId);
        if (user.getId().equals(currentUser.requireId())) {
            // Not paternalism: the last administrator deactivating themselves leaves a company
            // whose only route back in is us touching their database.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "You cannot deactivate yourself; another administrator has to.");
        }
        if (user.getStatus() == UserStatus.DEACTIVATED) {
            return user;
        }
        user.deactivate(Instant.now());
        AppUser deactivated = repository.saveAndFlush(user);
        // Their memberships and role assignments are deliberately untouched. Reactivation has
        // to restore access rather than rebuild it, and a report that says "no longer with the
        // company" needs the assignments that say what they were expected to do.
        audit.record("user.deactivate", "user", deactivated.getId(),
            Map.of("email", deactivated.getEmail()));
        return deactivated;
    }

    /** Back in, as the same person: same id, same identity link, same everything. */
    @Transactional
    public AppUser reactivate(UUID userId) {
        AppUser user = require(userId);
        if (user.getStatus() != UserStatus.DEACTIVATED) {
            return user;
        }
        user.reactivate();
        AppUser reactivated = repository.saveAndFlush(user);
        audit.record("user.reactivate", "user", reactivated.getId(), Map.of(
            "email", reactivated.getEmail(),
            "status", reactivated.getStatus().name()));
        return reactivated;
    }

    /**
     * A new address or a new name on the same row. The unique index on
     * {@code (tenant_id, lower(email))} is what makes "re-link rather than duplicate" a fact
     * rather than an intention; this reports the collision as a conflict rather than letting the
     * constraint surface as a 500.
     */
    @Transactional
    public AppUser update(UUID userId, String email, String displayName) {
        AppUser user = require(userId);
        Map<String, Object> changed = new LinkedHashMap<>();

        if (email != null && !email.isBlank() && !email.equalsIgnoreCase(user.getEmail())) {
            requireLooksLikeEmail(email);
            repository.findByEmailIgnoreCase(email).ifPresent(holder -> {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Another person in this company already uses " + email);
            });
            changed.put("emailBefore", user.getEmail());
            changed.put("emailAfter", email);
            user.changeEmailTo(email);
        }
        if (displayName != null && !displayName.isBlank()
            && !displayName.equals(user.getDisplayName())) {
            changed.put("displayNameBefore", user.getDisplayName());
            changed.put("displayNameAfter", displayName);
            user.rename(displayName);
        }
        if (changed.isEmpty()) {
            return user;
        }
        AppUser updated = repository.saveAndFlush(user);
        audit.record("user.update", "user", updated.getId(), changed);
        return updated;
    }

    /**
     * The spreadsheet, with a dry run that reports exactly what it would do.
     *
     * <p>Every row is decided independently and reported independently. An import that stops at
     * the first bad address is an import a customer runs by bisecting their own file, and the
     * five hundredth row's error is the one they find last.
     */
    @Transactional
    public ImportReport importUsers(String csv, boolean dryRun) {
        List<CsvUsers.Row> rows;
        try {
            rows = CsvUsers.parse(csv);
        } catch (IllegalArgumentException unreadable) {
            // The file itself, not a row: there is nothing to report per row.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, unreadable.getMessage());
        }
        if (rows.size() > invitations.maxImportRows()) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                "This file has " + rows.size() + " rows; the limit is "
                + invitations.maxImportRows() + " per import.");
        }

        List<ImportRow> results = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int invited = 0;
        int reinvited = 0;
        int skipped = 0;
        int failed = 0;

        for (CsvUsers.Row row : rows) {
            String email = row.email();
            String key = email.toLowerCase(Locale.ROOT);
            try {
                requireLooksLikeEmail(email);
                if (row.displayName().isBlank()) {
                    throw new IllegalArgumentException("A display name is required");
                }
                if (!seen.add(key)) {
                    throw new IllegalArgumentException("This address appears earlier in the file");
                }

                Optional<AppUser> existing = repository.findByEmailIgnoreCase(email);
                if (existing.isPresent() && existing.get().getStatus() == UserStatus.ACTIVE) {
                    results.add(new ImportRow(row.line(), email, ImportAction.SKIP,
                        "Already has an account", null));
                    skipped++;
                    continue;
                }
                if (existing.isPresent() && existing.get().getStatus() == UserStatus.DEACTIVATED) {
                    // Never resurrected by a spreadsheet: coming back is a decision somebody
                    // makes about a named person, not a side effect of re-uploading a file.
                    results.add(new ImportRow(row.line(), email, ImportAction.SKIP,
                        "Deactivated; reactivate deliberately", null));
                    skipped++;
                    continue;
                }

                boolean again = existing.isPresent();
                if (dryRun) {
                    results.add(new ImportRow(row.line(), email,
                        again ? ImportAction.REINVITE : ImportAction.INVITE,
                        again ? "Would issue a new token" : "Would invite", null));
                } else {
                    Invitation invitation = offer(email, row.displayName());
                    results.add(new ImportRow(row.line(), email,
                        again ? ImportAction.REINVITE : ImportAction.INVITE,
                        again ? "New token issued" : "Invited", invitation.token()));
                }
                if (again) {
                    reinvited++;
                } else {
                    invited++;
                }
            } catch (IllegalArgumentException | ResponseStatusException rejected) {
                results.add(new ImportRow(row.line(), email, ImportAction.ERROR,
                    rejected instanceof ResponseStatusException status ? status.getReason()
                        : rejected.getMessage(), null));
                failed++;
            }
        }

        ImportReport report = new ImportReport(dryRun, invited, reinvited, skipped, failed, results);
        if (!dryRun) {
            audit.record("user.import", "user", null, Map.of(
                "invited", invited, "reinvited", reinvited,
                "skipped", skipped, "failed", failed));
        }
        return report;
    }

    /** The invitation itself, shared by the endpoint and the importer. */
    private Invitation offer(String email, String displayName) {
        requireLooksLikeEmail(email);
        Instant now = Instant.now();
        AppUser invitee = repository.findByEmailIgnoreCase(email).orElse(null);
        if (invitee != null && invitee.getStatus() == UserStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                email + " already has an account here.");
        }
        if (invitee != null && invitee.getStatus() == UserStatus.DEACTIVATED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                email + " is deactivated. Reactivate the account rather than inviting it again, "
                + "so the history stays attached to one person.");
        }
        if (invitee == null) {
            invitee = AppUser.invited(email, displayName);
        } else {
            invitee.rename(displayName);
        }

        String token = InvitationToken.mint();
        invitee.offerInvitation(InvitationToken.hash(token), now.plus(invitations.ttl()), now);
        AppUser saved = repository.saveAndFlush(invitee);
        return new Invitation(saved.getId(), saved.getEmail(), saved.getDisplayName(), token,
            saved.getInvitationExpiresAt());
    }

    private AppUser require(UUID userId) {
        // Tenant-filtered by the persistence layer: another company's person is simply not
        // found here, which is the 404-not-403 shape ADR-0102 promises (T-1.6 walks it).
        return repository.findById(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private static void requireLooksLikeEmail(String email) {
        if (email == null || !LOOKS_LIKE_EMAIL.matcher(email).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Not an email address: " + email);
        }
    }
}
