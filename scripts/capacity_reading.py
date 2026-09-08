#!/usr/bin/env python3
"""One capacity reading of the dev cluster, in a form that can be compared to another one.

    KUBECONFIG=<stemcell>/infra/terraform/cluster/kubeconfig python scripts/capacity_reading.py

WHY THIS IS A SCRIPT AND NOT A `kubectl top` INVOCATION

ADR-0109's figures were produced by hand, and the way they went wrong is instructive
rather than embarrassing: a single reading was written down as a constant. It was not
one. The free-memory figure moved 810Mi in an hour on an idle cluster, and the second
draft had to be re-derived from the worst of three samples instead of the first.

So a reading has to be cheap enough to take three times and identical enough that the
three can be put in one table. That is all this does. It changes nothing on the
cluster -- every call is a `get` or a `top`.

THE THREE QUANTITIES, AND WHY EACH IS HERE

  allocatable   What the scheduler is allowed to hand out. NOT the machine's memory:
                the kubelet's reservations come off first, and they MOVE. ADR-0109
                recorded 7153Mi per cx33 worker; it is 5903Mi today, because the
                reservations were raised in the stemcell after that measurement.
                Anything computed against the old figure is 1250Mi per worker wrong.

  booked        The sum of pod requests. This is what the scheduler adds up, and it
                is the only number that decides whether a pod is Pending.

  used          `kubectl top`: working set over allocatable. **Reported here and
                deliberately not the basis of any conclusion.** It counts reclaimable
                page cache as spent AND divides by an allocatable that moves, and the
                two errors push the same way. ADR-0109's first draft ran its whole
                argument on this column.

  committed     (MemTotal - MemAvailable) / MemTotal, from node-exporter: physical
                memory actually committed, over physical memory present. Independent
                of every kubelet reservation. This is the one that predicts the
                failure -- the stemcell measured the two disagreeing by THIRTY POINTS
                on the same node at the same instant (its T-2.27, #341) and gates on
                this one, in infra/scripts/check-node-memory.sh.

The gap between booked and committed is the whole point of the exercise. Kubernetes
schedules on requests; the node dies on committed memory. A capacity calculation done
from manifests compares requests against requests and invents headroom; one done from
`kubectl top` compares against page cache and invents a shortage. Both errors have
already been made on this platform, in that order.

WHAT IS DELIBERATELY NOT COUNTED

Pods that are neither Running nor Pending. A Job that has Succeeded reserves nothing,
and counting one is how the stemcell's first audit came out 1700Mi pessimistic.

Init containers are not added to their pod's containers either. A pod's request is
max(sum(containers), max(initContainers)) -- init containers overlap in time, so they
do not add. Both of these are rule 1 and rule 2 of the stemcell's resource-audit.py,
paid for there and inherited here rather than re-learned.
"""

import json
import subprocess
import sys
from datetime import datetime, timezone

MI = 1024 * 1024


def kubectl(*args):
    out = subprocess.run(
        ["kubectl", *args], capture_output=True, text=True, encoding="utf-8"
    )
    if out.returncode != 0:
        sys.stderr.write(out.stderr)
        sys.exit(
            "kubectl failed. Point KUBECONFIG at the stemcell's dev kubeconfig:\n"
            "  infra/terraform/cluster/kubeconfig -- NOT the KUBECONFIG already in\n"
            "  your shell, which is a stale local k3d config."
        )
    return out.stdout


def quantity(value):
    """Kubernetes memory quantities to Mi.

    Written out rather than pulled from a library because the whole repository's
    Python dependency for this task would otherwise be this one function.
    """
    if value is None:
        return 0
    value = str(value)
    units = {"Ki": 1024, "Mi": MI, "Gi": 1024 * MI}
    for suffix, mult in units.items():
        if value.endswith(suffix):
            return float(value[: -len(suffix)]) * mult / MI
    return float(value) / MI


def cpu(value):
    if value is None:
        return 0
    value = str(value)
    return float(value[:-1]) if value.endswith("m") else float(value) * 1000


