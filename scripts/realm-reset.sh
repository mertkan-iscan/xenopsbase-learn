#!/usr/bin/env bash
#
# DESTROYS a realm and imports it again from this repository (T-1.7).
#
# DEVELOPMENT ONLY, AND THE CONSEQUENCE IS NOT ABSTRACT: deleting a realm
# deletes every account in it. Passwords, sessions, consents, federated links,
# and -- unless the import declares an explicit id for the user -- the internal
# id that this platform stores as app_user.idp_sub. A realm rebuilt without
# declared ids leaves every person in the product pointing at a subject that no
# longer exists, and they meet it as "this email already belongs to an existing
# user with a different identity link" on their next sign-in.
#
# It is survivable (ADR-0104 keeps ownership in our own id, and
# scripts/realm-relink.sh repairs the link), which is exactly why it must not be
# routine. Use scripts/realm-apply.sh anywhere that has real people.
#
# THREE GUARDS, because this reads as routine maintenance:
#
#   1. the Keycloak has to be on this machine        (localhost / 127.0.0.1)
#   2. the realm must contain nobody this repository does not declare
#   3. CONFIRM has to name the realm being destroyed
#
# Guard 2 is the one that matters. It is what tells a real installation apart
# from a development one without asking anybody to remember which is which: a
# realm with a single account nobody wrote into the file is an installation with
# users, and this refuses to run against it.
#
# Usage:  CONFIRM=destroy-xenopslearn bash scripts/realm-reset.sh
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

# ---- guard 1: this machine only ------------------------------------------
HOST="$("$PY" -c "import sys,urllib.parse;print(urllib.parse.urlparse(sys.argv[1]).hostname or '')" "$KEYCLOAK_URL")"
case "$HOST" in
    localhost|127.0.0.1|::1) ;;
    *)
        echo "REFUSED: $KEYCLOAK_URL is not this machine." >&2
        echo "This script destroys every account in the realm. Changing a realm that has real" >&2
        echo "people in it is scripts/realm-apply.sh, which never touches a user." >&2
        exit 1
        ;;
esac

# ---- guard 3 (checked before touching anything) ---------------------------
if [ "${CONFIRM:-}" != "destroy-$REALM" ]; then
    echo "REFUSED: set CONFIRM=destroy-$REALM to confirm you mean to delete realm '$REALM'" >&2
    echo "and every account in it. See docs/runbooks/keycloak-realm.md." >&2
    exit 1
fi

TOKEN="$(curl -sf -X POST "$KEYCLOAK_URL/realms/master/protocol/openid-connect/token" \
    -d grant_type=password -d client_id=admin-cli \
    --data-urlencode "username=$KC_ADMIN_USER" \
    --data-urlencode "password=$KC_ADMIN_PASSWORD" \
    | "$PY" -c "import json,sys;print(json.load(sys.stdin)['access_token'])")" \
    || { echo "Could not authenticate to $KEYCLOAK_URL" >&2; exit 1; }

STATUS="$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $TOKEN" \
    "$KEYCLOAK_URL/admin/realms/$REALM")"

if [ "$STATUS" != "404" ]; then
    # ---- guard 2: nobody in there that this repository did not put there --
    LIVE="$(curl -sf -H "Authorization: Bearer $TOKEN" \
        "$KEYCLOAK_URL/admin/realms/$REALM/users?briefRepresentation=true&max=1000")"
    # The program goes in a file rather than on stdin, because stdin is already
    # carrying the realm's users: a heredoc'd program and piped data are the same
    # channel, and the loser is whichever one you thought you were reading.
    CHECK="$(mktemp)"
    cat > "$CHECK" <<'PYTHON'
import json, sys
declared = {u["username"].lower() for u in json.load(open(sys.argv[1], encoding="utf-8"))["users"]}
live = json.load(sys.stdin)
print(chr(10).join(sorted(u["username"] for u in live if u["username"].lower() not in declared)))
PYTHON
    UNDECLARED="$(printf '%s' "$LIVE" | "$PY" "$CHECK" "$REALM_FILE")"
    rm -f "$CHECK"
    if [ -n "$UNDECLARED" ]; then
        echo "REFUSED: realm '$REALM' contains accounts this repository does not declare:" >&2
        printf '  %s\n' $UNDECLARED >&2
        echo "" >&2
        echo "That makes this an installation with users, not a development realm. Deleting it" >&2
        echo "would delete them. Use scripts/realm-apply.sh instead; if you really are recycling" >&2
        echo "a development realm somebody signed into, delete those accounts first." >&2
        exit 1
    fi

    echo "Deleting realm '$REALM' and everything in it."
    curl -sf -X DELETE -H "Authorization: Bearer $TOKEN" "$KEYCLOAK_URL/admin/realms/$REALM" >/dev/null
fi

curl -sf -X POST "$KEYCLOAK_URL/admin/realms" \
    -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
    --data-binary "@$REALM_FILE" >/dev/null

echo "Realm '$REALM' imported from $(basename "$REALM_FILE")."
echo "Every declared user carries an explicit id, so subjects are unchanged and no re-link is"
echo "needed. If you imported a realm WITHOUT declared ids, run scripts/realm-relink.sh."
