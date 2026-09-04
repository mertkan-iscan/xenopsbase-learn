# ADR-0110: The embeddable player is an iframe, not a JavaScript module

- **Status:** Accepted
- **Date:** 2026-09-04
- **Task:** T-3.5

## Context

The player ships in two places from one codebase: our own learner app, and a customer's page. The
second one is what makes this a decision rather than a component.

Three constraints narrow the field before any option is named.

**The player will hold a credential.** It mints playback tokens (T-3.4) and will post heartbeats
(T-3.6). A token is short-lived and bound to an asset, but it is still a bearer credential, and
whatever holds it is inside whatever security boundary contains it.

**This project already decided the general case.** [ADR-0105](0105-uploaded-packages-are-hostile-code.md)
puts a customer's uploaded package on a foreign origin because code we did not write must not have
our DOM, our session or our tokens. An embedded player is the same relationship pointing the other
way: *our* code running in a page *they* wrote. The symmetry is not decorative — a host page can
read any global, monkey-patch `fetch`, walk the DOM and read anything a script in that document can
reach.

**The interface becomes a contract the day somebody embeds it.** T-10.7 makes that explicit with a
semver'd package, and whatever this decides is what that package publishes. Choosing the wider
surface now means the wider surface is what we are held to.

What is *not* a constraint, and is worth saying because it usually decides this question: bundle
size. The player is lazily loaded in our own app either way, and a customer embedding a video
player is not counting our kilobytes against a budget they have for their own code.

## Decision criteria

Written before comparing, in the order they break ties:

1. **Containment, both directions.** Can the host page reach our tokens and DOM? Can our CSS and
   globals damage theirs?
2. **Size of the contract.** How much surface are we promising not to break?
3. **What a customer can build.** Does the integration support the things an integrator will
   actually ask for — sizing, events, programmatic control?
4. **Cost of being wrong.** Which mistake is cheaper to undo?

## Decision

**The embeddable player is an iframe on its own origin, with a documented `postMessage`
interface.** The host page includes a small loader script; the loader creates the iframe, sizes it,
and translates between `postMessage` and an ordinary object with methods and events. The player
itself never runs in the host's document.

Our own learner app embeds it the same way. There is no privileged in-process path that only we
get, because a private variant is one that is never exercised by the people who would notice it
break — the same reasoning T-10.7 states as "the application consumes the published package, not a
private copy".

## Consequences

### What this makes easy

Containment is structural rather than careful. The host cannot read a playback token, cannot reach
the player's DOM, and cannot see the heartbeat traffic; our styles cannot leak into their page and
theirs cannot break our controls. T-10.7's "works in a host page whose CSS and globals we do not
control, and does not leak its own into the host" is satisfied by the boundary rather than by
discipline.

The contract shrinks to a message schema — a named set of commands in, a named set of events out.
That is a surface we can version honestly, and it is small enough to write down completely.

It composes with the content origin the project already runs. `docker-compose.yml` has a separate
content origin for ADR-0105's packages, and the player is served the same way.

### What this makes hard

**Fullscreen, Picture-in-Picture and autoplay need explicit permission from the host**
(`allow="fullscreen; picture-in-picture; autoplay; encrypted-media"`). An integrator who omits the
attribute gets a player whose fullscreen button does nothing, and the documentation has to lead
with that rather than mention it.

**Sizing is the host's job and it is genuinely annoying.** An iframe has no intrinsic aspect ratio,
so either the loader maintains one from a `resize` message or the host wraps it. The loader does
it, and that is a large share of the loader's reason to exist.

**Focus and keyboard handling cross a boundary.** Keyboard operation inside the player works
normally, but the host cannot delegate its own shortcuts inward without going through the message
interface, and a focus trap in the host page can make the player unreachable — which is an
accessibility failure in *their* page that will be reported against ours.

**Every integration is asynchronous.** `player.seek(120)` cannot return a value; it posts a message
and an event comes back. The interface has to be designed that way from the start rather than
discovering it at the first method that wants a return.

### What it commits us to

An origin to serve the player from, forever, and its cache and release story — the iframe URL is
in customers' HTML and cannot be moved without their edit.

Reversal is not symmetric, which is the reason for choosing this direction. **Iframe → module** is
additive: the module is a new distribution of the same player with the same message names, and
existing embeds keep working. **Module → iframe** is a breaking change for every integrator at
once, because everything they could reach synchronously stops being reachable.

So the cheaper mistake is to start contained. If the containment turns out to cost more than it
buys — and the honest candidates are fullscreen friction and the asynchronous interface, not
security — a module can be published alongside without breaking anybody.
