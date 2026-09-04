# XenOpsBase Learn

A multi-tenant video, slides and SCORM training platform, built as microservices.

Planning board: [XenOpsBase Learn (project #7)](https://github.com/users/mertkan-iscan/projects/7)

## Where this runs

**Locally, for now.** [xenopsbase-stemcell](https://github.com/mertkan-iscan/xenopsbase-stemcell)
is the intended production infrastructure — cluster, GitOps, identity, backups, observability — and
it is not finished. Rather than wait for it, this repository develops against a local stack:

```bash
cp .env.example .env
make up
```

Postgres, Keycloak with the realm imported from this repository, MinIO for object storage, NATS,
Valkey, and a separate content origin. See [docs/local-stack.md](docs/local-stack.md) — what is
faithful to production and what is not. **Video is the one dependency with no local equivalent**,
so `streaming` runs against a fake provider by default, and a green local build never proves edge
delivery works.

That is a deliberate ordering, not a workaround. The domain — tenancy, permissions, delivery,
assessment, reporting — is where the value and the risk are, and none of it needs a cluster to be
designed, built or tested. Deployment is a later concern with its own tasks, and the fork happens
when the stemcell is ready to be forked into rather than on a schedule this project sets.

What the stemcell *does* supply already is reasoning. Its ADRs on service topology, durable state,
identity and evidence-before-scaling are referenced throughout this backlog, and several decisions
here exist because that repository documented the failure first.

## Modules and processes

Eight modules. **How many of them are separate processes is a different question**, and it is
decided in [ADR-0109](../../issues/86) against measured capacity rather than by preference.

| Module | Owns | Separate process at dev sizing? |
|---|---|---|
| `gateway` | Edge routing, sign-in, session, rate limiting, tenant status gate | yes — exists |
| `frontend` | Learner app, admin console, authoring, embeddable player | yes — a static build served from the edge costs the cluster nothing |
| `streaming` | Video assets, upload targets, encode state, playback tokens | yes — the learner hot path |
| `packaging` | SCORM and cmi5 archive extraction, manifest parsing, slide rasterisation. No video — that is Cloudflare Stream | yes — it runs untrusted uploaded code and must not share a heap with a session |
| `reporting` | Telemetry ingest, rollups, reports, exports | yes — must fail without stopping playback |
| `identity` | Tenants, users, groups, roles, permissions | proposed: starts inside `core` |
| `catalog` | Content items, courses, modules, gates, assignments | proposed: starts inside `core` |
| `assessment` | Banks, questions, tests, forms, attempts, grading | proposed: starts inside `core` |

Why the split is not simply eight, measured rather than estimated: a Spring Boot process idles at
~600Mi (`core` 605Mi, `gateway` 533Mi actual), and on identical sizing the platform underneath
leaves about 4.7GB for application services. Eight processes need 4696Mi of that. Six need 3486Mi.

Two traps are worth naming, because the first version of this calculation fell into both.
**Kubernetes schedules on requests; the node dies on usage** — cluster-wide requests totalled
7766Mi against 11217Mi actually used, so any headroom figure derived from manifests is ~2.6GB too
optimistic. And **a capacity reading taken once is a snapshot**: free memory moved 810Mi across
three readings in an hour on an idle cluster, all of it one Argo CD pod. The figure recorded is the
worst of the three.

Two rules keep the deferral honest rather than a retreat. **No module reads another module's
schema** — separate schemas, separate migrations, enforced by credentials where the boundary is a
process and by an ArchUnit rule where it is not. And **every cross-module call goes through a
published interface**, so extracting one later is a deployment change and a client swap rather than
a rewrite.

The estimates above are arithmetic over declared configuration. [T-9.15](../../issues/101) replaces
them with measurements.

## Decisions

| Decision | Choice |
|---|---|
| Video delivery | Cloudflare Stream. The backend mints a short signed playback token and never carries a byte |
| Delivery seam | A `MediaProvider` port, so own-transcode-into-R2 stays an adapter swap |
| Packaged content | SCORM 1.2 and 2004 run-time, cmi5, served from a separate content origin over `postMessage` |
| Tenancy | One Keycloak realm; a company is a row; the tenant is a verified claim, never a header |
| Identity | `app_user.id` is ours; the Keycloak `sub` is a nullable link that can be repaired |
| Authorization | Permission catalog in code, roles and assignments as runtime data, scoped and version-cached |
| Progress | Derived server-side from watched intervals. The client never reports completion |
| Assessments | Immutable question versions, materialised per-attempt forms, scaled scores |
| Telemetry | Postgres, partitioned by day, rolled up. A column store needs a measurement first |
| Events | Transactional outbox into a message bus. At-least-once, so every consumer is idempotent |

## The constraint that shapes delivery

Learners watch video at times nobody scheduled. If video bytes are served by our backend, the
backend can never be down — which forecloses every deployment choice this platform might later
want, including the disposable-cluster model the stemcell is built around.

So video and packaged content are delivered from the edge, and the backend only decides *who may
watch* and signs a short-lived token for it. [T-3.10](../../issues/43) is the test that keeps it
that way: playback continues with every one of our services stopped. It is a property that decays
quietly — one convenience proxy endpoint and it is gone — so it is asserted on every build rather
than believed.

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
| `E9-platform` | Service runtime, local stack, transport, tracing |
| `E10-frontend` | Learner app, admin console, authoring, player |

## Phases

| Phase | Done means |
|---|---|
| **P0** Foundations and seams | A request authenticated as tenant B gets 404 on every tenant A resource, and suspending a tenant blocks the next request |
| **P1** Video end to end | A learner resumes at the right second, completion is derived from coverage, and playback survives every service being stopped |
| **P2** Structure and assignment | A group admin assigns a course, and a learner sees a locked module unlock when its prerequisite completes |
| **P3** Assessments | Two learners sit different randomly-assembled papers, both graded correctly, and editing a question afterwards changes neither result |
| **P4** Packaged content | A third-party SCORM package runs, reports completion, and provably cannot reach the app origin or its session |
| **P5** Reporting | A company admin exports compliance for 5,000 learners without touching a request timeout |
| **P6** Open the platform | An integrator provisions a user, assigns a video and reads its completion without a call the product UI does not also make |

## Critical path

```
T-0.9 -> T-9.9 -> T-9.10 -> T-1.1 -> T-1.2 -> T-2.1 -> T-2.4 -> T-3.1 -> T-3.4 -> T-3.7 -> T-3.10
```

The service map is decided, the stack runs locally, services share one shape, the tenant boundary
exists and is tested, identity is ours, permissions are evaluated — and then video is delivered,
gated, measured, and proved to keep playing when nothing of ours is running.

`T-10.1` runs alongside from the start: the frontend is a service like the others and its
foundational decisions are as expensive to defer.

## Conventions

Task-numbered issue titles that state the problem rather than the solution. Epic labels. ADRs for
decisions that constrain future work. No manual configuration. Every claim in a document either
measured or marked as unmeasured.

**An issue closes when every acceptance box is genuinely met, and not before.** Work regularly
lands with one box that belongs to a task further down the dependency graph — a check with nothing
yet to check against, a metric nothing can yet scrape, a CI step with no CI. Those issues get a
comment saying what landed and what is still owed, and they stay open.

### The board columns, and the one that is not obvious

| Column | Means |
|---|---|
| Backlog | Not started |
| Ready | Next, and unblocked |
| In progress | **Somebody is working on it right now** |
| In review | **Landed and pushed, open only on a criterion another task owns** |
| Done | Closed, every box met |

The split between the last two exists because they were one column and it stopped meaning
anything: fifteen issues sat in *In progress* and nobody was working on any of them. With more
than one person — or agent — in the repository at once, "what is being touched right now" is the
question that stops two people editing the same files, and a column that also holds finished work
cannot answer it.

**Closing an issue does not move the card.** The Status field is separate and drifts unless it is
set, so setting it is part of finishing a task, not an afterthought.

### When something lands, re-check what it unblocked

An issue parked on *In review* is waiting for a named task, and nothing notices when that task
arrives. T-1.4 (#20) sat open for a day after T-3.4 (#37) satisfied its last criterion, because
the person who landed T-3.4 had no reason to look at T-1.4.

So: after landing X, search the open issues for X and close or advance whatever X just satisfied.
It is a one-command check and it is the only thing keeping *In review* from becoming the new place
work goes to be forgotten.
