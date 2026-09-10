#!/usr/bin/env bash
#
# Applies the realm file to a Keycloak WITHOUT deleting anything (T-1.7).
#
# THIS IS THE PATH FOR EVERY ENVIRONMENT THAT HAS REAL PEOPLE IN IT.
#
# Keycloak's declarative `--import-realm` only imports a realm that does not
# exist yet. That leaves an obvious-looking procedure for changing one that
# does -- delete it and let the import recreate it -- and against real customers
# that procedure destroys every account while reading as routine maintenance.
# See docs/runbooks/keycloak-realm.md.
#
# What this does instead, in three steps that never touch a user:
#
#   1. no realm yet        -> full import, which is safe precisely because there
#                             is nobody in it to lose
#   2. realm settings      -> PUT of the realm representation minus its
#                             collections (token lifespans, login policy, ...)
#   3. clients and roles   -> partialImport with ifResourceExists=OVERWRITE
#   4. service accounts    -> a client PUT, because partialImport skips them
#   5. their realm roles   -> granted, because step 3 excludes users and a
#                             service account's roles are declared on one
#
# Users are excluded from step 3 deliberately and always. The realm file's users
# are development fixtures; a real installation's people arrive by signing in,
# and an OVERWRITE of a user is a password and a set of attributes replaced by
# whatever this repository happened to say.
#
# A SERVICE ACCOUNT IS NOT A PERSON, and step 5 is the one exception. Its user
# exists only because a client authenticates as itself, it has no password and
# nobody signs in as it, and the roles it needs are declared in the realm file
# on that user -- which step 3 skips. Before step 5 existed, a service added by
# partialImport got a client and no role, and every call it made was refused by
# a gate working exactly as designed. The three services present at the realm's
# first FULL import were fine, which is why nobody noticed for two of them.
#
# Usage:  bash scripts/realm-apply.sh
# Env:    KEYCLOAK_URL (default http://localhost:8081)
#         KC_ADMIN_USER / KC_ADMIN_PASSWORD (default admin/admin)
#         REALM_FILE (default local/keycloak/realm-xenopslearn.json)
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
PY="$(bash "$HERE/python.sh")"

KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8081}"
KC_ADMIN_USER="${KC_ADMIN_USER:-admin}"
KC_ADMIN_PASSWORD="${KC_ADMIN_PASSWORD:-admin}"
REALM_FILE="${REALM_FILE:-$ROOT/local/keycloak/realm-xenopslearn.json}"

[ -f "$REALM_FILE" ] || { echo "No realm file at $REALM_FILE" >&2; exit 1; }
REALM="$("$PY" -c "import json,sys;print(json.load(open(sys.argv[1],encoding='utf-8'))['realm'])" "$REALM_FILE")"

# ------------------------------------------------------------------ the guard
#
# REFUSING THE LOCAL REALM FILE AGAINST A REMOTE KEYCLOAK.
#
# This script partial-imports clients with ifResourceExists=OVERWRITE, so it
# replaces a client's redirect URIs with whatever the file says. Point it at a
# cluster with the DEFAULT file -- the local one, whose gateway client lists
# http://localhost:8080 -- and it removes the cluster's own hostname from the
# realm.
#
# That happened. Sign-in then fails at Keycloak with "Invalid parameter:
# redirect_uri" AFTER a successful login, which is the worst place for it: the
# person has authenticated, and the error names something only an administrator
# can fix.
#
# The cluster realm lives in xenopsbase-stemcell
# (platform/envs/dev/keycloak/learn-realm-import.yaml). Applying it means
# rendering that file's spec.realm and naming it here.
if [ "${REALM_FILE}" = "$ROOT/local/keycloak/realm-xenopslearn.json" ]; then
    case "$KEYCLOAK_URL" in
        http://localhost:*|http://127.0.0.1:*|https://localhost:*)
            ;;
        *)
            cat >&2 <<REFUSED
This is the LOCAL realm file, and $KEYCLOAK_URL is not a local Keycloak.

Its gateway client lists http://localhost:8080 and nothing else. Applying it
here would overwrite that realm's redirect URIs with localhost ones, and every
sign-in afterwards fails with "Invalid parameter: redirect_uri" -- after the
person has already authenticated.

The realm for a cluster is defined in xenopsbase-stemcell at
platform/envs/dev/keycloak/learn-realm-import.yaml. Render its spec.realm to a
file and name it:

    REALM_FILE=/path/to/that.json KEYCLOAK_URL=$KEYCLOAK_URL make realm-apply

