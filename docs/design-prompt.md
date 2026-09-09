# The design brief

**Task:** T-10.3 – T-10.6, and the design system that has to exist before any of them (T-10.1
deliberately left it out: "it arrives with the screens it has to serve").

This file is written to be **pasted whole into a design tool** alongside
[api-surface.md](api-surface.md). That one says what the API can do. This one says what the
product is, who is on the other side of the screen, and which of its rules a design gets wrong by
default.

---

## What this is

**XenOpsBase Learn** — enterprise training delivery. A company buys it, uploads its training,
assigns it to its people, and has to be able to prove afterwards who completed what.

Multi-tenant: every company is isolated at the database level, and **tenant is never a parameter**
in a URL. Nobody picks a company from a dropdown; you are in one because of who you signed in as.

The unit of content is a **course** → **modules** → an ordered list of **nodes**. A node is a
video, an uploaded SCORM package, a set of slides, or a test. A course is walked in order, and a
node may be **gated** — locked until something else is true.

## Who uses it

| | who | on what | how often |
|---|---|---|---|
| **Learner** | anyone at the customer, from a warehouse floor to a legal department | whatever device they have — assume a mid-range Android phone on a bad connection | a few times a year, under obligation |
| **Company admin** | HR, compliance, an L&D team of one | a desktop, several tabs, all day | daily |
| **Author** | a subject expert who is not a designer | a desktop | in bursts, then not for months |
| **Group admin** | a line manager, over their own people only | a desktop or a phone | occasionally |

The gap between the first row and the rest is the central design fact. **Thousands of reluctant,
occasional, mobile users** against **a handful of expert desktop users**. They share one
deployable, one session, one API client and one design system — but they must not share a visual
strategy where that strategy has to serve two different jobs.

Two route trees: `/` is the learner app, `/admin` is the console. The admin bundle is lazy — a
learner never downloads it.

## The four surfaces to design

### 1. The learner app (T-10.3) — the one the product is judged on

Home, course, item, progress, results.

**Home** answers three questions and nothing else: *what is due, what is in progress, what is
next.* It is the most-hit authenticated screen in the product and it is built on **one endpoint**,
so the design must not want data that would need a second call.

The **item view is one shell** hosting every content type — video, SCORM, slides, test. A new
content type must not mean a new screen. Design the shell, then show each type inside it.

**Resume** returns the learner to the exact second, from the server's record, not the browser's.

### 2. The admin console (T-10.4) — where the permission model lives or dies

Users, groups, roles, assignment, tenant settings.

**Role editing is the screen that decides whether the authorization design is usable.** A customer
builds a role by picking permissions. A flat list of a hundred `resource:action` codes is not a
screen anyone can use correctly. It needs grouping by resource, a plain-language sentence per
permission, and a **preview answering "what will someone with this role actually be able to see
and do"**.

Assignment flows for a person, a group, or the whole company — always showing **how many learners
this affects before it is confirmed**. Destructive actions state their consequence *and their
reach*.

A group admin sees their groups. Not a greyed-out view of everyone else's.

### 3. The authoring surface (T-10.5) — one model that must not look like two features

Courses, modules, ordering, gates, question banks, tests.

A test can sit **in the order between two nodes**, or be **pinned to a second inside a video**
(an *interstitial*). Underneath they are the same model. In the interface that either becomes
obvious or becomes two confusing features.

**An interstitial is placed on the video's own timeline, with a preview from that position — never
by typing a number into a field.**

Gate rules are built from choices, and the **learner-facing explanation is shown as it is being
built**, so the author sees the sentence a locked-out learner will read.

Ten question types, each authored with a live preview of exactly what the learner will see:
`single-choice`, `multiple-choice`, `true-false`, `matching`, `ordering`, `fill-in`, `numeric`,
`hotspot`, `essay`, `file-upload`.

Test assembly draws questions randomly from a pool by tag and difficulty. **Show how many
questions the pool actually matches, before saving** — a section asking for 20 from a pool of 12 is
a broken exam discovered by a learner.

Draft and published states visible at all times, so nobody edits a live course by accident.

### 4. The reporting surface (T-10.6) — not a table of everything

Compliance, transcripts, funnels, retention curves, test results, item analysis.

Compliance for five thousand learners **is not a table**. It is a small number of counts, a
filter, and a list you page through. Nothing loads a whole company; everything large is
cursor-paged.

The **retention curve** is the one chart here with real analytical value — where people stop
watching, across a long video, with markers where the interstitials sit. It deserves better than a
default line chart.

**Export is an async job**, not a button that freezes: it has progress, a clear finish, and a link
that expires. Item analysis has to explain, on the screen, what *difficulty* and *discrimination*
mean to the author reading it.

### And the player (T-10.7)

Published as its own package and embedded through an iframe — **including by our own screens**.
It must be designable independently of the app around it, and it carries the interstitial
interruption: the video stops at a pinned second and a question takes over.

