#!/usr/bin/env bash
#
# Proves what the Cloudflare credentials can actually do, by doing it (T-9.14).
#
# THE CRITERION THIS EXISTS FOR: "token permissions proved by making the calls,
# not by reading the dashboard". A token's scope list in the dashboard is a
# description of intent. What matters is whether the four calls this platform
# makes are answered, and the way that normally goes wrong is silent: a token
# created with Stream:Read where Stream:Edit was meant looks correct on the
# permissions page, works for every GET somebody tries by hand, and fails on the
# first real upload -- which is weeks later, in an environment nobody is
# watching, and reads as a bug in the upload path.
#
# So this makes each call for real, creates and then deletes what it creates,
# and reports 403 separately from every other failure: a 403 is a permission
# answer, and anything else is a request this script got wrong.
#
# IT NEVER PRINTS A SECRET, and it never needs one written to a file. Export the
# variables in your own shell and run it; the values stay in your environment.
#
# Usage:
#     export CF_STREAM_ACCOUNT_ID=...      # Cloudflare account id
#     export CF_STREAM_API_TOKEN=...       # the Stream token
#     export CF_R2_BUCKET=...              # optional: the content bucket to check
#     bash scripts/cloudflare-check.sh
#
# Exit status is 0 only if every required capability is proved.
set -uo pipefail

API="https://api.cloudflare.com/client/v4"
PASS=0
FAIL=0
CREATED_UID=""

green() { printf '  \033[32mPASS\033[0m  %s\n' "$1"; PASS=$((PASS + 1)); }
red()   { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; FAIL=$((FAIL + 1)); }
note()  { printf '        %s\n' "$1"; }
head2() { printf '\n%s\n' "$1"; }

require() {
    local name="$1"
    if [ -z "${!name:-}" ]; then
        echo "Set $name before running this. It is read from your environment and never stored." >&2
        exit 2
    fi
}

require CF_STREAM_ACCOUNT_ID
require CF_STREAM_API_TOKEN

# Every call goes through here so that no invocation can accidentally echo the
# token: the header is built inside the function and the body is all that comes
# back. Prints "<status>\n<body>".
call() {
    local method="$1" url="$2"
    shift 2
    curl -s -o /tmp/cf-body.$$ -w '%{http_code}' -X "$method" "$url" \
        -H "Authorization: Bearer $CF_STREAM_API_TOKEN" "$@"
}

body() { cat /tmp/cf-body.$$ 2>/dev/null; }

# A 403 means the token was understood and refused, which is the answer this
# script is for. Anything else non-2xx is reported as itself so nobody spends an
# afternoon widening a token to fix a malformed request.
verdict() {
    local status="$1" what="$2"
    case "$status" in
        2*) green "$what"; return 0 ;;
        403) red "$what -- FORBIDDEN: the token lacks this permission"; return 1 ;;
        401) red "$what -- UNAUTHORIZED: the token is wrong, expired, or for another account"; return 1 ;;
        404) red "$what -- NOT FOUND: check CF_STREAM_ACCOUNT_ID"; return 1 ;;
        *)
            # Cloudflare's 10011 is worth naming rather than dumping, because it is the
            # opposite of what a failure here looks like: authorization ran BEFORE the quota
            # check, so reaching this error means the token was accepted and it is the ACCOUNT
            # that has nothing. Enabling Stream in the dashboard allocates no capacity -- the
            # minutes are bought. Left as a generic failure it reads as a broken token, which
            # is the one conclusion it rules out.
            if body | grep -q '"code": *10011'; then
                red "$what -- the token is fine; this ACCOUNT has no Stream capacity"
                note "Cloudflare says: allocated 0 minutes. Stream is a paid feature and"
                note "enabling it does not buy any."
                note "  Cloudflare dashboard > Stream > subscribe (minutes are sold in blocks)"
                note "Authorization runs before this check, so reaching it proves the token"
                note "carries Stream:Edit. Re-run this script after buying to confirm the"
                note "whole create path end to end."
                return 1
            fi
            red "$what -- HTTP $status (not a permission problem; see below)"
            note "$(body | head -c 400)"
            return 1 ;;
    esac
}

cleanup() {
    if [ -n "$CREATED_UID" ]; then
        call DELETE "$API/accounts/$CF_STREAM_ACCOUNT_ID/stream/$CREATED_UID" >/dev/null
    fi
    rm -f /tmp/cf-body.$$
}
trap cleanup EXIT

echo "Cloudflare capability check for account ${CF_STREAM_ACCOUNT_ID:0:6}..."
echo "(nothing here prints a credential)"

# ---------------------------------------------------------------------------
# 1. Is this token alive at all.
# ---------------------------------------------------------------------------
head2 "Token"
STATUS="$(call GET "$API/user/tokens/verify")"
verdict "$STATUS" "token is valid and active"
if [ "$STATUS" != "200" ]; then
    echo
    echo "Nothing else can be checked until the token verifies. $FAIL failed."
    exit 1
fi

# ---------------------------------------------------------------------------
# 2. Stream:Edit -- the upload path (T-3.2). This is the one that is usually
#    wrong, because Stream:Read passes everything a person tries by hand.
# ---------------------------------------------------------------------------
head2 "Stream"
STATUS="$(call POST "$API/accounts/$CF_STREAM_ACCOUNT_ID/stream?direct_user=true" \
    -H 'Tus-Resumable: 1.0.0' \
    -H 'Upload-Length: 1' \
    -H 'Upload-Metadata: maxdurationseconds NjA=')"
