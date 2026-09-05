# Cloudflare: the accounts this product needs (T-9.14)

The external accounts the **product** depends on, as opposed to the ones a cluster will need. The
split matters: infrastructure accounts (Hetzner, Tailscale, Terraform state) wait for the
stemcell; these do not, because `streaming` cannot be finished against a fake provider and the
content origin needs a real bucket and a real hostname.

Everything here is **development**. A development bucket, a development zone, development
credentials. Nothing shares a credential with anything the stemcell will later provision, and
nothing here is a step toward production.

## What each credential can do, and who holds it

Fill in the holder and date when you create each one. A credential nobody is named against is a
credential nobody rotates.

| Credential | Env var | What it can do | Held by | Created |
|---|---|---|---|---|
| Stream API token | `CF_STREAM_API_TOKEN` | Create, read and delete Stream assets in one account. Mint signing keys. **Cannot** touch DNS, R2, or any other account. | | |
| Stream signing key (private JWK) | `CF_STREAM_SIGNING_KEY_JWK` | Sign playback tokens locally, offline. Anyone holding it can mint a playback token for any asset in the account. | | |
| Stream signing key id | `CF_STREAM_SIGNING_KEY_ID` | Names which key a token was signed with. Not a secret. | | |
| Stream webhook secret | `CF_STREAM_WEBHOOK_SECRET` | Verify inbound encode notifications. Anyone holding it can forge one. | | |
| R2 access key | (T-9.5) | Read and write objects in the content bucket. | | |
| Account id | `CF_STREAM_ACCOUNT_ID` | Not a secret; it is in every API URL. | | |
| Delivery host | `CF_STREAM_CUSTOMER_SUBDOMAIN` | Not a secret; it is in every manifest URL a learner fetches. | | |

**The two that are worth treating differently.** The signing key's private JWK is the sharpest
thing in the table: playback tokens are minted with it *locally, with no network call* (ADR-0101),
which means nothing at Cloudflare's end can observe or revoke a token minted by somebody who
copied it. It is also returned **only once**, at creation — Cloudflare does not store it, so
losing it means minting a new key, and leaking it means minting a new key and revoking the old.

The webhook secret is the other one: it is what makes an inbound "your encode finished"
notification believable. Blank is a supported and safe configuration — nothing is trusted, and
the reconciler (T-3.3) polls instead. A *wrong* one is also safe, for the same reason. Only a
leaked one is dangerous.

## Setting it up

### 1. Stream, and a token scoped to it

Enable Stream on the account, then create an API token with **exactly one** permission:

> Account → **Stream** → **Edit**, scoped to this account only.

Not `Edit zone DNS`, not `Account Settings: Read`, and not the "Read all resources" template. The
adapter makes four calls and no others: create a direct-upload target, read an asset, delete an
asset, and manage signing keys. A token that can do more is a token whose blast radius is larger
than its job for no benefit.

**Then prove it, rather than reading the permissions page back:**

```bash
source ~/.xenopsbase-learn.env
bash scripts/cloudflare-check.sh
```

Credentials live in `~/.xenopsbase-learn.env`, **outside this repository**, copied from
`local/env.example` and filled in there. `.gitignore` lists `.env`, but a gitignore is a rule
somebody defeats without meaning to -- a `git add -f`, a rename no pattern covers, an editor's
backup file -- and several agents work in this checkout at once. A secret outside the working
tree cannot be committed by accident, and that is a property of where it lives rather than of
anybody remembering. `make env` says which of them the current shell has, by name and never by
value. Nothing sources the file for you: `make` guarding rather than reading it is what keeps
`make` and your shell from disagreeing about what is set.

This is the acceptance criterion, not a convenience. `Stream:Read` where `Stream:Edit` was meant
looks correct in the dashboard, passes every GET anybody tries by hand, and fails on the first
real upload — weeks later, in an environment nobody is watching, reading as a bug in the upload
path. The script makes each call for real, deletes what it creates, and reports a 403 separately
from every other failure so a scope problem is never confused with a malformed request.

It also derives `CF_STREAM_CUSTOMER_SUBDOMAIN` by reading an asset back, because that value fails
only at **playback** — after an upload has already succeeded.

**Stream is a paid feature, and enabling it allocates nothing.** A fresh account reports
`allocated 0 minutes` and the first upload fails with code `10011` — which arrives as an HTTP 413
and reads like a broken token if nothing names it. It is the opposite: authorization runs *before*
the quota check, so reaching that error proves the token carries `Stream:Edit`. Buy minutes
(Cloudflare dashboard → **Stream** → subscribe) and re-run the script to confirm the create path
end to end.

### 2. The signing key

Mint one, once, and keep what comes back:

```bash
curl -X POST "https://api.cloudflare.com/client/v4/accounts/$CF_STREAM_ACCOUNT_ID/stream/keys" \
     -H "Authorization: Bearer $CF_STREAM_API_TOKEN"
```

`result.id` → `CF_STREAM_SIGNING_KEY_ID`, `result.jwk` → `CF_STREAM_SIGNING_KEY_JWK`. **The JWK is
in that response and nowhere else, ever.**

### 3. The content origin hostname

The rule is one line and it is the reason this is an issue rather than a chore:

> **The content hostname must not be a subdomain of any application hostname.**

A cookie set with `Domain=example.com` is sent to `content.example.com`. So
`content.learn.example.com` beside an app at `learn.example.com` is not a second origin in the
sense that matters — it is the same cookie jar with a different path, and every uploaded SCORM
package served from it is third-party JavaScript inside the application's session (ADR-0105).

Use a **separate registrable domain**, not a subdomain. The local stack models this with ports
(`:8090` against the app's `:8080`), which a browser treats as a different origin, so the
postMessage bridge is exercised in development rather than first met in production.

### 4. Spend visibility, before any bulk upload

Stream bills on stored minutes and delivered minutes, and both are variable in a way a test
harness can move fast. Set a notification on the account before the first bulk upload, not after
the first surprise:

> Cloudflare dashboard → **Notifications** → **Add** → *Billing usage* → set a threshold.

### 5. Switching the platform onto it

```bash
export MEDIA_PROVIDER=cloudflare-stream
```

Without it the stack runs `FakeMediaProvider`, which is the point: **a developer with none of
these credentials still gets a working stack.** The fake is main code, not test code, and it warns
loudly at startup so a green run against it is never mistaken for evidence about the edge.

## What is deliberately not here

Object storage for uploads and exports (T-7.8) is S3-compatible and MinIO is faithful to it, so
there is nothing that cannot be tested locally and no credential needed until deployment.
