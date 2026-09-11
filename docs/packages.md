# Uploaded packages, and the runtime behind them

**Tasks:** T-4.1 · T-4.2 · T-4.3 · T-4.4 ·
**Decisions:** [ADR-0105](adr/0105-uploaded-packages-are-hostile-code.md) ·
[ADR-0107](adr/0107-completion-is-derived-by-the-server.md)

A course exported from an authoring tool arrives as a ZIP, is unpacked on this platform's terms
and is served from the tenant's own **content origin**. This document is about what happens after
that: the run-time data model, what the platform reads out of it, the limits it enforces, and the
small API an HTML5 bundle can use when it has no standard to speak.

```
packaging            /api/v1/uploads/…       reserve, upload, process   (T-4.1, T-4.2)
                     /api/v1/me/runtime/…    read and save a learner's place   (T-4.4)
<tenant>.<origin>    /packages/<id>/…        the wrapper, and the package's own files (T-4.3)
```

## Four kinds, and what each one is trusted to say

| Kind | Profile | The API it finds | How completion is decided |
| --- | --- | --- | --- |
| `scorm` | `scorm-1.2`, `scorm-2004` | `API`, `API_1484_11` | what the package reports |
| `cmi5` | `cmi5` | both of the above | what the package reports |
| `html5` | `html5` | `window.xenopslearn` | what the bundle reports, or "it was opened" |
| `slides` | *(none)* | nothing | it was opened |

Content with a standard is held to it. Content without one is credited for being opened, because
that is the entire evidence available — and the alternatives are worse in both directions:
recording nothing leaves a gate nobody can ever satisfy, and inventing a measurement claims to
know something about content whose interior is an opaque iframe on another origin.

Every completion from any of these is recorded as **`SELF_REPORTED`** and every report says so.
That is the distinction that matters, and it is the one ADR-0107 draws.

## The run-time data model (T-4.4)

### The whole CMI map is stored, and three facts are lifted out of it

`package_runtime` holds one `jsonb` document per **(learner, package, node)**, given back verbatim
on the next launch. Beside it are the only three things the platform acts on:

- **`completed`** — a ratchet. It goes false → true and never back, because a conformant package
  reports `incomplete` when a learner reopens a finished course, and storing that as written would
  revoke a compliance record because somebody clicked into a course in March.
- **`passed`** — nullable, and **null is not false**. A course with no test neither passed nor
  failed anybody. `unknown` in SCORM 2004 means exactly this and reads as null.
- **`score_raw`** — on the package's own scale. **Nothing compares it to anything**: `score.min`
  and `score.max` are optional and usually absent, and deriving a percentage without them is how a
  course marked out of 20 reports 17%.

`completed_at` is set once and never moved. The date is the thing an auditor reads.

### Every vocabulary is read, whatever the manifest declared

`cmi.core.lesson_status` (1.2), `cmi.completion_status` + `cmi.success_status` (2004) and the
HTML5 API's writes are all asked for on every save. A manifest's declared profile is not always
the truth about the JavaScript inside it — a 2004-declared export built from a 1.2 template writes
the 1.2 names, and reading strictly by the declaration records nothing at all for it.

**`failed` counts as complete in SCORM 1.2, and that is not a bug.** The 1.2 field answers two
questions at once. Somebody who sat the package's test and failed it has finished the material;
whether they *passed* is the other column, and treating a fail as unfinished would send them back
through a course they completed to re-fail a test.

### Session time and total time are different numbers

`session_time` is what the package says **this launch** has lasted. `total_time` is every session
added together, it is **read-only**, and the LMS is the one that answers it — a package cannot
know what happened in the sessions before this one.

`session_time` is cumulative *within* a launch: a package commits at slide three saying twelve
minutes and at slide six saying thirty, meaning thirty rather than forty-two. So a launch records
the total it started at and every save recomputes `total = base + session`. Adding each report to
a running total inflates a learner's recorded time by roughly the number of times the package
committed — and the more conscientious the package, the worse the error.

Beside both is `seconds_spent`, which the browser measures. It is corroboration and never the
decision (ADR-0107), and it is what still moves when a package reports no time at all.

An HTML5 bundle has no notion of a session time, so the wrapper fills one in from the wall clock —
but only when the content has written none of its own. A SCORM package's account of its own
session always stands.

### Limits, and what happens at each one

| Limit | Value | Refusal |
| --- | --- | --- |
| Elements in one save | 2,000 | `413` |
| Characters in one element | 65,536 | `SetValue` → `false`; `413` if it reaches the server |
| Element name | 255 characters | `413` |
| Time one save may add | 4 hours | clamped, not refused |
| Saves per registration | 20 per minute | `429` |

**65,536 characters is the `suspend_data` ceiling**, and it clears both standards' floors: SCORM
1.2 requires an LMS to accept 4,096 and SCORM 2004 raises that to 64,000. Both are minimums rather
than ceilings, so the number is chosen rather than read off.

