#!/usr/bin/env bash
#
# Prints the path of a Python that actually RUNS. Nothing else, so a caller can
# do:
#
#   PY="$(bash scripts/python.sh)" && "$PY" -c 'print("hello")'
#
# WHY THIS EXISTS, AND WHY IT RUNS EACH CANDIDATE
#
# On Windows, `python3` is very often a Microsoft Store stub: a real executable,
# on PATH, that `command -v python3` finds happily and that does nothing at all
# when you run it -- it opens the Store, prints nothing, and exits 9009. Every
# "check the tool exists" idiom passes and the next line fails with an empty
# error, which is the worst kind of failure to read in a script somebody is
# running because something else already went wrong.
#
# So existence is not the test. Running is: each candidate has to print back a
# word before this will hand out its path.
set -euo pipefail

for candidate in python3 python py; do
    if ! command -v "$candidate" >/dev/null 2>&1; then
        continue
    fi
    if [ "$("$candidate" -c 'print("works")' 2>/dev/null || true)" = "works" ]; then
        command -v "$candidate"
        exit 0
    fi
done

echo "No working Python on PATH. Tried python3, python and py; each was either" >&2
echo "missing or refused to run (on Windows, python3 is usually a Store stub)." >&2
exit 1
