# Deploying this platform

**Task:** T-9.3

The images are built and signed by [`.github/workflows/ci.yml`](../.github/workflows/ci.yml) and
published to `ghcr.io/mertkan-iscan/xenopsbase-learn/{identity,streaming,reporting}`. **Getting
them onto the cluster is not done**, and this file is what the next person needs rather than a
placeholder.

## The model, inherited from the stemcell

The target is the Hetzner dev cluster the
[stemcell](https://github.com/mertkan-iscan/xenopsbase-stemcell) builds, and its deploy model is
worth restating because it is the opposite of what a pipeline usually does:

**CI does not deploy.** It builds, tests, signs and publishes. A deploy is a commit that changes a
digest in a kustomization, and Argo CD reconciles it. `git log` on that file is the deployment
history — which is why the stemcell's own `kustomization.yaml` carries a paragraph of reasoning
per digest.

**Images are pinned by digest, never by tag.** `main` moves, and with
`imagePullPolicy: IfNotPresent` a node keeps whatever it cached, so two replicas can run different
code and nothing reports the difference. The stemcell hit exactly that. The CI summary prints the
digest for this reason.

## The manifests

`platform/envs/dev/services/` holds the three Deployments, their Services, the OIDC ConfigMap they
share, and the `kustomization.yaml` that pins each image **by digest**. That kustomization is the
deployment history: a deploy is a one-line commit to it, and Argo CD reconciles.

The platform half lives in the stemcell, because those are decisions about that cluster rather
than about these services: the `learn` namespace and its image-policy enrolment, three databases
and roles in the shared Postgres, the `xenopslearn` realm on the shared Keycloak, the
`ClusterImagePolicy` that admits these images, and the Argo `Application` that points here.
See [stemcell#426](https://github.com/mertkan-iscan/xenopsbase-stemcell/pull/426) and its
`docs/runbooks/hosting-xenopsbase-learn.md`.

**Shared Postgres and Keycloak, on purpose.** The isolation that matters — one module unable to
read or rewrite another's tables — comes from separate roles and databases, not separate clusters,
and sharing keeps this data under the same WAL archive rather than making it a new exception to
the stemcell's ADR-0002. Three databases rather than one schema each, because
`docs/reporting-inputs.md` says the boundary is enforced by credentials rather than convention,
and one database with three schemas would make that a review comment.

A second realm rather than a second Keycloak: this is a different product on one identity
provider, which is a different thing from a different customer. A customer is a row inside a realm
and never a realm of its own (ADR-0102).

## What still blocks a first sync

1. **Six SOPS secrets.** They need the age private key, which is deliberately not in either
   repository. The runbook in that pull request lists them, their namespaces, and the trap: the
   `svc-*` client secrets must match the realm's, or every inter-service call is refused with
   nothing else looking wrong.
2. **The image policy has to merge.** Until it does the cluster denies these images — wrong
   registry path and wrong signing identity for the policy that exists. That is why CI signs them
   at all.
3. **The headroom is unmeasured.** [ADR-0109](../docs/adr/0109-eight-modules-and-how-many-processes.md)
   measured ~600Mi per Spring Boot process and about six fitting on the dev workers; the stemcell
   already runs two, and this adds three. `make verify-headroom` there is the check after a first
   sync, and it is what caught a worker dropping to 87Mi schedulable after a routine rollout.

## What will not work even once it syncs, and is not a fault

**Nothing plays a video.** `streaming` runs the fake media provider and warns loudly at startup:
uploads, encode state and playback tokens are simulated. Real delivery needs the Cloudflare Stream
account that is T-9.14 (#100).

**There is no public entry point.** The three services are cluster-internal with no Ingress, and
the frontend is not deployed. A session and a gateway in front of them is T-10.2 (#93); until
then, reaching them means `kubectl port-forward`.

## What is done

- Images build with jib — no Dockerfile, no daemon on the runner, base pinned by digest so the
  same commit produces the same image.
- Tags are the commit SHA plus `main` for humans. **`main` must not appear in a manifest.**
- Signed keylessly with cosign and verified in the same run, with SLSA provenance attached.
- `platform-common` publishes nothing: it is a library and its pom says so, so a repository-root
  `mvn jib:build` does the right thing instead of failing on a missing main class.