**It is never truncated.** A suspend blob is an opaque serialised object graph; half of one does
not deserialise, so a package handed a truncated blob does not resume at slide thirty — it fails to
start on the next launch, with nothing naming the cause. The wrapper refuses the write at
`SetValue` with the standard's own code (`405` in 1.2, `407` in 2004) so the package hears about it
in its own vocabulary at the moment it wrote it, and the server refuses it again because a browser
is not a boundary.

### Two tabs: the most recent launch owns the registration

Opening a package mints a session id. Every save quotes it, and a save from a superseded launch is
refused with **`409`** — the window is told, stops committing, and says so on screen.

This is a rule rather than optimistic locking because the two writers are not racing on a field:
they hold entire divergent data models — different suspend blobs, different interaction arrays —
and there is no merge of them that means anything to the package that wrote them. One of them has
to lose, so the design chooses which and tells it. The failure it replaces was silent: a learner
who worked through twenty slides and then hit Refresh had their progress replaced by the empty map
the second tab started with, with nothing anywhere recording that it happened.

A row written before this rule existed carries no session id and accepts any save. A deploy must
not be the moment a learner mid-course loses their place.

### A commit storm is normal traffic, not abuse

A conformant package calls `Commit` at every slide boundary and some call it on a timer measured in
seconds. A thousand learners in a course that commits every three seconds is over three hundred
writes a second against one table, each replacing a `jsonb` document that can be sixty kilobytes.

Three things keep that survivable, and the third is what makes the first two safe:

1. The wrapper **coalesces** to at most one save every five seconds, always leaving a trailing
   timer behind so nothing is dropped, and always forcing on `Terminate` and `pagehide`.
2. The server keeps a **budget** of twenty saves per registration per minute
   (`packaging.runtime.saves-per-window`), in Valkey, failing open — a cache outage must not stop a
   learner's progress being saved.
3. **Every commit carries the whole data model.** `Commit` means "this is the state", never "this
   is what changed", which is why the endpoint is a `PUT`. A save that is folded, refused or
   dropped loses nothing the next one will not carry again, and `Terminate` forces a commit — so
   the last one always lands, and the last one is the one that counts.

`CommitStormTest` asserts the shape rather than a throughput figure: five hundred commits from one
learner leave one row, one completion event, and the last commit's data model.

### The error model

Both API objects are exposed to any SCORM or cmi5 package, whatever its manifest declared, because
a package that finds neither stops at its first line with no message anybody will see. **The error
codes belong to the object the package called**, not to the declared profile: a 2004-declared
export built from a 1.2 template calls `LMSInitialize` twice and must hear 1.2's `101`, not 2004's
`103`, because `103` means nothing in the vocabulary it is checking against.

`GetErrorString` returns the standard's own text from a real table. A course that logs `Error 407`
has told nobody anything.

Before-initialize and after-terminate are **different** errors in 2004 (`122`/`123`, `132`/`133`,
`142`/`143`) and a conformance suite checks both. Read-only elements refuse with `403` (1.2) /
`404` (2004); write-only elements (`exit`, `session_time`) refuse a read with `404` / `405`.

An element the package has not written and the runtime has no default for answers `""` with error
`0` — which is what almost every LMS does and what packages are written against. Answering `401`
makes conformance-checking packages abort a course that would otherwise have run perfectly.

## The API an HTML5 bundle can use

An HTML5 bundle has no contract with an LMS, so it is handed no `API` object — a discovery walk
that found one it never asked for could not tell it was being lied to. It gets this instead, on
`window.xenopslearn`, and **every call is optional**: a bundle that calls none of them is still a
valid item, and the platform records that it was opened and nothing more.

```js
// Inside the bundle, on the content origin. `window.parent.xenopslearn` from a nested frame.
const lms = window.parent.xenopslearn ?? window.xenopslearn;

lms.version;                       // 1 — feature-detect against this
lms.complete();                    // finished. Idempotent: twice reports one completion
lms.score(7, true);                // a raw score on your own scale, and pass / fail / omitted
lms.save('chapter-3', blob);       // where they are, and anything you want back verbatim
                                   //   → false if `blob` was over 65,536 characters
const { location, suspendData, completed } = lms.resume();   // what `save` last stored
```

It writes into the **SCORM 2004 element names**, deliberately: the server already reads those in
three vocabularies, and a fourth would mean a second derivation to keep in step with the first. A
report cannot tell — and does not need to tell — whether a completion was asserted by a conformant
SCORM package or by four lines in somebody's `index.html`. Both are `SELF_REPORTED`.

The object is frozen. A bundle cannot replace a method with one that reports something else.

### What a bundle does not get

No credential, no cookie, and no reach into the application. The wrapper it runs inside is on the
tenant's content origin and holds nothing; it posts the data model to the application over
`postMessage`, and the application — which has the learner's session — makes the call. Both ends
name the other origin **exactly**, by string equality, because
`acme.usercontent.example.com.evil.test` ends with something useful to an attacker and equals
nothing.

That relay runs in the learner's own browser, and this is not a weakness the design introduced: a
conformant LMS believes what a package says, and the package runs on the learner's machine either
way. What the platform owes in return is that the resulting completion is recorded as self-reported
and every report says so.
