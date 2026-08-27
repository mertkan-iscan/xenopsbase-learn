# Architecture decision records

An ADR records a choice that constrains future work, together with the reasoning that made it
the right choice at the time. The reasoning is the valuable part: anyone can read the code and
see what was decided; only an ADR says why the alternatives lost, and therefore what would have
to change for the decision to be revisited.

The conventions are [xenopsbase-stemcell's](https://github.com/mertkan-iscan/xenopsbase-stemcell/blob/main/docs/adr/README.md),
including the one that matters most: **ADRs are append-only.** An accepted ADR is never edited to
say something different. When a decision changes, a new ADR supersedes it, with a link in both
directions.

## Numbering starts at 0101

The stemcell's ADRs are numbered 0001–0015 and this backlog cites them constantly — ADR-0002 for
the disposable cluster, ADR-0008 for durable state, ADR-0010 for user identity. Numbering this
repository's decisions from 0101 keeps every citation unambiguous: a two-digit ADR is the
stemcell's, a four-digit one starting with 01 is ours.

The planned set maps to the E0 tasks:

| ADR | Decision | Task |
|---|---|---|
| 0101 | Video is delivered by the edge, and the backend only signs for it | T-0.1 |
| 0102 | A company is a row, not a realm | T-0.2 |
| 0103 | Authorization is business logic, and it does not live in Keycloak | T-0.3 |
| 0104 | User identity is ours, and `sub` is only a link | T-0.4 |
| 0105 | Uploaded packages are hostile code and run on a foreign origin | T-0.5 |
| 0106 | A question version is immutable once it has been served | T-0.6 |
| 0107 | Completion is derived by the server, never reported by the client | T-0.7 |
| 0108 | Telemetry stays in Postgres until a measurement says otherwise | T-0.8 |
| 0109 | Eight modules, and how many processes to run them in | T-0.9 |
