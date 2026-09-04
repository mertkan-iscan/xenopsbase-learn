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

## What blocks a deploy today, in order

### 1. The cluster will refuse these images

`platform/envs/dev/policy/clusterimagepolicy.yaml` in the stemcell admits images matching
`ghcr.io/mertkan-iscan/xenopsbase-stemcell/**`, signed by a Fulcio certificate whose subject is a
workflow **in the stemcell repository**. policy-controller denies anything matching *no* policy in
an enrolled namespace.

Learn's images fail both halves: wrong registry path, and a certificate naming a workflow in
*this* repository. Our CI signs them keylessly with the same flags for exactly this reason — the
signature is correct and there is no policy that accepts it yet.

**What is needed:** a second `ClusterImagePolicy` in the stemcell, for
`ghcr.io/mertkan-iscan/xenopsbase-learn/**`, with

```yaml
subjectRegExp: '^https://github\.com/mertkan-iscan/xenopsbase-learn/\.github/workflows/.+$'
```

That is a change to the cluster's admission policy and to another repository, so it is a decision
rather than a commit.

### 2. Three services need three databases, a realm, and a budget

- **Databases.** `identity`, `streaming` and `reporting` each own their schema outright and the
  separation is enforced by credentials (`docs/reporting-inputs.md`). The cluster runs
  CloudNativePG for the stemcell's own database. Whether learn gets databases in that cluster or
  its own is a real decision: shared means one backup story and one blast radius, separate means
  two of everything.
- **Keycloak.** Learn has its own realm (`xenopslearn`) and `scripts/realm-apply.sh` applies it
  without touching users. The cluster runs Keycloak via the operator, with the stemcell's realm.
  Adding a second realm is straightforward; deciding it should be a second realm rather than a
  second Keycloak is the part to write down.
- **The budget.** [ADR-0109](../docs/adr/0109-eight-modules-and-how-many-processes.md) measured
  that a Spring Boot process idles at ~600Mi and that the dev workers leave room for about six.
  The stemcell already runs two. Three more is five, before `catalog` and `assessment` exist as
  processes — so the sizing that ADR describes has to be revisited against the same cluster rather
  than assumed to still hold.

### 3. There is no `platform/envs/` here yet

When the above are decided, the shape follows the stemcell exactly: `platform/envs/dev/services/`
with a Deployment per service and a `kustomization.yaml` pinning digests, and an Argo CD
`Application` in the *stemcell's* `platform/envs/dev/apps/` pointing at this repository's path.

## What is done

- Images build with jib — no Dockerfile, no daemon on the runner, base pinned by digest so the
  same commit produces the same image.
- Tags are the commit SHA plus `main` for humans. **`main` must not appear in a manifest.**
- Signed keylessly with cosign and verified in the same run, with SLSA provenance attached.
- `platform-common` publishes nothing: it is a library and its pom says so, so a repository-root
  `mvn jib:build` does the right thing instead of failing on a missing main class.
