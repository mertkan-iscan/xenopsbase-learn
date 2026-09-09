#!/usr/bin/env python3
"""ADR-0109's arithmetic, enforced rather than commented.

    python scripts/verify_capacity.py              # static, runs in CI
    python scripts/verify_capacity.py --cluster    # also checks the figures are still true

WHY THIS EXISTS

The stemcell's `platform/envs/dev/services/hpa.yaml` carries this sentence:

    IF THE REQUESTS CHANGE, THESE TARGETS ARE WRONG. They are a ratio to the
    request and nothing enforces the relationship.

The requests then changed, and the targets were wrong, and the file that said so
went on saying so for weeks. That is not a failure of the comment -- the comment was
correct, specific and prominent. It is a failure of putting an invariant somewhere
that cannot fail.

T-9.15 (#101) asks for "a check that fails when the ratio breaks again", and this is
it, for this repository's half. Every number ADR-0109's decision rests on is below,
with the date it was measured, and every relationship between them is asserted.

THE TWO CONSTRAINTS, WHICH GIVE OPPOSITE ANSWERS

  SCHEDULING   the sum of REQUESTS against allocatable. A pod that does not fit here
               is Pending, and the cluster-autoscaler adds a node. This is what
               binds on this cluster, and it is entirely under our control: we
               choose the request.

  SURVIVAL     COMMITTED memory against physical memory. A node that runs out here
               does not go Pending, it degrades -- T-1.12's failure, where Argo's
               repo-server lost its probes and committed changes silently stopped
               arriving.

ADR-0109's first draft compared requests against requests and invented headroom. Its
second compared `kubectl top` against allocatable and invented a shortage. Both are
recorded there. This file keeps them apart on purpose.

WHAT THIS DOES NOT CHECK

Anything about the stemcell's cluster that is the stemcell's to decide: the HPA
targets on `apps`, `max_nodes`, the platform's own requests. Those are asserted in
that repository, by `make verify-headroom` and `make verify-node-memory`. What is
here is the part this repository can break on its own -- our manifests drifting from
the measurement the decision was made on.
"""

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MANIFESTS = ROOT / "platform" / "envs" / "dev" / "services"

# ---------------------------------------------------------------------------
# THE MEASUREMENT. Everything below is read from a running cluster on the date
# named, by scripts/capacity_reading.py, and written up in docs/slos.md.
#
# CHANGING A NUMBER HERE WITHOUT A FRESH READING IS THE FAILURE THIS FILE EXISTS
# TO PREVENT. Re-measure, then edit, then move the date.
# ---------------------------------------------------------------------------
MEASURED_ON = "2026-09-08"

# Per fixed cx33 worker. NOT the machine's 7753Mi: the kubelet's reservations come
# off first. This figure MOVES -- it was 7153Mi when ADR-0109 was written and the
# stemcell cut it by 1250Mi in its T-2.28 (#367), which is most of why that draft's
# arithmetic no longer held. --cluster is what notices next time.
WORKER_ALLOCATABLE_MI = 5903
FIXED_WORKERS = 2

# What the platform books on the two fixed workers: everything that is neither
# `apps` (the stemcell's services) nor `learn` (ours). Observability alone is 3572Mi
# of it, which is more than everything of ours put together.
PLATFORM_BOOKED_MI = 7942

# The stemcell's own services at their floor, which compete with ours for the same
# two workers: gateway at 2 replicas x 640Mi, plus core at 832Mi.
STEMCELL_APPS_AT_FLOOR_MI = 2112

# What a fixed worker must keep free so an ORDINARY PLATFORM POD can still land --
# a rescheduled coredns, a CSI sidecar after a node event, a DaemonSet member
# rolling. The stemcell's #368 was filed when a worker had 11Mi of this. Its
# `make verify-headroom` asserts it there; we have to leave room for it here.
HEADROOM_FLOOR_MI_PER_WORKER = 256

# One of OUR Spring Boot processes, measured on the deployed pods rather than
# borrowed from the stemcell's `core`. The borrowed figure was 605Mi and it is not
# ours: a JVM sizes its heap from the container LIMIT, and ours is 896Mi where
# core's is 1Gi.
#
# UNDER LOAD is the worst sample ever taken, not the best-behaved run: 406Mi, from a
# 64-worker run that ended with the pod being restarted on a failed liveness probe.
# A settled 16-worker run plateaus at 341Mi. The larger figure is the one recorded,
# because a floor taken from the run that behaved is not a floor.
OUR_FLOOR_AT_REST_MI = 370
OUR_FLOOR_UNDER_LOAD_MI = 406

# ADR-0109's decision, as pods rather than as processes: gateway at 2 replicas,
# core, streaming, packaging, reporting. `frontend` is a static build served from
# the edge and costs the cluster nothing, which is why the module count and the pod
# count differ by one.
PODS_AT_SIX_PROCESSES = 6


def fail(message):
    print("  FAIL  " + message)
    return 1


def ok(message):
    print("  ok    " + message)
    return 0


