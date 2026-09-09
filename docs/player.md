# The player

**Task:** T-3.5 · **Decision:** [ADR-0110](adr/0110-the-embeddable-player-is-an-iframe.md)

The web player, and the surface a customer embeds in their own page. One codebase serves both:
our learner app embeds the player exactly the way an integrator does, because a private in-process
variant is one that is never exercised by the people who would notice it break.

```
/watch/<node id>      the learner screen
/player.html?node=…   the player's own document, which the iframe loads
```

## What was decided, and why

### The embeddable surface is an iframe.

[ADR-0110](adr/0110-the-embeddable-player-is-an-iframe.md) has the reasoning. The short version:
the player holds a credential, this project already decided that code across a trust boundary runs
on a foreign origin ([ADR-0105](adr/0105-uploaded-packages-are-hostile-code.md)), and the mistake
is asymmetric — an iframe can later publish a module without breaking anybody, while a module
cannot become an iframe without breaking everybody at once.

The costs are real and named there: fullscreen needs an `allow` attribute, sizing is the loader's
job, and every method is asynchronous.

### The player mints its own token. The host never handles one.

A host page passes a **node id**, not a credential. The player calls
`POST /api/v1/me/nodes/{id}/playback-token` itself against the viewer's session (T-3.4), so no
playback token is ever in a `src` attribute, a browser history entry or a referrer header.

### Renewal is the mechanism, not an optimisation.

A playback token lives five minutes and **cannot be recalled from the edge** (ADR-0101). The
server bounds a suspension, a revoked assignment or a closed gate at one token lifetime *only if
the player comes back for the next token*. A player that fetched once and cached for the session
would make the five minutes decorative and quietly turn the revocation window into "the length of
the video".

So the loop is deliberate about three things:

- **It renews when the server says to** (`renewAfter`, three minutes), not when the token expires.
  A player that renews at expiry has already stalled, and the cadence stays a server decision
  changeable without shipping a browser release.
- **A failed renewal is not an error yet.** There are two minutes of valid playback left, so it
  retries quietly inside that window and says nothing. It surfaces a failure only once the token
  in hand has actually expired — because only then has the video stopped.
- **A refusal is terminal.** A revoked assignment or a closed gate answers the same way however
  many times it is asked; retrying it in a loop only spends the learner's mint rate limit.

The position survives renewal: a new token means a new manifest URL, so the element's
`currentTime` is captured before the swap and restored after the new manifest is *parsed* — not
after `loadSource`, which returns long before there is anything to seek within.

### The controls are the browser's, except the two that cannot be.

`<video controls>` gives play, pause, seek, volume, fullscreen and captions — keyboard operable,
labelled in the viewer's own language, understood by every screen reader, wired to platform media
keys. A custom control bar has to re-earn all of that and usually earns about half, which is how
"keyboard operable and screen-reader labelled" becomes a claim rather than a fact.

Quality and playback rate are ours because the native controls do not expose them: quality is an
hls.js concept the element knows nothing about, and rate is buried in a context menu on some
browsers and absent on others. Both are labelled `<select>`s.

Rate matters beyond convenience — it changes how much video passes per wall-clock second, so
progress accounting has to know it or a learner at 2× looks like one claiming twice the time they
spent. Reporting it is T-3.6's heartbeat; owning it is this task's.

### hls.js is imported dynamically, and it is the only chunk over budget.

At ~575kB it is larger than everything else in the application together, and most of what a
learner does involves no video. It is downloaded by somebody who presses play and by nobody else.
`vite.config.ts` keeps the 300kB budget and names this as the one expected exceedance; hls.js
appearing *inside* the entry chunk rather than beside it would mean a static import crept in.

### MSE first; native HLS only where there is no MSE.

The obvious order is wrong, and it cost a real bug. `canPlayType('application/vnd.apple.mpegurl')`
returns **`"maybe"` on Chromium**, which cannot play HLS natively at all — so a player that asks
`canPlayType` first and treats hls.js as the fallback hands Chrome a manifest it will not play,
silently: no error, no quality ladder, a video that never starts. That is what the first version
of this player did, and it was found by opening it in a real browser rather than by any test.