Set REALM_FILE explicitly to this same path if you genuinely mean to apply the
local realm to a remote Keycloak.
REFUSED
            exit 1
            ;;
    esac
fi

admin_token() {
    curl -sf -X POST "$KEYCLOAK_URL/realms/master/protocol/openid-connect/token" \
        -d grant_type=password -d client_id=admin-cli \
        --data-urlencode "username=$KC_ADMIN_USER" \
        --data-urlencode "password=$KC_ADMIN_PASSWORD" \
    | "$PY" -c "import json,sys;print(json.load(sys.stdin)['access_token'])"
}

TOKEN="$(admin_token)" || { echo "Could not authenticate to $KEYCLOAK_URL as $KC_ADMIN_USER" >&2; exit 1; }

STATUS="$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $TOKEN" \
    "$KEYCLOAK_URL/admin/realms/$REALM")"

if [ "$STATUS" = "404" ]; then
    echo "Realm '$REALM' does not exist here: importing it whole (nobody to lose)."
    curl -sf -X POST "$KEYCLOAK_URL/admin/realms" \
        -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
        --data-binary "@$REALM_FILE" >/dev/null
    echo "Imported $REALM."
    exit 0
fi

echo "Realm '$REALM' exists: updating settings and resources, leaving users alone."

"$PY" - "$REALM_FILE" <<'SETTINGS' > /tmp/realm-settings.json
import json, sys
realm = json.load(open(sys.argv[1], encoding="utf-8"))
# Everything except the collections. A PUT carrying users or clients would ask
# Keycloak to reconcile them, which is the reconciliation this script exists to
# avoid.
settings = {k: v for k, v in realm.items()
            if k not in ("users", "clients", "roles", "groups", "components",
                         "identityProviders", "clientScopes")}
json.dump(settings, sys.stdout)
SETTINGS

curl -sf -X PUT "$KEYCLOAK_URL/admin/realms/$REALM" \
    -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
    --data-binary @/tmp/realm-settings.json >/dev/null
echo "  settings applied"

"$PY" - "$REALM_FILE" <<'PARTIAL' > /tmp/realm-partial.json
import json, sys
realm = json.load(open(sys.argv[1], encoding="utf-8"))
partial = {"ifResourceExists": "OVERWRITE"}
for key in ("clients", "roles", "groups", "identityProviders"):
    if key in realm:
        partial[key] = realm[key]
# NOT users. Never users. See the header.
json.dump(partial, sys.stdout)
PARTIAL

RESULT="$(curl -sf -X POST "$KEYCLOAK_URL/admin/realms/$REALM/partialImport" \
    -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
    --data-binary @/tmp/realm-partial.json)"
rm -f /tmp/realm-settings.json /tmp/realm-partial.json