def maven_modules():
    """The services the Java build produces, read from the one list that must be right.

    This is what makes the invariant below apply to the right manifests. It is about
    JVMs: a JVM sizes its heap from the container LIMIT, and one that under-declares
    its REQUEST is starved first and never Pending, so nothing reports it. Neither
    property belongs to nginx serving files out of page cache, and booking half a
    gigabyte for the frontend would be the same mistake ADR-0109 caught in the other
    direction -- spending the cluster's scarcest resource on nothing.

    So a Deployment whose name is not a Maven module is not held to the figure. The
    discriminator is deliberately not a list of names kept here: a new Java service
    cannot escape it by being forgotten, and a static asset server is not asked to
    pretend to be a JVM.
    """
    pom = (ROOT / "services" / "pom.xml").read_text(encoding="utf-8")
    modules = set(re.findall(r"<module>([^<]+)</module>", pom))
    return {m for m in modules if not m.startswith("platform-common")}


def declared_resources():
    """Each JVM service manifest's app-container memory request and limit.

    Parsed with a regular expression rather than a YAML library, deliberately: this
    script is meant to run in CI on a checkout with no Python dependencies installed,
    the same way scripts/service-modules.sh works with no jq. The shape it reads is
    the one the manifests use and a `verify` job proves it still matches.
    """
    jvm_services = maven_modules()
    found = {}
    for path in sorted(MANIFESTS.glob("*.yaml")):
        text = path.read_text(encoding="utf-8")
        if "kind: Deployment" not in text:
            continue
        if path.stem not in jvm_services:
            continue
        # The LAST resources block in the file is the app container's; the first is
        # the wait-for-oidc init container, which is 16Mi and not what is being sized.
        blocks = re.findall(
            r"requests:\s*\{cpu:\s*([0-9]+)m,\s*memory:\s*([0-9]+)Mi\}\s*\n\s*limits:\s*\{memory:\s*([0-9]+)Mi\}",
            text,
        )
        if not blocks:
            found[path.stem] = None
            continue
        cpu, request, limit = blocks[-1]
        found[path.stem] = {"cpu": int(cpu), "request": int(request), "limit": int(limit)}
    return found


def check_manifests(services):
    """Returns (problems, request) -- request is None when nothing could be read."""
    problems = 0
    for name, declared in sorted(services.items()):
        if declared is None:
            problems += fail(
                "%s declares no memory request and limit in the shape this script reads.\n"
                "        Either the manifest stopped declaring them -- which is the\n"
                "        under-request trap -- or its formatting changed and this parser\n"
                "        needs updating. Both are worth stopping for." % name
            )
    if problems:
        return problems, None

    requests = {d["request"] for d in services.values()}
    limits = {d["limit"] for d in services.values()}
    if len(requests) > 1 or len(limits) > 1:
        problems += fail(
            "the services no longer declare the same memory request and limit: %s.\n"
            "        They are the same kind of process on the same JVM, so a divergence\n"
            "        is a decision. Make it here, with the measurement behind it."
            % ", ".join("%s %d/%d" % (n, d["request"], d["limit"]) for n, d in sorted(services.items()))
        )
        return problems, None

    request = requests.pop()
    limit = limits.pop()
    ok("every service declares %dMi request / %dMi limit" % (request, limit))

    floor = max(OUR_FLOOR_AT_REST_MI, OUR_FLOOR_UNDER_LOAD_MI)
    if request < floor:
        problems += fail(
            "the request (%dMi) is BELOW the measured floor (%dMi).\n"
            "        This is the stemcell's T-5.12 trap: the kernel shares out a contended\n"
            "        CPU in proportion to requests and the scheduler only ever sees the\n"
            "        request, so a pod that under-declares is starved first, exactly when\n"
            "        the node is busy -- and nothing is ever Pending, so no node is added."
            % (request, floor)
        )
    else:
        ok("the request (%dMi) covers the measured floor (%dMi), by %dMi"
           % (request, floor, request - floor))

    if limit <= request:
        problems += fail("the limit (%dMi) does not exceed the request (%dMi)" % (limit, request))
    else:
        ok("the limit (%dMi) leaves %dMi of burst above the request" % (limit, limit - request))

    return problems, request


