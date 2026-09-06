#!/usr/bin/env bash
#
# The list of services, read from the one place that already has to be right.
#
# WHY THIS EXISTS, and it is not hypothetical here. `.github/workflows/ci.yml`
# carried `service: [identity, streaming, reporting]` as a typed list. T-5.1
# added `catalog` as a module with jib enabled -- a deployable service -- and
# nobody edited the workflow, so catalog was compiled and tested on every run
# and never published once. GHCR answers 404 for it. Nothing failed, because a
# workflow that never builds a service is a green workflow.
#
# Ported from xenopsbase-stemcell, where the same list had six copies.
#
# services/pom.xml's <modules> is the list Maven itself uses, so it cannot be
# stale without the build breaking. Everything else derives from it.
#
# Usage:
#   service-modules.sh            deployable services, space-separated  -> "gateway core"
#   service-modules.sh --all      every module, in build order          -> "platform-common ... core"
#   service-modules.sh --json     deployable services as a JSON array, for a GitHub Actions matrix
#
# "Deployable" means everything except the shared libraries, which are named by
# convention: platform-common and platform-common-web. A module is a service if
# it is not one of those -- so a new library must follow the naming, and a new
# service needs no edit here at all.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
POM="$ROOT/services/pom.xml"

if [ ! -f "$POM" ]; then
  echo "service-modules.sh: no $POM" >&2
  exit 1
fi

# Only the <modules> block, so a <module> mentioned in a comment or a profile
# elsewhere in the file cannot leak into the list.
all_modules() {
  sed -n '/<modules>/,/<\/modules>/p' "$POM" \
    | grep -oE '<module>[^<]+</module>' \
    | sed -e 's|<module>||' -e 's|</module>||'
}

services() {
  all_modules | grep -vE '^platform-common(-web)?$' || true
}

case "${1:-}" in
  --all)
    all_modules | tr '\n' ' ' | sed 's/ $//'
    echo
    ;;
  --json)
    # Built in the shell rather than piped through python3: on Windows `python3`
    # is a Microsoft Store stub that satisfies `command -v` and then refuses to
    # run, so a python one-liner here works in CI and fails on a developer
    # machine -- the worst of the two places to find out.
    printf '['
    sep=''
    while read -r m; do
      [ -n "$m" ] || continue
      printf '%s"%s"' "$sep" "$m"
      sep=', '
    done < <(services)
    printf ']
'
    ;;
  "")
    services | tr '\n' ' ' | sed 's/ $//'
    echo
    ;;
  *)
    echo "service-modules.sh: unknown option $1" >&2
    exit 2
    ;;
esac