echo "  resources applied: $(echo "$RESULT" | "$PY" -c "
import json,sys
r = json.load(sys.stdin)
print(f\"{r.get('overwritten', 0)} overwritten, {r.get('added', 0)} added, {r.get('skipped', 0)} skipped\")
")"
# ---------------------------------------------------------------------------
# 4. service accounts, which partialImport does not create.
#
# A client with serviceAccountsEnabled needs a hidden user to BE when it
# authenticates as itself. partialImport writes the client and stops there, and
# the failure arrives later and reads as a credentials problem:
#
#   {"error":"invalid_request",
#    "error_description":"The associated service account for the client does not exist"}
#
# A PUT of the client Keycloak already has is enough to make it create the
# account. Doing it unconditionally is safe -- the body is what the server just
# returned -- and it is idempotent, because the second run finds the account
# already there.
# ---------------------------------------------------------------------------
for CLIENT_ID in $("$PY" - "$REALM_FILE" <<'SVC'
import json, sys
realm = json.load(open(sys.argv[1], encoding="utf-8"))
for client in realm.get("clients", []):
    if client.get("serviceAccountsEnabled"):
        print(client["clientId"])
SVC
); do
    # Not -f, and tolerant of an empty body: a client that is somehow absent is a thing to
    # skip, not a reason to abort a run that has already applied everything else.
    FOUND="$(curl -s -H "Authorization: Bearer $TOKEN"         "$KEYCLOAK_URL/admin/realms/$REALM/clients?clientId=$CLIENT_ID" || true)"
    UUID="$(printf '%s' "$FOUND" | "$PY" -c "
import json, sys
raw = sys.stdin.read().strip()
try:
    clients = json.loads(raw) if raw else []
except ValueError:
    clients = []
print(clients[0]['id'] if clients else '')
")"
    [ -n "$UUID" ] || continue
    if curl -sf -o /dev/null -H "Authorization: Bearer $TOKEN"         "$KEYCLOAK_URL/admin/realms/$REALM/clients/$UUID/service-account-user"; then
        continue
    fi
    curl -sf -H "Authorization: Bearer $TOKEN"         "$KEYCLOAK_URL/admin/realms/$REALM/clients/$UUID" > /tmp/realm-client.json
    curl -sf -o /dev/null -X PUT -H "Authorization: Bearer $TOKEN"         -H "Content-Type: application/json" --data-binary @/tmp/realm-client.json         "$KEYCLOAK_URL/admin/realms/$REALM/clients/$UUID"
    rm -f /tmp/realm-client.json
    echo "  service account created for $CLIENT_ID"
done

# ---------------------------------------------------------------------------
# 5. the realm roles those service accounts are declared with.
#
# Step 3 excludes users, and a service account's roles live on a user. So a
# client added by partialImport arrives able to authenticate and unable to do
# anything: identity gates inter-service calls on `svc-caller` (T-9.11), and
# without it every call is refused by a gate that is working correctly.
#
# Only ever service accounts, and only ever the roles the realm file names for
# them. This does not touch a person, and it does not remove a role somebody
# granted by hand -- it adds what is declared and leaves the rest alone.
# ---------------------------------------------------------------------------
"$PY" - "$REALM_FILE" <<'ROLES' > /tmp/realm-svc-roles.txt
import json, sys

realm = json.load(open(sys.argv[1], encoding="utf-8"))
for user in realm.get("users", []):
    client = user.get("serviceAccountClientId")
    for role in user.get("realmRoles", []) if client else []:
        print(client, role)
ROLES

while read -r CLIENT_ID ROLE; do
    [ -n "$CLIENT_ID" ] || continue

    UUID="$(curl -s -H "Authorization: Bearer $TOKEN" \
        "$KEYCLOAK_URL/admin/realms/$REALM/clients?clientId=$CLIENT_ID" \
        | "$PY" -c "
import json, sys
raw = sys.stdin.read().strip()
clients = json.loads(raw) if raw else []
print(clients[0]['id'] if clients else '')
")"
    [ -n "$UUID" ] || continue

    ACCOUNT="$(curl -s -H "Authorization: Bearer $TOKEN" \
        "$KEYCLOAK_URL/admin/realms/$REALM/clients/$UUID/service-account-user" \
        | "$PY" -c "
import json, sys
raw = sys.stdin.read().strip()
try:
    print((json.loads(raw) if raw else {}).get('id', ''))
except ValueError:
    print('')
")"
    [ -n "$ACCOUNT" ] || continue

    # Already granted is the common case, and re-POSTing a held role is not an
    # error to Keycloak -- but asking first keeps the output honest about what
    # this run actually changed.
    if curl -s -H "Authorization: Bearer $TOKEN" \
        "$KEYCLOAK_URL/admin/realms/$REALM/users/$ACCOUNT/role-mappings/realm" \
        | grep -q "\"$ROLE\""; then
        continue
    fi

    REPRESENTATION="$(curl -sf -H "Authorization: Bearer $TOKEN" \
        "$KEYCLOAK_URL/admin/realms/$REALM/roles/$ROLE" || true)"
    if [ -z "$REPRESENTATION" ]; then
        echo "  WARNING: realm role $ROLE does not exist; $CLIENT_ID cannot be granted it" >&2
        continue
    fi

    printf '[%s]' "$REPRESENTATION" > /tmp/realm-role.json
    curl -sf -o /dev/null -X POST -H "Authorization: Bearer $TOKEN" \
        -H "Content-Type: application/json" --data-binary @/tmp/realm-role.json \
        "$KEYCLOAK_URL/admin/realms/$REALM/users/$ACCOUNT/role-mappings/realm"
    rm -f /tmp/realm-role.json
    echo "  granted $ROLE to $CLIENT_ID"
done < /tmp/realm-svc-roles.txt
rm -f /tmp/realm-svc-roles.txt

echo "No person was created, changed or removed."
