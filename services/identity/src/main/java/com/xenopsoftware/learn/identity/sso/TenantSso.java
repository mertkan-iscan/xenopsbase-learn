package com.xenopsoftware.learn.identity.sso;

import com.xenopsoftware.learn.common.tenancy.TenantContext;
import com.xenopsoftware.learn.identity.audit.AuditLogger;
import com.xenopsoftware.learn.identity.tenant.StatusGuard;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * A company's own identity provider, and the email domains that route people to it (T-1.8).
 *
 * <p>Two halves that have to be kept apart in the reader's head. <b>Configuration</b> is
 * tenant-scoped and ordinary: an administrator registers their provider and claims their domains,
 * inside their own company, gated by a permission. <b>Discovery</b> is global and unauthenticated:
 * it answers "which provider should this address go to" for a person who has not signed in yet
 * and belongs to nobody as far as we know.
 *
 * <p>Plain SQL naming its tenant where it must, for {@code EffectiveStatus}'s reason: discovery
 * has no tenant bound and could not have one — the answer is which tenant, and taking it from the
 * caller would be the question answering itself.
 */
@Service
public class TenantSso {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final RealmProviders realm;
    private final DomainOwnership ownership;
    private final AuditLogger audit;
    private final StatusGuard statusGuard;

    public TenantSso(DataSource dataSource, RealmProviders realm, DomainOwnership ownership,
            AuditLogger audit, StatusGuard statusGuard) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.realm = realm;
        this.ownership = ownership;
        this.audit = audit;
        this.statusGuard = statusGuard;
    }

    /** A claimed domain as its owner sees it, including what they must publish to prove it. */
    public record DomainView(UUID id, String domain, String record, String verificationToken,
                             Instant verifiedAt) {}

    /**
     * Registers or updates this company's provider, and applies it to the realm in the same
     * transaction it is recorded in.
     *
     * <p>The order is deliberate: the row first, then the realm, then the row again to record
     * that the realm agreed. A crash between them leaves a provider that exists here and not
     * there, which the API reports as unapplied — the other order would leave a realm nobody has
     * a record of, and a login landing in a company whose administrators never configured one.
     */
    @Transactional
    public TenantProvider register(String alias, ProviderKind kind, String displayName,
            RealmProviders.ProviderSecrets secrets) {
        statusGuard.requireWritable();
        String tenantId = TenantContext.require();
        if (!TenantProvider.ALIAS.matcher(alias).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "An alias is lowercase letters, digits and hyphens, 2 to 63 characters");
        }
        String owner = ownerOf(alias);
        if (owner != null && !owner.equals(tenantId)) {
            // The one refusal that is the whole security model. An alias belongs to one company
            // for the life of the installation, because "which provider signed you in" is only
            // an answer to "which company are you in" while that is true. 409 rather than 404:
            // an alias is a name the caller chose, not a row whose existence is a secret.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "That alias belongs to another company; choose another");
        }

        Instant now = Instant.now();
        jdbc.update("""
            INSERT INTO tenant_identity_provider (alias, tenant_id, kind, display_name, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (alias) DO UPDATE
               SET kind = excluded.kind, display_name = excluded.display_name, updated_at = excluded.updated_at
            """, alias, tenantId, kind.name(), displayName,
            java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));

        TenantProvider provider = new TenantProvider(alias, tenantId, kind, displayName, null);
        realm.apply(provider, secrets);
        Instant appliedAt = realm.reachesTheRealm() ? now : null;
        jdbc.update("UPDATE tenant_identity_provider SET applied_at = ? WHERE alias = ?",
            appliedAt == null ? null : java.sql.Timestamp.from(appliedAt), alias);

        // The secrets are not in the payload and never will be. An audit entry that records a
        // client secret is a place a client secret lives.
        audit.record("sso.provider.register", "identityProvider", null, Map.of(
            "alias", alias, "kind", kind.name(), "displayName", displayName,
            "appliedToRealm", String.valueOf(appliedAt != null)));
        return new TenantProvider(alias, tenantId, kind, displayName, appliedAt);
    }

    @Transactional
    public void unregister(String alias) {
        statusGuard.requireWritable();
        String tenantId = TenantContext.require();
        String owner = ownerOf(alias);
        if (owner == null || !owner.equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such provider");
        }
        realm.remove(alias);
        jdbc.update("DELETE FROM tenant_identity_provider WHERE alias = ?", alias);
        // People who signed in through it keep their app_user rows, which is the point of
        // ADR-0104: the account is ours and the credential was theirs.
        audit.record("sso.provider.remove", "identityProvider", null, Map.of("alias", alias));
    }

    public List<TenantProvider> providersOf(String tenantId) {
        return jdbc.query("""
            SELECT alias, tenant_id, kind, display_name, applied_at
              FROM tenant_identity_provider WHERE tenant_id = ? ORDER BY alias
            """, PROVIDER, tenantId);
    }

    /**
     * Claims an email domain for this company. Claiming is not owning: the row is created
     * unverified with a token, and discovery ignores it until a DNS record proves it.
     */
    @Transactional
    public DomainView claim(String rawDomain) {
        statusGuard.requireWritable();
        String tenantId = TenantContext.require();
        String domain = normalise(rawDomain);
        Optional<DomainView> existing = domainOf(tenantId, domain);
        if (existing.isPresent()) {
            return existing.get();
        }
        DomainView view = new DomainView(UUID.randomUUID(), domain, DnsDomainOwnership.RECORD,
            newToken(), null);
        jdbc.update("""
            INSERT INTO tenant_email_domain (id, tenant_id, domain, verification_token, created_at)
            VALUES (?, ?, ?, ?, now())
            """, view.id(), tenantId, domain, view.verificationToken());
        audit.record("sso.domain.claim", "emailDomain", view.id(), Map.of("domain", domain));
        return view;
    }

    /**
     * Checks the DNS record and, if it proves the claim, makes this company the domain's owner.
     *
     * <p>The unique index is what arbitrates a race between two companies claiming one domain,
     * and losing it is a 409 rather than a 500: two claims can coexist, two proofs cannot.
     */
    @Transactional
    public DomainView verify(UUID domainId) {
        statusGuard.requireWritable();
        String tenantId = TenantContext.require();
        DomainView claim = jdbc.query("""
            SELECT id, domain, verification_token, verified_at
              FROM tenant_email_domain WHERE tenant_id = ? AND id = ?
            """, rows -> rows.next() ? read(rows) : null, tenantId, domainId);
        if (claim == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such domain claim");
        }
        if (claim.verifiedAt() != null) {
            return claim;
        }
        if (!ownership.proves(claim.domain(), claim.verificationToken())) {
            audit.recordRefusal("sso.domain.unproved", "emailDomain", claim.id(),
                Map.of("domain", claim.domain(), "expected", claim.verificationToken()));
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "No TXT record at " + DnsDomainOwnership.RECORD + "." + claim.domain()
                + " carrying " + claim.verificationToken());
        }
        try {
            jdbc.update("UPDATE tenant_email_domain SET verified_at = now() WHERE id = ?", claim.id());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Another company has already proved ownership of " + claim.domain());
        }
        audit.record("sso.domain.verify", "emailDomain", claim.id(), Map.of("domain", claim.domain()));
        return new DomainView(claim.id(), claim.domain(), claim.record(), claim.verificationToken(),
            Instant.now());
    }

    public List<DomainView> domainsOf(String tenantId) {
        return jdbc.query("""
            SELECT id, domain, verification_token, verified_at
              FROM tenant_email_domain WHERE tenant_id = ? ORDER BY domain
            """, (rows, index) -> read(rows), tenantId);
    }

    /**
     * Home-provider discovery: which provider an address should be sent to, or none (T-1.8).
     *
     * <p><b>What this deliberately is not.</b> There is no endpoint that lists domains or
     * providers across companies, and no prefix or partial match — an exact verified domain, or
     * nothing. A public login page that could be asked "who are your customers" is both a leak
     * and, for the customer, a list of everyone they compete with.
     *
     * <p>What it cannot prevent is being asked one domain at a time, which is inherent to
     * home-realm discovery: anyone may ask whether {@code bigcorp.com} uses this platform, and
     * the honest answer is yes or no. That is a rate-limiting problem (T-8.7), not something this
     * lookup can solve by being cleverer.
     *
     * <p>Only VERIFIED domains answer. An unverified claim routing sign-ins would make claiming a
     * domain equivalent to owning it, which is the whole thing verification exists to prevent.
     *
     * <p>An UNAPPLIED provider still answers, deliberately. Sending a browser at an alias the
     * realm has never heard of is a broken redirect — but the configuration API already reports
     * that provider as unapplied and the service WARNs about it at startup, whereas discovery
     * quietly declining would make a developer's local stack answer "no provider here" and look
     * like a bug in discovery. The visible failure belongs where the cause is.
     */
    public Optional<TenantProvider> discover(String email) {
        if (email == null) {
            return Optional.empty();
        }
        int at = email.lastIndexOf('@');
        if (at <= 0 || at == email.length() - 1) {
            // at == 0 is "@acme.test", which is a domain somebody pasted rather than an address.
            // Answering it would turn this into a domain lookup with an @ in front, which is a
            // slightly more convenient version of the enumeration the shape here exists to keep
            // uninteresting.
            return Optional.empty();
        }
        String domain = normalise(email.substring(at + 1));
        List<TenantProvider> found = jdbc.query("""
            SELECT p.alias, p.tenant_id, p.kind, p.display_name, p.applied_at
              FROM tenant_email_domain d
              JOIN tenant_identity_provider p ON p.tenant_id = d.tenant_id
             WHERE lower(d.domain) = ? AND d.verified_at IS NOT NULL
             ORDER BY p.alias
            """, PROVIDER, domain);
        // A company with two providers has no single home, so discovery declines rather than
        // guessing: being sent to the wrong one of your own company's providers is a dead end a
        // learner cannot diagnose. They see the ordinary sign-in page and pick.
        return found.size() == 1 ? Optional.of(found.getFirst()) : Optional.empty();
    }

    private String ownerOf(String alias) {
        return jdbc.query("SELECT tenant_id FROM tenant_identity_provider WHERE alias = ?",
            rows -> rows.next() ? rows.getString(1) : null, alias);
    }

    private Optional<DomainView> domainOf(String tenantId, String domain) {
        return Optional.ofNullable(jdbc.query("""
            SELECT id, domain, verification_token, verified_at
              FROM tenant_email_domain WHERE tenant_id = ? AND domain = ?
            """, rows -> rows.next() ? read(rows) : null, tenantId, domain));
    }

    /** Lowercased and trimmed of the trailing dot a fully-qualified name may carry. */
    private static String normalise(String domain) {
        String normalised = domain == null ? "" : domain.strip().toLowerCase(Locale.ROOT);
        while (normalised.endsWith(".")) {
            normalised = normalised.substring(0, normalised.length() - 1);
        }
        if (normalised.isEmpty() || !normalised.contains(".")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not an email domain");
        }
        return normalised;
    }

    /** 192 bits, URL-safe. Generated here, never accepted from the claimant. */
    private static String newToken() {
        byte[] entropy = new byte[24];
        RANDOM.nextBytes(entropy);
        return "xenopslearn-verify=" + Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
    }

    private static DomainView read(java.sql.ResultSet rows) throws java.sql.SQLException {
        return new DomainView(rows.getObject("id", UUID.class), rows.getString("domain"),
            DnsDomainOwnership.RECORD, rows.getString("verification_token"),
            rows.getTimestamp("verified_at") == null ? null
                : rows.getTimestamp("verified_at").toInstant());
    }

    private static final RowMapper<TenantProvider> PROVIDER = (rows, index) -> new TenantProvider(
        rows.getString("alias"), rows.getString("tenant_id"),
        ProviderKind.valueOf(rows.getString("kind")), rows.getString("display_name"),
        rows.getTimestamp("applied_at") == null ? null : rows.getTimestamp("applied_at").toInstant());
}