So the question asked first is whether `MediaSource` exists, which is the capability hls.js
actually needs. Native HLS is the fallback for browsers with no MSE — iOS Safari, essentially —
where it is genuinely the better path anyway: hardware decoding, lower power, working AirPlay.
Those browsers also never download hls.js, which is why the check asks `MediaSource` directly
instead of importing the library to ask `Hls.isSupported()`.

Two tests stub the browser's answers in both directions, because jsdom returns `""` from
`canPlayType` and has no `MediaSource` — so left to itself it never takes the branch that was
broken.

## Embedding it

```html
<div id="video"></div>
<script type="module">
  import { embedPlayer } from '…/embed.js';

  const player = embedPlayer({
    into: document.querySelector('#video'),
    origin: 'https://player.example',
    nodeId: '8f1c…',
    title: 'Fire safety, part 1',
  });

  player.on('progress', ({ seconds, duration }) => …);
  player.on('error', ({ message, terminal }) => …);
  player.seek(120);
</script>
```

`src/player/messages.ts` is the whole contract — commands in, events out, and `PLAYER_PROTOCOL`
so a host can tell what it is talking to. T-10.7 publishes it as a semver'd package; until then
the version there is what changes when the contract does.

Two properties the loader holds so an integrator does not have to think about them: the frame
carries `allow="fullscreen; picture-in-picture; autoplay; encrypted-media"`, and it holds a 16:9
aspect ratio until the player reports the real one.

Every message is filtered on **both** origin and channel id. Origin makes a stranger's message
untrusted; the channel id is what makes another of *our own* players on the same page not ours.

### Progress is read from the server, never computed here (T-3.7).

Where to resume, how much has been watched, whether it is complete, whether this item allows
skipping ahead, and where an unanswered interstitial is holding the learner (T-5.4) all arrive from `GET /api/v1/me/nodes/{id}/progress` and from the answer to every
progress post. **None of it is derived in the browser.** A player that kept its own idea of
completion would be a second answer to the question a compliance report answers, and the two would
disagree the first time a heartbeat was lost — which is exactly the disagreement
[ADR-0107](adr/0107-completion-is-derived-by-the-server.md) exists to prevent.

Three consequences worth stating, because each of them looks like a bug from the outside:

- **The resume position can arrive after the video does.** The token request and the progress
  request are made together and either can answer first. The player seeks once, whichever order
  they land in, and never afterwards — a learner who scrolls back after resuming has not asked to
  be moved again.
- **Seeking forward is stopped by a listener, not by hiding the scrubber.** Native controls are the
  accessible ones, and a scrubber that will not go past what has been watched serves a learner
  better than no scrubber. The server enforces the same rule from its side by refusing to credit
  coverage that could only have come from a skip, so a modified client gains nothing but a refusal.
- **Progress lags by up to one flush.** Completion is derived on the server from a batch posted
  every ten seconds, so a learner who finishes and immediately looks for their certificate may wait
  that long. The UI says what it knows rather than guessing ahead of it.

### An interstitial is a frontier, and the pause is the visible half of it (T-5.4).

An author can pin a question inside a video's timeline. A blocking one stops playback until it is
answered — and **the stop is arithmetic, not a pause**. `blockedAfterSecond` arrives on the same
progress reads as everything else, and the server does not credit coverage past it. A player with
the pause code deleted plays to the end and the item still does not complete, because the seconds
past the marker were never counted.

That is what makes this rule different from seek-forward, which is enforced on both sides. There is
nothing to enforce on this side: the player renders the question because that is the only way the
learner can move the frontier, not because the rule depends on it.

Three consequences, again because each one looks like a bug from the outside:

- **A heartbeat that straddles the marker is accepted and trimmed.** `[290, 301)` against a marker
  at 300 is what a correct player produces — the heartbeat window will never align with the marker
  — so it is credited to 300 and answered `200`. A batch *wholly* past the marker is a `409`
  `INTERSTITIAL_UNANSWERED`, which is the player reporting playback from beyond a frontier it was
  handed.
- **`resumeSecond` never goes past the frontier.** A learner who reloads mid-interruption returns
  to the question rather than past it, even though their furthest second is beyond it. Resuming
  them where they got to would hand them a video that credits them nothing, with no sign of why.
- **Answering it is not something the player can assert.** The answer is an assessment attempt, and
  the frontier moves when catalog hears about it. There is no request a browser can make that says
  "I answered that" — the same reason there is no request that says "I completed that"
  ([ADR-0107](adr/0107-completion-is-derived-by-the-server.md)).

