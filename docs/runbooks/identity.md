# Identity

## Re-linking a user after their `sub` changed

**When:** a token's `sub` matches no `app_user` row but its email matches an existing one. The
person sees a failed login; the log line names the conflict. Causes: the customer changed
identity provider (T-1.8), a realm was rebuilt without restoring Keycloak's database, or an
account was deleted and recreated in the IdP.

**What it is:** one UPDATE, matched on the verified email inside the tenant (ADR-0104). Every
foreign key in every service points at `app_user.id`, so nothing else moves — the one column is
the whole repair.

```sql
UPDATE app_user
   SET idp_sub = '<the new sub, from the failed token or the IdP admin console>'
 WHERE tenant_id = '<tenant>'
   AND lower(email) = lower('<email>')
   AND status <> 'DEACTIVATED';
```

The matching key is the **verified email within the tenant** — `(tenant_id, lower(email))` is
unique, so the statement can touch at most one row. Check exactly that before trusting the email:
the token's `email_verified` must be true, or the IdP must be one the tenant controls. An email
nobody verified is an email anybody can claim, and re-linking on it hands the account over.

Bulk variant (IdP migration for a whole tenant): the same statement per user, driven from the new
IdP's export, matched on the same key. Do not match on username — usernames are mutable and are
exactly what a migration tends to rewrite.

**This is a deliberate act, on purpose.** The service refuses to auto-relink on email match
(`UserProvisioningService`), because silent re-linking would make email ownership equivalent to
account takeover. If this runbook step feels frequent enough to automate, that is T-1.8's
admin-facing re-link flow — with the same verification — not a relaxation in provisioning.

## The realm and the people in it

The realm file (`local/keycloak/realm-xenopslearn.json`) declares the five development accounts
with pinned ids, so `make reset` restores the same subs (the stemcell's ADR-0010 mechanism).
Every real user is runtime-created and has no line there: a realm delete in any environment with
real users loses their subs, and recovery is the re-link procedure above, per user.

**T-1.7 landed the paths that keep that recovery unnecessary** — see
[keycloak-realm.md](keycloak-realm.md). Short version: change a realm with `make realm-apply`,
which never touches a user; `make realm-reset` is development-only and guarded three ways; and
`make realm-relink` is the procedure above, in bulk, dry-run by default. Run the repair **before**
the people sign in, or provisioning gives them a second account instead of an error.

## Impersonating a customer's user (T-2.8)

Support impersonation exists so an engineer can reproduce what one person is seeing. It is
read-only, time-boxed, and the customer can read the whole record afterwards.

**Before you can do it at all.** `support:impersonate` is on the `support` platform role and
nowhere else — deliberately not on `sys-admin`, which every configured platform administrator
holds from startup. Someone has to be given the role. Changing a customer's data during a
session needs a second permission, `support:impersonate_write`, which lives only on the
`support-write` role: a separate permission *and* a separate decision.

**Starting one.**

```
POST /api/v1/platform/impersonations
{"tenantId": "acme", "userId": "<app_user.id>", "reason": "ticket 4711, learner cannot open module 3"}
```

The reason is required, is at least `identity.impersonation.min-reason` characters, and is what
the customer reads. The response carries a session id; send it as `X-Impersonate-Session` on
every request that should run as that person. It is not a token — it works only for the engineer
who opened it, and only until it ends.

**What a session is and is not.** Inside one, the engineer holds the impersonated person's
permissions and *not* their own platform ones: they cannot provision or suspend a company while
wearing a customer's face. `/api/v1/me` answers as that person. Nothing provisions an account for
our staff inside the customer's company.

**Ending one.** `DELETE /api/v1/platform/impersonations/{id}`, or wait for
`identity.impersonation.max-duration` (30 minutes by default). There is no renew: a longer
investigation is a second session with its own reason. A session also closes itself if the
account it entered is suspended or deactivated underneath it.

**What the customer sees.** `GET /api/v1/impersonations`, held by the company-administrator
template by default: every session in their company, who opened it, why, whether it could write,
and when it ended. Every audited action taken during a session carries both identities —
`actor_user_id` is the engineer, `impersonated_user_id` is their user — so "I never did that" has
an answer, and so does "your staff did this".

**A suspended company cannot be entered at all**, and the refused attempt is audited into *that
company's* log rather than only ours.

## Setting up a customer's own SSO (T-1.8)

A company brings their OIDC or SAML provider; their people sign in with it and land in their
company and no other.

**The security property, first, because everything else is arrangement.** One realm holds every
company (ADR-0102), so the realm cannot answer "which company is this person in" the way a
realm-per-customer would. What answers it is *which provider authenticated them* — a fact about
our configuration. Every provider we write carries two hardcoded Keycloak attribute mappers,
`tenant_id` and `side`, whose values come from our own row, with `syncMode: FORCE` so they
re-apply on every login. A customer's provider can assert `tenant_id: someone-else` and it is
overwritten. **An alias belongs to one company for the life of the installation** — the primary
key on `tenant_identity_provider.alias` is what makes that true, not a check.

**Registering one.**

```
POST /api/v1/sso/providers
{"alias": "acme-okta", "kind": "OIDC", "displayName": "Acme Okta",
 "issuer": "https://acme.okta.com", "clientId": "...", "clientSecret": "..."}
```

Held by `sso:manage`, which the company-administrator template carries. The body has endpoints and
credentials and deliberately no mapper configuration: what a login *means* is not the customer's
to configure. The secret goes to the realm and is never stored here or read back.

If `identity.sso.realm-admin.*` is unset, the provider is recorded and **not** applied — the
response says `"applied": false` and the service WARNs at startup. That is the local default, so
`make up` still works without a Keycloak admin account. The service account those properties name
needs `manage-identity-providers` and **not** `realm-admin`: this service has no business editing
users in the realm.

**Home-provider discovery.** A learner types an address, the sign-in page asks:

```
POST /api/v1/auth/discovery      (no token — this is pre-login)
{"email": "someone@acme.com"}  →  {"provider": "acme-okta", "displayName": "Acme Okta"}
```

The only unauthenticated endpoint in this service. It answers on **exact verified domains** only:
no listing, no prefixes, and the same two fields whether or not there is an answer, so it is not
a yes/no oracle with a status code. It can still be asked one domain at a time — that is inherent
to home-realm discovery, and it is a rate-limiting problem (T-8.7) rather than something this
lookup can solve.

**Proving a domain.** Claim it, publish the record, ask again:

```
POST /api/v1/sso/domains            {"domain": "acme.com"}
  → {"dnsName": "_xenopslearn-verify.acme.com", "txtValue": "xenopslearn-verify=..."}
POST /api/v1/sso/domains/{id}/verify
```

Claiming blocks nothing — any number of companies may claim `acme.com`, which is what stops a
squatter reserving a competitor's domain before they sign up. **Only one can prove it**, and a
partial unique index over verified rows is the arbiter. The token is generated here, never
supplied by the claimant: a token the claimant picks proves they can publish a string they
already knew.

Locally, `identity.sso.domain-verification` is `trusting` because `acme.test` can never publish a
record. Everywhere else the default is `dns`, **including when the key is absent** — a
verification that trusts the claimant hands one customer another customer's sign-ins, so the safe
implementation is the one that runs when nobody chose. This is the opposite of how `streaming`
picks its fake media provider, on purpose.

**Returning users are not duplicated.** A federated sign-in is an ordinary first sight of a `sub`
(T-1.2): the `app_user` row is created on first login and found on every one after. Removing a
provider does not remove anybody's account — the account is ours and the credential was theirs
(ADR-0104).
