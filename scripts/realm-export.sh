#!/usr/bin/env bash
#
# Prints the running realm as JSON, so it can be diffed against the file in this
# repository before either one is changed (T-1.7).
#
# Keys sorted and indented, and volatile fields removed, because the point is a
# diff a human reads. Keycloak's export carries generated ids, timestamps and
# defaults it filled in itself; left in, every export differs from every other
# one and the diff stops being evidence of anything.
#
# Users are NOT exported. They are people, their representations carry
# credentials, and this output is meant to be pasted into a review.
#
# Usage:  bash scripts/realm-export.sh > /tmp/live-realm.json
#         diff <(bash scripts/realm-export.sh) local/keycloak/realm-xenopslearn.json
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
PY="$(bash "$HERE/python.sh")"

KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8081}"
KC_ADMIN_USER="${KC_ADMIN_USER:-admin}"
KC_ADMIN_PASSWORD="${KC_ADMIN_PASSWORD:-admin}"
REALM_FILE="${REALM_FILE:-$ROOT/local/keycloak/realm-xenopslearn.json}"
REALM="${REALM:-$("$PY" -c "import json,sys;print(json.load(open(sys.argv[1],encoding='utf-8'))['realm'])" "$REALM_FILE")}"

TOKEN="$(curl -sf -X POST "$KEYCLOAK_URL/realms/master/protocol/openid-connect/token" \
    -d grant_type=password -d client_id=admin-cli \
    --data-urlencode "username=$KC_ADMIN_USER" \
    --data-urlencode "password=$KC_ADMIN_PASSWORD" \
    | "$PY" -c "import json,sys;print(json.load(sys.stdin)['access_token'])")" \
    || { echo "Could not authenticate to $KEYCLOAK_URL" >&2; exit 1; }

curl -sf -H "Authorization: Bearer $TOKEN" \
    "$KEYCLOAK_URL/admin/realms/$REALM?briefRepresentation=false" \
| "$PY" -c "
import json, sys
realm = json.load(sys.stdin)
for volatile in ('id', 'users'):
    realm.pop(volatile, None)
json.dump(realm, sys.stdout, indent=2, sort_keys=True)
print()
"
