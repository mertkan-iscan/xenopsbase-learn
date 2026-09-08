# Runbook: the caches, and telling a missing one from a working one

**Task:** #126 · **Services:** all · **Endpoint:** `GET /management/health`

## The failure this exists for

On 2026-09-08 no service in `learn` could open a TCP connection to Valkey on the dev cluster. It
had been that way for five hours. Every signal said the pods were fine:

| Signal | Said | Why |
|---|---|---|
| `kubectl get pods` | `Running`, `Ready`, 0 restarts | the probes exclude the cache, by design |
| `/management/health/liveness` | `200` in 7ms | same |
| Argo | green | reads pod readiness |
| `/management/health` | `503` on two of four services | nobody reads the aggregate; the other two had the indicator off |

The cache failing open is correct. The probe groups excluding it is correct. **The combination
meant a dependency could be completely absent and every indicator stayed green** — and the
permission cache, whose whole purpose is that a revoked permission must not be served from a warm
cache, had never once been read on that cluster.

It was found by accident, while measuring something else.

## What it actually cost

Not a cache miss. An unreachable Valkey does not fail fast: Lettuce shares one connection and
callers queue behind one that can never be established. Two of the four callers read Valkey on the
path of **every request** and had no cooldown, so:

- `reporting` served **~1 request/second** at 20–60m of CPU against a 100m request;
- at 64 concurrent, the queue outlasted the liveness probe's deadline and the kubelet restarted
  the container (`Exit Code: 137`);
- the log carried one `permissive until Valkey returns` line **per request**, which is a log
  outage on top of a cache outage.

Every caller now goes through `DegradableCache`: one connection attempt per cooldown window
(`platform.cache.cooldown`, default 30s), then the path a miss would have taken.

## How to tell

```bash
curl -s localhost:8081/management/health | jq '.components.caches'
```

`caches` is **always UP** — a service built to survive a missing cache must not be taken out of
rotation for one. Read the details, not the status:

| `mode` | Means |
|---|---|
| `ok` | the cache is being used |
| `degraded` | it failed; nothing is consulting it until `retriesAt` |
| `absent` | this service has no Valkey configured at all |

`lastFailure` survives the window closing, deliberately: five minutes of `ok` following a
connection refusal reads differently from five minutes of nothing having happened.

Details are `when-authorized`, so an unauthenticated probe sees the status only.

## The caches

| Name | Service | Without it |
|---|---|---|
| `permissions` | identity | every permission set resolved from Postgres (T-2.5) |
| `tenant-status` | every service but identity | every account treated as ACTIVE; the owning module still refuses writes (T-1.4) |
| `home-screen` | catalog | every home screen assembled from Postgres (T-5.8) |
| `playback-mint-limit` | streaming | token minting is unbounded; the entitlement decision is unaffected (T-3.4) |

## What is still missing

`platform.cache.degraded{cache}` is registered — 1 while a cache is being skipped — and **nothing
can scrape it**. Every `SecurityConfiguration` in this repository permits only
`/management/health` and `/management/info`, so no meter here is reachable by Prometheus. That is
T-9.13 (#91)'s decision to make; until it lands, the health document above is the only signal, and
it has to be looked at rather than delivered.

The stock Redis indicator is **off in all five services**. It reports DOWN when Valkey is
unreachable, which is true and stops a rollout for a dependency the service is built to run
without — during a Valkey upgrade, which is when it is most likely to be moving. Two services used
to disagree, and those two were the ones whose 503 nobody read.

## If a cache is `degraded`

1. `kubectl -n cache get pods` — is Valkey running at all?
2. From a pod in the service's namespace, open a connection to
   `valkey-cache.cache.svc.cluster.local:6379`. **An immediate refusal is not "nothing is
   listening"** — k3s enforces NetworkPolicy with kube-router, which REJECTs rather than drops, so
   a namespace missing from `valkey-cache-allow-apps` looks exactly like a dead server. That was
   the 2026-09-08 cause, and it is why the probe worth running is the whole matrix: from each of
   `learn`, `apps`, `cache` and `observability`, against both 6379 and 9121. A policy working as
   written admits `apps` on 6379 only and `observability` on 9121 only.
3. `VALKEY_PASSWORD` empty where the server sets `requirepass` gives `NOAUTH` — a failure that
   arrives at the command rather than the connection, so it degrades the same way and refuses in
   microseconds instead of timing out.