def physical_memory(nodes):
    """MemTotal and MemAvailable per node, keyed by node name.

    Read out of the Prometheus that already scrapes node-exporter, through the API
    server's service proxy -- so this needs no port-forward and no credential beyond
    the kubeconfig, and reading /proc on each node would need a privileged pod per
    node. The approach is lifted from the stemcell's check-node-memory.sh rather than
    invented.

    Returns {} when Prometheus cannot be reached, and the caller degrades to the
    `kubectl top` column with a warning. A missing honest denominator has to be
    visible: silently falling back to the misleading one is the whole failure this
    column exists to prevent.
    """
    proxy = (
        "/api/v1/namespaces/observability/services/"
        "kube-prometheus-stack-prometheus:9090/proxy/api/v1/query"
    )
    by_ip = {}
    for metric in ("node_memory_MemTotal_bytes", "node_memory_MemAvailable_bytes"):
        out = subprocess.run(
            ["kubectl", "get", "--raw", proxy + "?query=" + metric],
            capture_output=True, text=True, encoding="utf-8",
        )
        if out.returncode != 0:
            return {}
        for row in json.loads(out.stdout)["data"]["result"]:
            ip = row["metric"].get("instance", "").split(":")[0]
            by_ip.setdefault(ip, {})[metric] = float(row["value"][1]) / MI

    # node-exporter labels its series by the node's address, so the join back to a
    # node name goes through the node's InternalIP.
    by_name = {}
    for n in nodes:
        for address in n["status"].get("addresses", []):
            if address["type"] == "InternalIP" and address["address"] in by_ip:
                by_name[n["metadata"]["name"]] = by_ip[address["address"]]
    return by_name


def effective_requests(spec):
    """max(sum(containers), max(initContainers)) -- see the header."""
    containers = spec.get("containers", [])
    mem = sum(quantity(c.get("resources", {}).get("requests", {}).get("memory")) for c in containers)
    cpus = sum(cpu(c.get("resources", {}).get("requests", {}).get("cpu")) for c in containers)
    inits = spec.get("initContainers") or []
    if inits:
        mem = max(mem, max(quantity(c.get("resources", {}).get("requests", {}).get("memory")) for c in inits))
        cpus = max(cpus, max(cpu(c.get("resources", {}).get("requests", {}).get("cpu")) for c in inits))
    return mem, cpus


