# -*- coding: utf-8 -*-
"""Every upstream the gateway can route to has an address in its manifest.

WHY THIS EXISTS. `GatewayProperties` gained `catalog` and `assessment` when the
routing table did (T-9.11, #143), and both defaulted to `http://localhost:<port>`.
Nothing set CATALOG_URL or ASSESSMENT_URL on the Deployment, so inside the pod
those addresses pointed at nothing.

The gateway started. It reported healthy. It relayed identity, streaming and
reporting perfectly. The first request to a catalog path answered 500 with a
`ClosedChannelException` — and 500 from a relay reads as "the service behind it
is broken", so the fault surfaced as far as possible from the missing line.

A default that is a real-looking URL is worse than no default here: it turns a
configuration gap into a runtime connection failure rather than a startup
refusal. Changing that is a bigger conversation than this check, and this check
is what makes the gap visible before it ships.

Run:  python scripts/verify_gateway_upstreams.py
"""
import io
import re
import sys

PROPERTIES = ("services/gateway/src/main/java/com/xenopsoftware/learn/gateway/"
              "config/GatewayProperties.java")
MANIFEST = "platform/envs/dev/services/gateway.yaml"

# The record's components, in order, with the default each falls back to. `appUrl`
# is not an upstream -- it is where the browser lives -- so it is excluded by name
# rather than by position, which would break the moment somebody reorders them.
NOT_AN_UPSTREAM = {"appUrl"}


def upstreams_in_code():
    with io.open(PROPERTIES, encoding="utf-8") as handle:
        source = handle.read()
    found = re.findall(
        r'@DefaultValue\("(?P<default>[^"]+)"\)\s+String\s+(?P<name>\w+)', source)
    return [(name, default) for default, name in found if name not in NOT_AN_UPSTREAM]


def addresses_in_manifest():
    with io.open(MANIFEST, encoding="utf-8") as handle:
        manifest = handle.read()
    return dict(re.findall(r'name:\s*([A-Z_]+_URL),\s*value:\s*(\S+?)\}', manifest))


def main():
    code = upstreams_in_code()
    manifest = addresses_in_manifest()

    print("The gateway's upstreams, in code and on the cluster\n")
    problems = []
    for name, default in code:
        variable = name.upper() + "_URL"
        address = manifest.get(variable)
        if address is None:
            problems.append(
                "%s is routable in GatewayProperties and has no %s in %s.\n"
                "        It would fall back to %s, which inside the pod is nothing,\n"
                "        and the first request to it answers 500 from the relay."
                % (name, variable, MANIFEST, default))
            print("  MISSING  %-12s no %s" % (name, variable))
        elif "localhost" in address:
            problems.append(
                "%s is addressed at %s, which inside the pod is this process."
                % (variable, address))
            print("  LOCAL    %-12s %s" % (name, address))
        else:
            print("  ok       %-12s %s" % (name, address))

    # The other direction: an address for something the code cannot route to is
    # not dangerous, but it is a lie about what this gateway does.
    routable = {name.upper() + "_URL" for name, _ in code}
    for variable in sorted(set(manifest) - routable - {"OIDC_REDIRECT_URI", "APP_URL"}):
        print("  EXTRA    %s is set and nothing routes to it" % variable)
        problems.append("%s is set in %s and GatewayProperties has no such upstream."
                        % (variable, MANIFEST))

    print()
    if problems:
        print("=" * 66)
        print("FAILED - the gateway cannot reach everything it claims to route.\n")
        for problem in problems:
            print("  - %s" % problem)
        print("=" * 66)
        return 1
    print("=" * 66)
    print("PASSED - every upstream the gateway routes to has a real address.")
    print("=" * 66)
    return 0


if __name__ == "__main__":
    sys.exit(main())
