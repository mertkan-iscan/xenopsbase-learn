#!/usr/bin/env bash
#
# Repairs app_user.idp_sub after a realm was rebuilt with new subjects (T-1.7).
#
# THE RECOVERY THIS PLATFORM IS BUILT TO HAVE. ADR-0104 keeps ownership in our
# own app_user.id and stores the Keycloak subject as one nullable link, so a
# realm whose users came back with new internal ids is one UPDATE per person --
# not a data loss. This is that UPDATE, with the guards
# UserProvisioningService.relink applies in code:
#
#   * matched by email, inside one tenant, because an email is unique per tenant
#   * never a deactivated account: re-linking one would let somebody sign into
#     an account nobody decided to reactivate (T-1.9)
#   * never a subject that already belongs to somebody else
#
# DRY RUN BY DEFAULT. It prints the UPDATEs it would run; APPLY=1 runs them.
# A repair procedure that writes on its first invocation is one nobody dares
# rehearse, and this one is meant to be rehearsed.
#
# Usage:  bash scripts/realm-relink.sh          # report
#         APPLY=1 bash scripts/realm-relink.sh  # repair
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
PY="$(bash "$HERE/python.sh")"

KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8081}"
KC_ADMIN_USER="${KC_ADMIN_USER:-admin}"
KC_ADMIN_PASSWORD="${KC_ADMIN_PASSWORD:-admin}"
REALM_FILE="${REALM_FILE:-$ROOT/local/keycloak/realm-xenopslearn.json}"
REALM="${REALM:-$("$PY" -c "import json,sys;print(json.load(open(sys.argv[1],encoding='utf-8'))['realm'])" "$REALM_FILE")}"

# The local stack by default; point PSQL at a real database deliberately.
PSQL="${PSQL:-docker compose exec -T postgres psql -U identity -d identity}"

TOKEN="$(curl -sf -X POST "$KEYCLOAK_URL/realms/master/protocol/openid-connect/token" \
    -d grant_type=password -d client_id=admin-cli \
    --data-urlencode "username=$KC_ADMIN_USER" \
    --data-urlencode "password=$KC_ADMIN_PASSWORD" \
    | "$PY" -c "import json,sys;print(json.load(sys.stdin)['access_token'])")" \
    || { echo "Could not authenticate to $KEYCLOAK_URL" >&2; exit 1; }

REALM_USERS="$(curl -sf -H "Authorization: Bearer $TOKEN" \
    "$KEYCLOAK_URL/admin/realms/$REALM/users?briefRepresentation=true&max=5000")"

# id|tenant|email|idp_sub|status, one person per line.
PEOPLE="$(cd "$ROOT" && $PSQL -t -A -F'|' -c \
    "SELECT id, tenant_id, email, coalesce(idp_sub, ''), status FROM app_user ORDER BY tenant_id, email")"

# In a file, not on stdin: stdin is carrying the people.
PLANNER="$(mktemp)"
cat > "$PLANNER" <<'PLAN'
import json, sys

realm_users = json.loads(sys.argv[1])
by_email = {}
for user in realm_users:
    email = (user.get("email") or "").lower()
    if email:
        by_email.setdefault(email, []).append(user)

people = [row.split("|") for row in sys.stdin.read().splitlines() if row.strip()]

# Subjects already held, per person. The unique index on app_user.idp_sub means a
# plan that hands one person's subject to somebody else does not "mostly work":
# it fails the whole transaction. The rehearsal that found this had exactly that
# shape -- one person's product address had been changed, so the realm's declared
# id for their username belonged to a different row.
held = {sub: app_id for app_id, tenant, email, sub, status in people if sub}

taken = set()
lines = []
for app_id, tenant, email, sub, status in people:
    matches = by_email.get(email.lower(), [])
    if not matches:
        lines.append(f"MISSING  {tenant}/{email}: no account in the realm; they can sign in "
                     f"again once one exists, or the row waits as it is")
        continue
    if len(matches) > 1:
        lines.append(f"AMBIGUOUS {tenant}/{email}: {len(matches)} realm accounts share this "
                     f"address; a human decides which one")
        continue
    new_sub = matches[0]["id"]
    if new_sub == sub:
        lines.append(f"UNCHANGED {tenant}/{email}")
        continue
    if status == "DEACTIVATED":
        lines.append(f"SKIPPED  {tenant}/{email}: deactivated, and re-linking would hand "
                     f"somebody an account nobody decided to reactivate")
        continue
    if new_sub in taken:
        lines.append(f"CONFLICT {tenant}/{email}: subject {new_sub} already claimed above")
        continue
    owner = held.get(new_sub)
    if owner and owner != app_id:
        lines.append(f"CONFLICT {tenant}/{email}: subject {new_sub} already belongs to "
                     f"app_user {owner}. Which of the two people that account is, is not a "
                     f"decision this script may make -- and re-linking would fail the repair.")
        continue
    taken.add(new_sub)
    lines.append(f"RELINK   {tenant}/{email}: {sub or '(none)'} -> {new_sub}")
    lines.append(f"SQL      UPDATE app_user SET idp_sub = '{new_sub}', updated_at = now() "
                 f"WHERE id = '{app_id}';")
print("\n".join(lines))
PLAN
PLAN="$(printf '%s' "$PEOPLE" | "$PY" "$PLANNER" "$REALM_USERS")"
rm -f "$PLANNER"

printf '%s\n' "$PLAN"

STATEMENTS="$(printf '%s\n' "$PLAN" | sed -n 's/^SQL      //p')"
if [ -z "$STATEMENTS" ]; then
    echo ""
    echo "Nothing to re-link."
    exit 0
fi

if [ "${APPLY:-}" != "1" ]; then
    echo ""
    echo "Dry run. Re-run with APPLY=1 to execute the $(printf '%s\n' "$STATEMENTS" | wc -l | tr -d ' ') statement(s) above."
    exit 0
fi

# One transaction: half a re-link is worse than none, because the half that ran
# looks correct and the half that did not is invisible until somebody signs in.
if ! printf 'BEGIN;\n%s\nCOMMIT;\n' "$STATEMENTS" | (cd "$ROOT" && $PSQL -v ON_ERROR_STOP=1 -q); then
    echo "" >&2
    echo "REPAIR FAILED -- nothing changed. The plan is one transaction, so the database is" >&2
    echo "exactly as it was. Read the error above: a unique-constraint failure on idp_sub" >&2
    echo "means the plan handed one person a subject another row already holds." >&2
    exit 1
fi
echo ""
echo "Re-linked. Everything those people did still points at the same app_user.id."