def main():
    taken = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%MZ")

    nodes = json.loads(kubectl("get", "nodes", "-o", "json"))["items"]
    pods = json.loads(kubectl("get", "pods", "-A", "-o", "json"))["items"]

    top_nodes = {}
    for line in kubectl("top", "node", "--no-headers").splitlines():
        name, c, _, m, _ = line.split()
        top_nodes[name] = (cpu(c), quantity(m))

    top_pods = {}
    for line in kubectl("top", "pods", "-A", "--no-headers").splitlines():
        ns, name, c, m = line.split()
        top_pods[(ns, name)] = (cpu(c), quantity(m))

    live = [p for p in pods if p["status"].get("phase") in ("Running", "Pending")]

    booked_mem, booked_cpu = {}, {}
    for p in live:
        node = p["spec"].get("nodeName")
        mem, cpus = effective_requests(p["spec"])
        booked_mem[node] = booked_mem.get(node, 0) + mem
        booked_cpu[node] = booked_cpu.get(node, 0) + cpus

    physical = physical_memory(nodes)

    print("# capacity reading " + taken)
    print()
    if not physical:
        print("> **No honest denominator in this reading.** Prometheus could not be reached, so the")
        print("> `committed` column is missing and only `kubectl top` is left -- which is the")
        print("> instrument ADR-0109 got wrong. Do not conclude anything from this reading.")
        print()
    print("## Nodes")
    print()
    print("| node | allocatable | booked | `top` used | **committed / physical** | cpu alloc | cpu booked | cpu used |")
    print("|---|---|---|---|---|---|---|---|")

    fixed, autoscaled = [], []
    for n in sorted(nodes, key=lambda n: n["metadata"]["name"]):
        name = n["metadata"]["name"]
        alloc = quantity(n["status"]["allocatable"]["memory"])
        alloc_cpu = cpu(n["status"]["allocatable"]["cpu"])
        booked = booked_mem.get(name, 0)
        bcpu = booked_cpu.get(name, 0)
        used_cpu, used = top_nodes.get(name, (0, 0))
        short = name.replace("xenopsbase-dev-", "")

        node_physical = physical.get(name)
        if node_physical:
            total = node_physical["node_memory_MemTotal_bytes"]
            avail = node_physical["node_memory_MemAvailable_bytes"]
            committed = "**%.0fMi / %.0fMi (%.0f%%)**" % (total - avail, total, 100 * (total - avail) / total)
        else:
            committed = "—"

        print(
            "| `%s` | %.0fMi | %.0fMi | %.0fMi | %s | %.0fm | %.0fm | %.0fm |"
            % (short, alloc, booked, used, committed, alloc_cpu, bcpu, used_cpu)
        )
        # The control plane is tainted, so it can never carry a service and its
        # spare memory is not ours to spend. Only untainted nodes are summed.
        #
        # And the fixed workers are summed APART from the autoscaled ones, because
        # they are not the same kind of capacity. The fixed pair is paid for
        # continuously and is what the platform and the services at floor must fit
        # inside; an autoscaled node exists only while something is Pending and
        # drains two minutes after it is not. Adding the two together produces a
        # total that is true at the instant it is read and gone by the next one.
        if not any(t.get("effect") == "NoSchedule" for t in (n["spec"].get("taints") or [])):
            row = (
                alloc,
                booked,
                node_physical["node_memory_MemTotal_bytes"] if node_physical else 0,
                (node_physical["node_memory_MemTotal_bytes"] - node_physical["node_memory_MemAvailable_bytes"])
                if node_physical else 0,
            )
            (autoscaled if "autoscaled" in name else fixed).append(row)

    def summarise(label, rows):
        """The two headrooms, which are different questions with different answers.

        SCHEDULING headroom is allocatable minus booked: what the scheduler will still
        accept. A pod that does not fit here goes Pending, and Pending is the only
        signal the cluster-autoscaler listens to.

        SURVIVAL headroom is physical minus committed: what the machine still has.
        A node that runs out here does not go Pending, it degrades -- which is
        T-1.12's failure, where Argo's repo-server lost its probes and committed
        changes silently stopped arriving.

        They are reported separately because on this cluster they give opposite
        answers, and quoting either one alone is how both previous drafts went wrong.
        """
        if not rows:
            print()
            print("**%s: none up.**" % label)
            return
        alloc, booked, total, committed = (sum(x) for x in zip(*rows))
        print()
        print("**%s (%d):**" % (label, len(rows)))
        print()
        print("- scheduling: %.0fMi allocatable, %.0fMi booked, **%.0fMi free to schedule into**"
              % (alloc, booked, alloc - booked))
        if total:
            print("- survival:   %.0fMi physical, %.0fMi committed, **%.0fMi actually free**"
                  % (total, committed, total - committed))

    summarise("Fixed workers", fixed)
    summarise("Autoscaled", autoscaled)
    print()
    print("## What one of our JVMs costs")
    print()
    print("| pod | node | request | limit | **used** |")
    print("|---|---|---|---|---|")
    for p in sorted(live, key=lambda p: p["metadata"]["name"]):
        if p["metadata"]["namespace"] != "learn":
            continue
        name = p["metadata"]["name"]
        app = p["spec"]["containers"][0]
        resources = app.get("resources", {})
        req = quantity(resources.get("requests", {}).get("memory"))
        lim = quantity(resources.get("limits", {}).get("memory"))
        _, used = top_pods.get(("learn", name), (0, 0))
        node = (p["spec"].get("nodeName") or "").replace("xenopsbase-dev-", "")
        print("| `%s` | `%s` | %.0fMi | %.0fMi | **%.0fMi** |" % (name, node, req, lim, used))


if __name__ == "__main__":
    main()