if verdict "$STATUS" "Stream:Edit -- can create an upload target (this is what T-3.2 does)"; then
    # tus returns the id in a header, so ask for the headers of that same call.
    CREATED_UID="$(curl -s -D - -o /dev/null -X POST \
        "$API/accounts/$CF_STREAM_ACCOUNT_ID/stream?direct_user=true" \
        -H "Authorization: Bearer $CF_STREAM_API_TOKEN" \
        -H 'Tus-Resumable: 1.0.0' -H 'Upload-Length: 1' \
        -H 'Upload-Metadata: maxdurationseconds NjA=' \
        | tr -d '\r' | awk -F': ' '/^stream-media-id:/ {print $2}')"
    [ -n "$CREATED_UID" ] && note "created ${CREATED_UID:0:8}... (deleted when this script exits)"
fi

if [ -n "$CREATED_UID" ]; then
    STATUS="$(call GET "$API/accounts/$CF_STREAM_ACCOUNT_ID/stream/$CREATED_UID")"
    verdict "$STATUS" "Stream:Read -- can poll an asset's encode state (T-3.3's reconciler)"

    # The delivery host every manifest URL is served from. DERIVED by making a
    # call rather than copied off a dashboard page, because it is one of the
    # values the adapter needs and a wrong one fails only at playback.
    SUBDOMAIN="$(body | tr ',' '\n' | grep -o 'customer-[a-z0-9]*\.cloudflarestream\.com' | head -1)"
    if [ -n "$SUBDOMAIN" ]; then
        green "delivery host resolved: $SUBDOMAIN"
        note "export CF_STREAM_CUSTOMER_SUBDOMAIN=${SUBDOMAIN%%.*}"
        note "  (the part after 'customer-': ${SUBDOMAIN#customer-}; strip '.cloudflarestream.com')"
    else
        red "could not read the delivery host from the asset -- CF_STREAM_CUSTOMER_SUBDOMAIN unknown"
    fi
fi

# ---------------------------------------------------------------------------
# 3. Signing keys. ADR-0101 is explicit that minting a playback token must cost
#    zero network calls, so the private key lives with us and is used locally.
# ---------------------------------------------------------------------------
head2 "Signing"
STATUS="$(call GET "$API/accounts/$CF_STREAM_ACCOUNT_ID/stream/keys")"
if verdict "$STATUS" "can list signing keys"; then
    KEYS="$(body | grep -o '"id"' | wc -l | tr -d ' ')"
    if [ "$KEYS" = "0" ]; then
        note "no signing key yet. Mint ONE, once, and keep the response -- the private"
        note "key is returned only at creation and Cloudflare does not store it:"
        # Double quotes, not single: the reader pastes this, and inside single quotes the
        # variables would stay literal and the request would go to a path spelled
        # /accounts/$CF_STREAM_ACCOUNT_ID/ -- which fails in a way that looks like the account
        # is wrong rather than like the instruction was.
        note "  curl -X POST \"$API/accounts/\$CF_STREAM_ACCOUNT_ID/stream/keys\" \\"
        note "       -H \"Authorization: Bearer \$CF_STREAM_API_TOKEN\""
        note "then export CF_STREAM_SIGNING_KEY_ID=<result.id>"
        note "     and CF_STREAM_SIGNING_KEY_JWK=<result.jwk>"
    else
        note "$KEYS signing key(s) present"
        note "If you do not hold the private JWK for one of them, mint a new key --"
        note "the JWK is returned only at creation and cannot be read back."
    fi
fi

# ---------------------------------------------------------------------------
# 4. R2. Optional, because the content origin is T-9.5's and may not exist yet.
# ---------------------------------------------------------------------------
head2 "R2"
if [ -z "${CF_R2_BUCKET:-}" ]; then
    note "CF_R2_BUCKET not set; skipping. Set it once the content bucket exists (T-9.5)."
else
    STATUS="$(call GET "$API/accounts/$CF_STREAM_ACCOUNT_ID/r2/buckets")"
    if verdict "$STATUS" "can list R2 buckets"; then
        if body | grep -q "\"name\":\"$CF_R2_BUCKET\""; then
            green "bucket '$CF_R2_BUCKET' exists"
        else
            red "bucket '$CF_R2_BUCKET' not found in this account"
        fi
    fi
    # A bucket whose r2.dev public URL is on is a bucket serving objects with no
    # signature at all, which is the opposite of "signed and time-limited".
    STATUS="$(call GET "$API/accounts/$CF_STREAM_ACCOUNT_ID/r2/buckets/$CF_R2_BUCKET/domains/managed")"
    if [ "$STATUS" = "200" ]; then
        if body | grep -q '"enabled":true'; then
            red "the bucket's public r2.dev URL is ENABLED -- objects are readable with no signature"
            note "turn it off: R2 > $CF_R2_BUCKET > Settings > Public Development URL"
        else
            green "public r2.dev URL is disabled (access must be signed)"
        fi
    else
        note "could not read the managed-domain setting (HTTP $STATUS); check it by hand"
    fi
fi

head2 "Result"
echo "  $PASS proved, $FAIL failed"
if [ "$FAIL" -gt 0 ]; then
    echo
    echo "A FORBIDDEN above is a token scope to fix. Anything else is this script or the"
    echo "account, not your permissions."
    exit 1
fi
echo
echo "Every capability this platform uses is proved. Record the date and who holds"
echo "the token in docs/runbooks/cloudflare.md."