---

## Eight rules a design gets wrong by default

These are not preferences. Each one is enforced somewhere in the backend, and a screen that
assumes otherwise is wrong rather than merely different.

1. **A 401 means the session ended — it is never "you may not".** It arrives as
   `problem+json` with `code: SESSION_ENDED`. Signing in again is a **full navigation**, so
   anything in memory is gone. Design the recovery: the exam case is why it exists — park the
   answers, sign in, replay. Never a redirect from a fetch, never a silent retry.

2. **A 404 may mean "not yours to know about".** The API deliberately refuses to say whether a
   thing exists. A screen that renders it as "this was deleted" is asserting what the API declined
   to. Say *not found or not available to you*, and mean it.

3. **`passed: null` is not `false`.** An attempt waiting on a human carries
   `grading: AWAITING_GRADING` and a null verdict. Drawing that as a fail locks a learner out of a
   course they may well have passed, and it is the single most damaging rendering mistake
   available here. It needs its own visual state, not a greyed-out version of one of the others.

4. **Locked always comes with a reason, and the reason is the design.** A gate returns a
   learner-facing sentence. A padlock icon with no sentence is a support ticket.

5. **Loading, failed and empty are three real states, and they already exist as components.**
   `Loading` is a polite live region. `ErrorState` is `role="alert"` and carries its own retry.
   `Empty` says what to do next — otherwise it is just a smaller failure. Design all three for
   every screen; they are not an afterthought.

6. **The clock on a test is the server's.** A timer in the browser is a *display* of a deadline it
   does not own, and it can be wrong. There is exactly one attempt in progress per learner per
   test; the same test opened in a second tab is the same attempt, not a new one. An unanswered
   question is an empty response, not an error — "I don't know" must be a state the learner can
   leave a question in.

7. **Integrity signals are disclosed, not hidden.** The product records things like leaving the
   tab during an exam. It is **not proctoring**: no camera, no microphone, no screen capture, no
   keystroke content. Every signal that can be collected carries its own learner-facing sentence,
   generated from the same source as the recorder, and it is handed to the client *before* the
   first question renders. The design has to make that impossible to miss — and equally has to
   avoid making a learner feel watched by a machine that is not watching them.

8. **What a learner may see after submitting is a policy, not a default.** Score only, or the
   questions back, or the answer key — immediately, or after all attempts, or after a date. The
   review screen is *constructed* from that policy, so it has several genuinely different shapes.
   Design them all; do not design the fullest one and hide parts of it.

---

## Constraints that are already decided

- **Static build, no server-side rendering.** Files served from the edge — playback must survive
  every one of our services being down.
- **One origin.** The browser holds an opaque session cookie and no token, ever. There is no
  account switcher, no API key in the UI, no "copy your token".
- **Every screen is behind authentication.** There is no marketing surface, no public course page,
  no SEO consideration. Do not design a landing page.
- **Accessibility is a conformance target with a build that fails on violations** (T-10.8).
  Keyboard operation for *every* interaction including the player, drag ordering, and hotspot
  questions. Focus management on route change and in modals. This is not decoration: this product
  delivers video and timed assessments, and for some learners those are the whole question of
  whether the training is possible at all.
- **All user-facing text is externalised from the first screen** — no string literals in
  components, enforced by lint. So: no text baked into images, room for strings that grow ~40%
  in German, and **a right-to-left layout that is proved to work, not assumed**.
- **Locale-aware dates, numbers and durations from one place.**
- **Timed assessments account for accommodations** — extra time as a policy, not a favour, and
  visible as such.
- **Offline and flaky-network behaviour is defined rather than accidental.** Decide what a
  half-watched video and a half-answered test do when the connection drops.

## What does not exist yet, and is therefore yours

There is **no design system**. What exists today is visible focus, landmarks and a skip link — the
structural parts, not the decorative ones. Colour, type, spacing, density, iconography, the
component set, the two densities (learner-mobile and admin-desktop) and the states above are all
open.

## What to deliver

1. **The system first** — colour with both themes resolved, a type scale, spacing, and the
   semantic states: locked, in progress, awaiting grading, passed, failed, overdue, draft,
   published. These carry more meaning in this product than any component does.
2. **Home**, on a phone, with something due, something in progress and something locked.
3. **The item shell**, showing a video with an interstitial firing.
4. **The role editor**, with its grouping, its plain-language descriptions and its preview.
5. **The interstitial timeline**, in authoring.
6. **A compliance view** for five thousand learners, and the retention curve.
7. **The review screen in at least two of its policy shapes**, one of them awaiting grading.

## What to avoid

The house style of this category: a slab of navy, a stock photo of people in a meeting room, a
progress ring on a rounded card, a dashboard of donut charts nobody reads. This is software people
are **required** to use — that is a reason to make it fast, legible and honest about where they
are, not a reason to make it corporate. Respect that the learner did not choose to be here, and
that the administrator will be in it for six hours.
