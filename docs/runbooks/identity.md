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
real users loses their subs, and recovery is the re-link procedure above, per user. T-1.7 (#23)
owns proving that realm changes never need that recovery in the first place.
