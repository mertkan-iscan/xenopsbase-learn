# web

The learner app and the admin console (T-10.1). The decisions — static build, one application
with two route trees, one origin, a generated API client — are in
[docs/frontend.md](../docs/frontend.md), with the reasons.

```bash
cp .env.example .env.local     # paste a token from `make token U=acme-admin`
npm install
npm run dev                    # http://localhost:5173, proxying /api to identity
```

| Command | What it does |
|---|---|
| `npm run verify` | lint, typecheck and build, tests — what a pipeline would run |
| `npm run api:generate` | refresh the checked-in OpenAPI description and its types from a running service |
| `npm run api:check` | fail if the client has drifted from the running service |

`npm run api:*` need `identity` running (`make up`, then `make run`). They fail rather than skip
when it is absent: a check that passes with nothing to compare against reports success for work it
did not do.
