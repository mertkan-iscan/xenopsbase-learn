# The frontend as an image: a web server and a directory of files (T-10.2's deploy).
#
# WHY THIS IS NOT SERVED BY THE GATEWAY, which would be one fewer image and one
# fewer pod. `docs/frontend.md` says what ships is files, served from the edge,
# "so a learner's page loads when none of our services are running" -- and
# T-3.10 asserts exactly that about playback. Put the app inside the gateway and
# the gateway being down stops the page from loading at all, which turns a
# degraded backend into a blank screen and makes T-3.10's property untrue.
#
# ADR-0109 counts `frontend` as a process that is "static, free". This is what
# free looks like: nginx with a 16Mi request, against 512Mi for a JVM.

# ---------------------------------------------------------------------------
# Build
# ---------------------------------------------------------------------------
FROM node@sha256:e67514e5d0f6c46656005e1b693b2ec9d52e80b641307de684d4a015ba7a4eaf AS build
# 24-alpine, resolved 2026-09-09. Node 24 is the LTS the CI workflow pins; the
# local toolchain runs 25 and both satisfy Vite.

WORKDIR /app

# The lockfile first, so a dependency install is only re-run when dependencies
# change rather than on every source edit.
COPY package.json package-lock.json ./
RUN npm ci

COPY . .

# `build` and not `verify`: linting and tests belong to CI, where a failure is
# reported against a pull request. A Dockerfile that runs them turns every image
# build into a second test run whose output nobody reads.
RUN npm run build

# ---------------------------------------------------------------------------
# Serve
# ---------------------------------------------------------------------------
FROM nginxinc/nginx-unprivileged@sha256:0c79d56aee561a1d81c63f00eee5fb5fe29279560cdc55e91425133104c7fbe6
# 1.29-alpine, resolved 2026-09-09.
#
# nginx-unprivileged rather than nginx: the stock image starts as root to bind
# port 80 and drops afterwards, which a `runAsNonRoot` pod spec refuses outright
# (the same refusal curlimages/curl produces, and the reason that init container
# names a numeric uid). This one binds 8080 as uid 101 and needs no capability.

COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 8080
