# XenOpsBase Learn

A multi-tenant video, slides and SCORM training platform. Grows on top of
[xenopsbase-stemcell](https://github.com/mertkan-iscan/xenopsbase-stemcell), which supplies the
plumbing — identity, persistence, object storage, observability, GitOps, disaster recovery — and no
business logic. Everything in this repository is the business logic.

Planning board: [XenOpsBase Learn (project #7)](https://github.com/users/mertkan-iscan/projects/7)

## Decisions

| Decision | Choice |
|---|---|
| Video delivery | Cloudflare Stream. The cluster mints a short signed playback token and never carries a byte |
| Delivery seam | A `MediaProvider` port, so own-transcode-into-R2 stays an adapter swap |
| Packaged content | SCORM 1.2 and 2004 run-time, cmi5, served from a separate content origin over `postMessage` |
| Tenancy | One Keycloak realm; a company is a row; `tenant_id` is a mapped claim, never a header |
| Identity | `app_user.id` is ours; the Keycloak `sub` is a nullable link that can be repaired |
| Authorization | Permission catalog in code, roles and assignments as runtime data, scoped and version-cached |
| Progress | Derived server-side from watched intervals. The client never reports completion |
| Assessments | Immutable question versions, materialised per-attempt forms, scaled scores |
| Telemetry | Postgres, partitioned by day, rolled up. A column store needs a measurement first |
| Topology | Four deployables: `gateway`, `core`, `media-worker`, `analytics` |

## The constraint that drives the design

ADR-0002 in the stemcell makes the cluster cattle: torn down and rebuilt on demand, near-zero cost
while down. An LMS breaks that assumption in a way nothing in the stemcell does — **learners watch
video at times nobody scheduled.**

If video bytes are served by the cluster, the cluster can never be down, and the property the whole
platform was built around is gone on the first day of production. So every design here is checked
against one question: *does this force the cluster to stay up?* Where the answer is yes, the
component moves outside it or gets bought.

| Outside the cluster — survives `make down` | Inside — disposable |
|---|---|
| Video masters and renditions (Cloudflare Stream) | Playback tokens, permission cache, rate limits (Valkey) |
| SCORM packages and slide images (R2, separate origin) | Event transport — the outbox row is the record |
| Learners, progress, attempts, grades (Postgres → WAL archive) | Every pod, every PVC |
| Raw telemetry, partitioned, 90 days | Rollup jobs mid-run |

That table is why `T-3.10` exists: playback continuing through a `make down` is the only thing that
proves the delivery architecture, and it is the property most likely to decay quietly.

## Topology

Two deployables are inherited. Two are added, and each one is argued against ADR-0001's bar —
a materially different scaling profile, a different availability requirement, a different owning
team, or a conflicting technology.

| Service | Why it exists separately |
|---|---|
| `gateway` | Inherited. OIDC, session, token relay, rate limiting, tenant status gate |
| `core` | Inherited. Tenancy, users, authorization, catalog, structure, assignments, assessments, SCORM run-time |
| `media-worker` | **Scaling profile.** Package extraction and PDF rasterisation are bursty CPU and disk against a request path that is otherwise cheap |
| `analytics` | **Scaling profile and availability.** Write rate scales with concurrent learners, not users — and analytics being down must never stop a video playing |

Everything else is a module inside `core`. ADR-0001 is explicit that boundaries drawn before the
domain is understood are drawn along the wrong lines, and a service per domain concept is how that
happens.

## Epics

| Label | Epic |
|---|---|
| `E0-foundations` | Decisions, as ADRs |
| `E1-tenancy` | Tenants, identity, groups, blocking |
| `E2-authz` | Dynamic permissions, roles, scopes |
| `E3-media` | Video delivery, tokens, player, progress |
| `E4-content` | SCORM, cmi5, slides, packaging |
| `E5-structure` | Courses, modules, gates, assignment |
| `E6-assessment` | Question banks, tests, attempts, grading |
| `E7-analytics` | Telemetry, rollups, reports, exports |
| `E8-api` | Public API, webhooks, SDKs |
| `E9-platform` | Fork, deployables, edge, CI |

## Phases

Milestones, ordered so each is provable on its own, and so the two decisions that are expensive to
reverse — tenancy and identity — land before there is data to migrate.

| Phase | Done means |
|---|---|
| **P0** Fork and seams | A request authenticated as tenant B gets 404 on every tenant A resource, and suspending a tenant blocks the next request |
| **P1** Video end to end | A learner resumes at the right second, completion is derived from coverage, and playback survives a `make down` mid-video |
| **P2** Structure and assignment | A group admin assigns a course, and a learner sees a locked module unlock when its prerequisite completes |
| **P3** Assessments | Two learners sit different randomly-assembled papers, both graded correctly, and editing a question afterwards changes neither result |
| **P4** Packaged content | A third-party SCORM package runs, reports completion, and provably cannot reach the app origin or its session |
| **P5** Reporting | A company admin exports compliance for 5,000 learners without touching a request timeout |
| **P6** Open the platform | An integrator provisions a user, assigns a video and reads its completion without a call the product UI does not also make |

## Critical path

```
T-9.1 -> T-9.2 -> T-9.3 -> T-1.1 -> T-1.2 -> T-2.1 -> T-2.4 -> T-3.1 -> T-3.4 -> T-3.7 -> T-3.10
```

The repository is its own, identity is ours rather than borrowed, the tenant boundary exists and is
tested, permissions are evaluated, and then video is delivered, gated, measured — and proved to keep
playing when the cluster does not.

## Upstream

The stemcell is under active development and this fork has no version pin on it, which is
[T-9.4](../../issues/12). Issues in the stemcell that block work here carry `blocked-upstream` on
the dependent issue and sit on the same board, so a cross-repository blocker is visible in one
place rather than remembered.

## Conventions

Inherited from the stemcell and not re-litigated: task-numbered issue titles that state the problem
rather than the solution, epic labels, ADRs for decisions that constrain future work, no manual
configuration, and every claim in a document either measured or marked as unmeasured.