def check_arithmetic(request):
    """ADR-0109's conclusion, recomputed from the numbers above."""
    allocatable = WORKER_ALLOCATABLE_MI * FIXED_WORKERS
    headroom = HEADROOM_FLOOR_MI_PER_WORKER * FIXED_WORKERS
    committed = PLATFORM_BOOKED_MI + STEMCELL_APPS_AT_FLOOR_MI + headroom
    ours = PODS_AT_SIX_PROCESSES * request

    print()
    print("  ADR-0109's arithmetic, on the fixed workers only:")
    print("    %5dMi  allocatable (%d x %dMi)" % (allocatable, FIXED_WORKERS, WORKER_ALLOCATABLE_MI))
    print("    %5dMi  the platform underneath" % PLATFORM_BOOKED_MI)
    print("    %5dMi  the stemcell's own services at floor" % STEMCELL_APPS_AT_FLOOR_MI)
    print("    %5dMi  reserved so an ordinary platform pod can still land" % headroom)
    print("    %5dMi  left for us" % (allocatable - committed))
    print("    %5dMi  what six processes ask for (%d pods x %dMi)" % (ours, PODS_AT_SIX_PROCESSES, request))
    print()

    left = allocatable - committed
    if ours <= left:
        # Not a relief. ADR-0109's decision is written against "they do not fit",
        # and an ADR that describes a cluster which no longer exists is worse than
        # one that is merely pessimistic -- it will be quoted.
        return fail(
            "six processes now FIT the two fixed workers, with %dMi to spare.\n"
            "        ADR-0109 concludes they do not, and that dev therefore carries a\n"
            "        permanently occupied autoscaled node at floor. That conclusion is\n"
            "        now false. Re-derive it there before this passes again."
            % (left - ours)
        )

    return ok(
        "six processes do not fit the two fixed workers -- short by %dMi.\n"
        "        This is ADR-0109's conclusion, and it is why dev carries a permanently\n"
        "        occupied autoscaled node at floor rather than only under load."
        % (ours - left)
    )


def check_cluster():
    """The half that goes stale on its own, with nothing in this repository moving."""
    problems = 0
    print()
    print("  Against the running cluster:")

    def kubectl(*args):
        out = subprocess.run(["kubectl", *args], capture_output=True, text=True, encoding="utf-8")
        if out.returncode != 0:
            sys.stderr.write(out.stderr)
            sys.exit(
                "  no cluster. Point KUBECONFIG at the stemcell's dev kubeconfig:\n"
                "    infra/terraform/cluster/kubeconfig -- NOT the KUBECONFIG already in\n"
                "    your shell, which is a stale local k3d config."
            )
        return json.loads(out.stdout)

    MI = 1024 * 1024

    def q(value):
        if value is None:
            return 0
        value = str(value)
        for suffix, mult in (("Ki", 1024), ("Mi", MI), ("Gi", 1024 * MI)):
            if value.endswith(suffix):
                return float(value[: -len(suffix)]) * mult / MI
        return float(value) / MI

    nodes = kubectl("get", "nodes", "-o", "json")["items"]
    fixed = [n for n in nodes if "worker" in n["metadata"]["name"]]

    for n in fixed:
        alloc = q(n["status"]["allocatable"]["memory"])
        if abs(alloc - WORKER_ALLOCATABLE_MI) > 64:
            problems += fail(
                "%s reports %.0fMi allocatable, not the %dMi measured on %s.\n"
                "        The kubelet's reservations moved. THIS IS EXACTLY HOW ADR-0109\n"
                "        WENT STALE -- the stemcell cut allocatable by 1250Mi and nothing\n"
                "        here noticed for two weeks. Re-run scripts/capacity_reading.py and\n"
                "        update the block at the top of this file."
                % (n["metadata"]["name"], alloc, WORKER_ALLOCATABLE_MI, MEASURED_ON)
            )
        else:
            ok("%s still has %.0fMi allocatable" % (n["metadata"]["name"], alloc))

    pods = kubectl("get", "pods", "-A", "-o", "json")["items"]
    top = {}
    out = subprocess.run(["kubectl", "top", "pods", "-n", "learn", "--no-headers"],
                         capture_output=True, text=True, encoding="utf-8")
    for line in out.stdout.splitlines():
        name, _, mem = line.split()
        top[name] = q(mem)

    for p in pods:
        if p["metadata"]["namespace"] != "learn" or p["status"].get("phase") != "Running":
            continue
        name = p["metadata"]["name"]
        request = q(p["spec"]["containers"][0].get("resources", {}).get("requests", {}).get("memory"))
        used = top.get(name, 0)
        if used > request:
            problems += fail(
                "%s is using %.0fMi against a %.0fMi request -- it is over-drawing what\n"
                "        the scheduler booked for it, which is invisible until a node fills."
                % (name, used, request)
            )
        else:
            ok("%s uses %.0fMi of its %.0fMi request" % (name, used, request))

    return problems


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cluster", action="store_true",
                    help="also check the measured figures against the running cluster")
    args = ap.parse_args()

    print("==================================================================")
    print(" ADR-0109's capacity arithmetic - measured %s" % MEASURED_ON)
    print("==================================================================")
    print()

    services = declared_resources()
    if not services:
        sys.exit("no Deployment manifests under %s" % MANIFESTS)

    problems, request = check_manifests(services)
    if request is not None:
        problems += check_arithmetic(request)
    if args.cluster:
        problems += check_cluster()

    print()
    if problems:
        print("FAILED - %d check(s). The numbers this decision rests on have moved." % problems)
        sys.exit(1)
    print("PASSED - the manifests still match the measurement ADR-0109 was decided on.")


if __name__ == "__main__":
    main()
