"""Assert the realm export fits the columns Keycloak stores it in.

The limits below are Keycloak's own schema, not a house style: exceeding one
crashes the import rather than truncating. Locally that is `make up` failing with
"container xenopslearn-keycloak-1 is unhealthy" and no other clue; in the cluster
it is worse, because applying a realm change means deleting the realm first, so a
crashed import leaves nothing to log in to.

Both happened on 2026-09-07 from one 271-character role description, which is why
this exists. The cluster copy of this realm lives in xenopsbase-stemcell and has
the same check; the two files are kept in step by hand.
"""

import glob
import sys

import json

# column -> (path description, maximum). Only the ones this repository actually
# writes; add a row when a manifest starts using a new field, rather than
# guessing at Keycloak's whole schema.
LIMITS = {
    "realm role description": 255,
    "client role description": 255,
    "client description": 255,
    "client name": 255,
    "username": 255,
    "group name": 255,
}


def check(path):
    problems = []
    with open(path, encoding="utf-8") as handle:
        realm = json.load(handle)
    if not realm:
        return problems, 0

    checked = 0

    def measure(kind, label, value):
        nonlocal checked
        if not isinstance(value, str):
            return
        checked += 1
        limit = LIMITS[kind]
        if len(value) > limit:
            problems.append(
                f"{path}: {kind} for {label!r} is {len(value)} characters, limit {limit}"
            )

    for role in realm.get("roles", {}).get("realm", []) or []:
        measure("realm role description", role.get("name"), role.get("description"))

    for client_id, roles in (realm.get("roles", {}).get("client", {}) or {}).items():
        for role in roles or []:
            measure("client role description", f"{client_id}:{role.get('name')}", role.get("description"))

    for client in realm.get("clients", []) or []:
        measure("client description", client.get("clientId"), client.get("description"))
        measure("client name", client.get("clientId"), client.get("name"))

    for user in realm.get("users", []) or []:
        measure("username", user.get("username"), user.get("username"))

    for group in realm.get("groups", []) or []:
        measure("group name", group.get("name"), group.get("name"))

    return problems, checked


def main():
    paths = sorted(glob.glob("local/keycloak/realm-*.json"))
    if not paths:
        # Never pass by finding nothing to check.
        print("  no realm files found — this check has stopped checking anything")
        return 1

    all_problems = []
    for path in paths:
        problems, checked = check(path)
        status = "FAIL" if problems else "ok"
        print(f"  {status:4}  {path}  ({checked} field(s))")
        all_problems.extend(problems)

    for problem in all_problems:
        print(f"    {problem}")
    return 1 if all_problems else 0


if __name__ == "__main__":
    sys.exit(main())
