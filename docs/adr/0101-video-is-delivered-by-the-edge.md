# ADR-0101: Video is delivered by the edge, and the backend only signs for it

- **Status:** Accepted
- **Date:** 2026-08-28
- **Task:** T-0.1 (#1)

## Context

Learners watch video at times nobody scheduled. That is the fact this decision turns on: if video
bytes are served by our backend, the backend can never be down — and every deployment choice this
platform might later want is foreclosed before it is made. The one it is most likely to want is
already written down: xenopsbase-stemcell's ADR-0002 builds around a cluster that is torn down
and rebuilt on demand, and a platform whose learners depend on that cluster answering at 2am
cannot run on it.

The decision is made now, while there is no content library to migrate, because it is cheap now
and an archaeology project later.

## Decision criteria

- What has to be running for a learner to watch something
- How reversible the choice is once a library of content exists
- What a delivered hour actually costs, priced against current published rates rather than assumed
- Who operates the encoding ladder, the packaging and the captions, and whether that is a
  business we want

## The options, against the criteria

Rates read from Cloudflare's published pricing pages on **2026-08-28**, not remembered:
Stream is **$5 per 1,000 minutes stored per month** (prepaid, file size irrelevant) and
**$1 per 1,000 minutes delivered** (post-paid), with encoding and bandwidth included. R2 is
**$0.015/GB-month with free egress**.

**1. Serve HLS from our own services, out of our own object storage, behind our ingress.**
For a learner to watch: our ingress, our service, our storage, our cluster — all of it, at 2am,
forever. A delivered hour costs whatever our egress and compute cost, which on Hetzner-class
infrastructure is small — and is the least of it. This option converts every deployment into a
learner-visible risk, rules out ADR-0002's disposable-cluster model outright, and puts us in the
transcoding, packaging and captions business immediately. Cheapest per byte, most expensive per
decision.

**2. Cloudflare Stream.** For a learner to watch: Cloudflare's edge and a token our backend
signed *before* the watch began. A delivered hour costs **$0.06**; a stored hour costs
**$0.30/month**. Encoding ladder, packaging, captions storage and player delivery are operated
by the vendor. Our backend's only hot-path job is an entitlement decision and a signature —
which it can do stateless and cheap, and which tolerates the backend being down for everyone
already holding a token.

**3. Own transcode jobs writing an HLS ladder to R2, delivered by Cloudflare with signed URLs.**
For a learner to watch: Cloudflare's edge and our signature — same availability shape as
option 2. A stored hour (≈3GB across a ladder) costs **≈$0.045/month** and delivery is free, so
at scale it is roughly an order of magnitude cheaper per stored and delivered hour — paid for by
us operating transcoding compute, ladder tuning, packaging and captions. This is the escape
hatch: same edge, same token model, vendor's managed pipeline swapped for ours.

## Decision

**Option 2. Video is delivered by Cloudflare Stream's edge; the backend never carries a video
byte — it decides who may watch and mints a short signed playback token.**

And the part that keeps it a decision rather than a bet: **the delivery choice lives behind a
`MediaProvider` port** (T-3.1) — create an upload target, read asset status, mint a playback
token, delete. No vendor type appears in a signature; domain tables store an opaque provider
reference plus a discriminator; an ArchUnit rule keeps the vendor out of every package except
its adapter. **Option 3 is the named escape hatch this port exists for**: same edge, same
signing, an adapter swap rather than a migration — for exactly the day the pricing (someone
else's decision) stops being sound.

## Signed URLs are not DRM, in writing

A **signed token** is request-time access control: whether the edge will answer this fetch. It
expires in minutes, so a shared link dies quickly, and revoking entitlement stops the *next*
token. It does nothing against an authorized viewer capturing the stream. **DRM** (Widevine,
FairPlay) encrypts content and binds decryption to a device and license — it is what stops the
authorized viewer from keeping a copy, at real cost in players, licensing and support. This
platform ships signed tokens. Nobody may sell it as DRM, and a customer contract that requires
DRM is a new feature with its own ADR, not a configuration.

## Consequences

### What this makes easy

T-3.10's property — playback continues with every one of our services stopped — is achievable
and testable. Deployments, cluster rebuilds and the stemcell's cattle model stay on the table.
The encoding, packaging and captions business is somebody else's.

### What this makes hard

Local development has no faithful video path (`streaming` runs a fake provider; T-9.14's real
account is the only proof of edge delivery). Per-delivered-minute pricing is a marginal cost
that scales with success and is controlled by the vendor.

### What it commits us to

The property decays quietly — one convenience proxy endpoint serving bytes "just for this case"
and the backend is load-bearing again. T-3.10 asserts it on every build rather than believing
it. And the port discipline is permanent: the moment a provider identifier leaks into a
controller, a report or the player contract, option 3 stops being an adapter swap.

## Alternatives considered

Options 1 and 3 above. Option 1 rejected on the deployment consequence, stated explicitly: it
forecloses ephemeral infrastructure, scheduled maintenance without learner impact, and scale-to-
zero — an architecture chosen implicitly through a sequence of individually reasonable tickets,
each adding one small convenience to the byte path. Writing this down is what makes the next
such ticket visible as the reversal it is. Option 3 rejected *for now* on operating cost: it
buys a ~10x unit-price improvement by making us run a transcoding pipeline before there is a
library or a bill that justifies one. It is the designated successor, not a loser.

## Revisit if

- Delivered volume sustains above **2,000,000 minutes per month for three consecutive months**
  (a ≥ $2,000/month delivery line item) — at that volume option 3's unit economics plausibly
  pay for the pipeline it requires; re-price both against then-current rates.
- Stream's published delivery or storage price rises by **≥ 2×**, or per-minute storage prepay
  becomes a blocker for the content library's growth.
- A customer contract requires DRM — that is not this decision changing, but it reopens the
  delivery stack around it.
