# -*- coding: utf-8 -*-
"""Builds docs/api-surface.md from the committed OpenAPI descriptions.

Generated rather than written, for the reason api.mjs gives about the specs
themselves: a hand-kept inventory of 114 endpoints is one that stops matching the
code, and the first person to notice is whoever designed a screen around a call
that does not exist.
"""
import io
import json
import os
import re

ROOT = "web/api"
SERVICES = [
    ("identity", "Identity", "People, companies, groups, roles and sign-in."),
    ("catalog", "Catalog", "What training exists, who it reaches, and what is pinned inside it."),
    ("streaming", "Streaming", "Playback tokens and watched-interval progress."),
    ("assessment", "Assessment", "Banks, questions, tests, attempts and marking."),
    ("reporting", "Reporting", "Telemetry ingest."),
    ("packaging", "Packaging",
     "Uploaded SCORM, cmi5 and slide packages. **The two `/served/` routes are not "
     "reachable from a browser on the application's origin, and that is the whole "
     "decision:** they answer the tenant's CONTENT ORIGIN, which proxies to them, and "
     "the gateway has no route to them at all (ADR-0105, `UpstreamsTest`). They are "
     "listed because this service serves them, not because a page here may call them."),
]
METHODS = ["get", "post", "put", "patch", "delete"]


def spec(name):
    with io.open(os.path.join(ROOT, name + "-openapi.json"), encoding="utf-8") as handle:
        return json.load(handle)


def humanise(operation_id):
    """`setPermissions_1` -> `set permissions`. Springdoc names these after the method."""
    without_suffix = re.sub(r"_\d+$", "", operation_id or "")
    spaced = re.sub(r"(?<!^)(?=[A-Z])", " ", without_suffix)
    return spaced.replace("_", " ").strip().lower()


def summarise(operation):
    """What the endpoint answers with, in the words the service itself used.

    Springdoc writes "OK" when nobody declared an @ApiResponse, which is true and
    useless on a design brief -- so the operation's own name is the better
    fallback. Both are the service's, and neither is invented here.
    """
    summary = operation.get("summary") or operation.get("description") or ""
    if summary.strip():
        return " ".join(summary.split())
    for status in ("200", "201", "202", "204"):
        response = (operation.get("responses") or {}).get(status)
        description = (response or {}).get("description", "")
        if description and description.strip() not in ("OK", "Created", "No Content", "Accepted"):
            return " ".join(description.split())
    return humanise(operation.get("operationId")) or "-"


def refusals(operation):
    codes = [code for code in sorted((operation.get("responses") or {}).keys())
             if code[0] in "45"]
    return ", ".join(codes)


def learner_facing(path):
    return "/me/" in path or path.endswith("/me")


lines = []
total = 0
rows_by_service = {}

for name, title, _ in SERVICES:
    document = spec(name)
    rows = []
    for path, item in sorted((document.get("paths") or {}).items()):
        for method in METHODS:
            operation = item.get(method)
            if not operation:
                continue
            total += 1
            rows.append((method.upper(), path, summarise(operation), refusals(operation)))
    rows_by_service[name] = rows

lines.append("# The API surface, in one page")
lines.append("")
lines.append("**Generated** by `scripts/api_surface.py` from the OpenAPI descriptions in "
             "`web/api/`, which `npm run api:check` proves match the running services. Regenerate "
             "it rather than editing it: a hand-kept inventory of %d endpoints is one that stops "
             "matching the code, and the first person to notice is whoever designed a screen "
             "around a call that does not exist." % total)
lines.append("")
lines.append("## What a browser talks to")
lines.append("")
lines.append("**One origin.** The browser calls the **gateway** and nothing else. It holds the "
             "session — the access and refresh tokens live server-side in Valkey, and no page "
             "ever sees one (T-10.2). Everything under `/api` is relayed inward by path.")
lines.append("")
lines.append("- **Credential:** an opaque session cookie. Writes must echo the CSRF value "
             "(`X-XSRF-TOKEN`, from the `XSRF-TOKEN` cookie).")
lines.append("- **401 means the session ended** and is never a redirect — it is a "
             "`problem+json` with `code: SESSION_ENDED`. Sign in again; do not retry.")
lines.append("- **403** means signed in but not permitted. **404** may mean \"not yours to know "
             "about\" (T-2.4's disclosure rule), so never render it as \"it was deleted\".")
lines.append("- **Every refusal is RFC 9457** `application/problem+json` with a `code` a client "
             "switches on. The `Problem` schema is in every description.")
lines.append("")
lines.append("### Sign-in, which is not under `/api`")
lines.append("")
lines.append("| path | what it does |")
lines.append("|---|---|")
lines.append("| `GET /auth/session` | who is signed in, or 401. Sets the CSRF cookie. |")
lines.append("| `GET /oauth2/authorization/oidc` | starts sign-in (a redirect to the issuer) |")
lines.append("| `POST /auth/logout` | ends the session |")
lines.append("")

lines.append("## The six services, and how many endpoints each owns")
lines.append("")
lines.append("| service | endpoints | owns |")
lines.append("|---|---|---|")
for name, title, blurb in SERVICES:
    lines.append("| **%s** | %d | %s |" % (title, len(rows_by_service[name]), blurb))
lines.append("| | **%d** | |" % total)
lines.append("")

lines.append("## Learner-facing endpoints, all of them")
lines.append("")
lines.append("Everything under `/me/` answers **only about the caller** and takes no learner id. "
             "There is deliberately no version of any of these that answers about somebody else.")
lines.append("")
lines.append("| method | path | service | what it returns |")
lines.append("|---|---|---|---|")
for name, title, _ in SERVICES:
    for method, path, summary, _refusals in rows_by_service[name]:
        if learner_facing(path):
            lines.append("| `%s` | `%s` | %s | %s |" % (method, path, title, summary or "—"))
lines.append("")

for name, title, blurb in SERVICES:
    lines.append("## %s" % title)
    lines.append("")
    lines.append("_%s_" % blurb)
    lines.append("")
    lines.append("| method | path | what it returns | refusals |")
    lines.append("|---|---|---|---|")
    for method, path, summary, refusal in rows_by_service[name]:
        lines.append("| `%s` | `%s` | %s | %s |" % (method, path, summary or "—", refusal or "—"))
    lines.append("")

io.open("docs/api-surface.md", "w", encoding="utf-8", newline="\n").write("\n".join(lines) + "\n")
print("wrote docs/api-surface.md with %d endpoints" % total)