### The batch goes to two services, and that is on purpose (T-3.6, T-3.7).

The same samples are posted to `reporting` (raw, append-only, droppable at ninety days) and to
`streaming` (merged into the coverage completion is derived from). One extra request per learner
per ten seconds buys the property [`reporting-inputs.md`](reporting-inputs.md) states as a rule:
**progress recording completes with `reporting` stopped.** Deriving completion from rows in the
analytics store would break that in one commit, invisibly — reports would keep rendering, with
fewer completions in them.

## What is deliberately not here yet

- **Captions and multiple audio tracks.** T-3.9 (#42) produces them. A hard-coded `<track>`
  pointing at nothing would announce captions that do not exist, so the caption list is empty
  until an asset has one.
- **A published package.** T-10.7 (#98), including the example page built in CI so it cannot rot.

## What cannot be proved locally

**Adaptive playback, the quality ladder, and anything about the picture.** Video is the one
dependency with no local equivalent ([local-stack.md](local-stack.md)): the fake provider mints
manifest URLs on `fake-media.invalid`, a domain reserved never to resolve. A player wired against
it exercises every path except the one that matters — it requests, it fails, and it shows the
error it would show if the edge were down.

jsdom cannot close that gap either: it has no Media Source Extensions, so `Hls.isSupported()` is
false and the real player correctly renders "this browser cannot play this video". The component
tests stub hls.js and assert everything around the picture — the controls, the labels, the
refusals — and claim nothing about decoding.

T-9.14's real Cloudflare account is the only thing that can prove edge delivery.

## Verified, on 2026-09-05 (T-3.7)

Against the real local stack, `streaming`, `identity` and `reporting` all running, as
`acme-learner`:

- `GET` and `POST /api/v1/me/nodes/<uuid>/progress` both answered **404 with an empty body** for a
  node nobody has, and `playback_refusal` recorded `UNKNOWN_NODE` against the caller's
  `app_user.id` — the same disclosure rule the playback token follows (T-3.4), and proof that the
  hop to `identity` resolved the person before anything was written.
- A batch with no playback token answered **400 `MISSING_ATTRIBUTION`**, before any of that: the
  shape of a batch is checked before the request is allowed to cost a lookup.

What still cannot be checked locally is a *credited* interval, for the same reason T-3.4's
entitlement decision cannot: `UnassignedContent` refuses every node because no catalog adapter
exists yet, so nothing is assigned to anybody. The merge, the thresholds, the rate check and the
seek rule are covered by `ProgressTest` against a real Postgres instead.

## Verified, on 2026-09-04

Against the real local stack, `streaming` and `identity` both running:

- `POST /api/v1/me/nodes/<uuid>/playback-token` as `acme-learner` answered **404 with an empty
  body**, and `playback_refusal` recorded `NO_PERMISSION` against the caller's `app_user.id` —
  the caller told nothing, the audit keeping the reason (T-3.4's disclosure rule), and the
  service-to-service hop to `identity` (T-9.11) resolving the person.
- Granting the seeded **Learner** role — which now carries `content:view`, re-projected by
  `SystemRoleSeeder` exactly as T-2.7 promised — moved the refusal to `UNKNOWN_NODE`. The
  permission check passed and the next link refused, which is `UnassignedContent` doing its job:
  catalog does not exist, so nothing is assigned to anybody.

Both grants were removed afterwards. To repeat it, assign the tenant's `Learner` role to the
`app_user` the token actually resolves to and flush the permission cache:

```sql
INSERT INTO role_assignment (id, tenant_id, role_id, user_id, scope_type, granted_by, created_at)
SELECT gen_random_uuid(), 'acme', r.id, u.id, 'TENANT', u.id, now()
  FROM app_role r, app_user u
 WHERE r.tenant_id = 'acme' AND r.name = 'Learner'
   AND u.tenant_id = 'acme' AND u.email = '<the resolved address>';
```

**Check which `app_user` the token resolves to rather than assuming.** In this stack the Keycloak
user `acme-learner` resolves to `newhire@acme.test`, not `acme-learner@acme.test` — the realm
link drift T-1.7 warns about, from a realm rebuilt without declared ids. `GET /api/v1/me` reports
the row that counts.
