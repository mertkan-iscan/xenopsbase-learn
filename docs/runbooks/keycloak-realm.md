# The Keycloak realm

Three paths, and picking the wrong one deletes every account in the realm. Which is safe depends
on one question only: **does this Keycloak have people in it who are not in
`local/keycloak/realm-xenopslearn.json`?**

| | Command | Touches users | Use where |
|---|---|---|---|
| Change the realm | `make realm-apply` | **no** | anywhere, including production |
| Rebuild the realm | `make realm-reset` | **deletes all of them** | your machine, and nowhere else |
| Repair the links | `make realm-relink` | our database, not Keycloak | after a rebuild handed out new subjects |
| See what is live | `make realm-export` | no | before changing either side |

## Why there is a hazard at all

Keycloak's declarative `--import-realm` only imports a realm that does **not** exist. That leaves
an obvious-looking procedure for changing one that does — delete it, let the import recreate it —
and against real customers that procedure destroys every account while reading as routine
maintenance. It is the exact shape of failure worth designing out early: a step that was safe when
it was written and stopped being safe without anyone editing it.

It is survivable, because ADR-0104 keeps ownership in our own `app_user.id` and stores the Keycloak
subject as one nullable link. Survivable is not the same as fine.

## Changing the realm: `make realm-apply`

Applies the file in this repository to a running Keycloak in two steps that never touch a user:
the realm's own settings by `PUT /admin/realms/{realm}`, then clients, roles and groups by
`partialImport` with `ifResourceExists=OVERWRITE`. If the realm does not exist yet it imports the
whole file, which is safe precisely because there is nobody in it to lose.

**Users are excluded deliberately and always.** The file's five users are development fixtures. A
real installation's people arrive by signing in, and an `OVERWRITE` of a user is their password
and attributes replaced by whatever this repository happened to say.

This is the path a pipeline would run on merge. There is no pipeline yet (T-9.3 was dropped, see
T-1.6 (#22)), so today it is run by hand — from this file, against a realm nobody edits in the
console.

## Rebuilding the realm: `make realm-reset`, development only

Deletes the realm and everything in it, then imports the file. **Three guards**, because it reads
as routine:

1. the Keycloak has to be on this machine (`localhost`/`127.0.0.1`);
2. the realm must contain nobody this repository does not declare;
3. `CONFIRM=destroy-<realm>` has to name the realm.

Guard 2 is the one that matters. It tells a real installation from a development one without
asking anybody to remember which is which: one account nobody wrote into the file means people
live here, and the script refuses.

Because every declared user carries an explicit `id`, a rebuild gives them back **the same
subjects** and nothing needs repairing. `LocalRealmTest` fails the build if a user is ever added
without one.

## Recovering: `make realm-relink`

For when a realm came back with new subjects anyway — a rebuild from a file without declared ids,
a restore that lost Keycloak's database, an identity provider migration.

Matched on email within the tenant, exactly like the manual procedure in
[identity.md](identity.md), with the same guards: never a deactivated account, never a subject
another row already holds. **Dry run by default**; `APPLY=1` executes, as one transaction.

**Run it before the people do.** Provisioning creates an `app_user` on first sight of an unknown
subject, so somebody who signs in after the rebuild and before the repair gets a *second account*
— silently, if their address in the product is not the address the realm knows them by. That is
not hypothetical; it happened during the rehearsal below.

## The rehearsal (2026-08-31)

Run end to end on the local stack, against a database that already had people, groups, role
assignments and audit entries in it.

- **The safe path is safe.** `realm-apply` against the existing realm: settings applied, 5
  resources overwritten and 2 added, and all five subjects unchanged afterwards.
- **Guard 1 and 3 refuse**, with the alternative named in the message.
- **Guard 2 refuses.** One account created through the Admin API that the file does not declare,
  and the reset stopped: *"realm 'xenopslearn' contains accounts this repository does not
  declare: real.customer"*.
- **A rebuild with declared ids costs nothing.** `make realm-reset` deleted and re-imported the
  realm; every subject came back identical, and the person who had signed in before it still
  resolved to the same `app_user.id`.
- **A rebuild without declared ids is the failure this exists to prevent.** Re-imported from a
  copy with the `id` fields stripped: five new subjects. The next sign-in did **not** produce an
  error — it produced a **second account**, because that person's address in the product had been
  changed and so matched nothing. An error would have been the kinder outcome.
- **The repair works.** `realm-relink` reported the plan, `APPLY=1` ran it, and the administrator
  came back to the same `app_user.id` with their role assignment and four audited actions still
  attached — no second account.
- **The repair found two bugs in itself, which is what a rehearsal is for.** The plan proposed
  handing one person a subject another row already held (the duplicate above), the whole
  transaction rolled back — correctly — and the script printed success anyway. Both are fixed:
  the planner now reports `CONFLICT` and leaves that decision to a human, and a failed transaction
  is reported as a failure with nothing changed.

## If you have to do it by hand

`make token U=acme-admin` and the Admin API on `http://localhost:8081`; admin credentials are in
`.env` (`admin`/`admin` locally). Everything the scripts do is plain REST, and each script says in
its header which endpoint it uses and why.
