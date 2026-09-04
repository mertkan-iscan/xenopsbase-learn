# Runbook: telemetry ingest

**Task:** T-3.6 · **Service:** `reporting` · **Endpoint:** `POST /api/v1/telemetry/playback`

## The failure this exists for

Losing a heartbeat is acceptable. **Losing all of them silently is not** — that is a compliance
report filling with zeros while every dashboard stays green, discovered months later by a customer
asking why nobody in their company has completed anything.

The shape of that failure is the reason the alert below is not "ingest rate is zero". A zero
ingest rate is perfectly normal at 3am, and an alert that fires every night is one nobody reads.
What is never normal is **nobody posting heartbeats while people are demonstrably watching**, and
the platform already knows the second half of that: `streaming` mints a playback token per viewer
per five minutes (T-3.4), so tokens being issued is the ground truth for "sessions are open".

So the alert compares two services' counters. It is the only formulation that distinguishes "the
product is quiet" from "the product is broken".

## Metrics

Exported by `reporting`:

| Meter | Type | What it answers |
|---|---|---|
| `telemetry.playback.batches.accepted` | counter | Is ingest happening at all |
| `telemetry.playback.samples.accepted` | counter | How much, in samples rather than requests |
| `telemetry.playback.batches.rejected{reason}` | counter | What is being refused, and why |
| `telemetry.playback.lag` | summary (p50/p95/p99) | How far behind the samples arriving are |

Rejections are tagged by reason rather than summed, because the reasons call for different
responses. A spike in `MALFORMED_INTERVAL` is a player release. A spike in `BATCH_TOO_LARGE` is a
network outage somewhere refilling client buffers. One number would show both as "rejections up".

Lag is measured **from the oldest sample in the batch**, not the newest. The newest is always
about ten seconds old by construction, and measuring from it would report a healthy number during
exactly the incident this metric exists to show: clients draining a backlog after an outage.

## The alert

```yaml
groups:
  - name: telemetry-ingest
    rules:
      - alert: PlaybackHeartbeatsStopped
        # Tokens are being minted, so people are watching. Nothing is arriving, so
        # nothing is being recorded. Neither half is an incident alone.
        expr: |
          sum(rate(telemetry_playback_batches_accepted_total[10m])) == 0
          and
          sum(rate(playback_tokens_minted_total[10m])) > 0
        for: 15m
        labels:
          severity: page
        annotations:
          summary: Playback is happening and no telemetry is arriving
          runbook: docs/runbooks/telemetry-ingest.md

      - alert: PlaybackHeartbeatsMostlyRejected
        expr: |
          sum(rate(telemetry_playback_batches_rejected_total[10m]))
            / clamp_min(sum(rate(telemetry_playback_batches_accepted_total[10m]))
                        + sum(rate(telemetry_playback_batches_rejected_total[10m])), 1)
            > 0.05
        for: 10m
        labels:
          severity: ticket
        annotations:
          summary: More than 5% of heartbeat batches are being refused
          runbook: docs/runbooks/telemetry-ingest.md
```

`for: 15m` rather than something tighter because the comparison is between two ten-minute rates
across two services, and a deploy of either can briefly produce the pattern legitimately.

## Two things this cannot do yet, and they are related

**`playback_tokens_minted_total` does not exist.** T-3.4 mints the tokens and counts nothing;
adding the meter is a small change and belongs with whoever wires the scrape, because a counter
nobody can read is not worth adding on its own.

**Nothing scrapes any of this.** `SecurityConfiguration` in every service permits `/management/health`
and `/management/info` and denies the rest, so `/management/metrics` and `/management/prometheus`
are not reachable. Every meter described above is registered and correct and **no monitoring system
can see it**.

That is a deliberate decision belonging to **T-9.13 (#91)**, which owns observability, and it is
now blocking a second task — T-2.5 hit the same wall with the permission cache's meters. The rule
this file describes is written down so that the day the scrape exists it is a configuration change
rather than a design conversation.

Until then the honest statement is: the metrics are produced, the alert is specified, and the
alert is not running.

## When it fires

1. **Is anybody actually watching?** Check that playback tokens are being minted at all. If they
   are not, the alert is wrong or `streaming` is down, and this is not the incident.
2. **Is the endpoint reachable from a browser?** The player posts from the learner's own browser
   to `reporting` directly. A gateway route or CORS change breaks this without touching
   `reporting` at all, and the service's own logs will be silent — which looks identical to
   nobody watching.
3. **Are batches being refused rather than lost?** Check
   `telemetry_playback_batches_rejected_total` by reason. Rejections mean requests arrive and are
   being turned away, which is a different problem with a different fix — usually a player release
   sending something the server does not accept.
4. **Is the database accepting writes?** Ingest is a single batched insert; if it fails, requests
   error rather than being silently dropped, and the error rate will show it.
5. **Accept the loss.** Heartbeats already dropped are gone: the client buffers ten minutes and
   retries a failed post once (T-3.6), then lets it go. Coverage for the affected window will be
   under-reported and no backfill exists. Say so rather than quietly recomputing something that
   looks complete.
