#!/bin/sh
# Buckets, created every start and idempotent (T-9.9).
#
# Same names and same layout the real object storage will use, so the adapter
# and its configuration do not change between here and Hetzner or R2 -- only
# the endpoint and the credentials do.
set -e

mc alias set local http://minio:9000 "$S3_ACCESS_KEY" "$S3_SECRET_KEY"

# uploads   -- source files an author uploads, before processing
# packages  -- extracted SCORM/cmi5 bundles and rasterised slides. This is the
#              ONE bucket whose contents reach a browser, and only through the
#              content origin on :8090 -- never through the application origin.
#              A browser never talks to MinIO for it: the content origin proxies
#              to the packaging service, which holds the credential and decides
#              the content type of every file it serves (ADR-0105).
# exports   -- generated reports, fetched by expiring presigned link (T-7.8)
for b in uploads packages exports; do
  mc mb --ignore-existing "local/$b"
done

# Nothing is public. Even `packages`, whose contents browsers see: they are
# fetched by the packaging service, with this credential, and re-served on the
# content origin. A public bucket would make every tenant's uploaded content
# world-readable by URL guess, and it would do so silently.
for b in uploads packages exports; do
  mc anonymous set none "local/$b" >/dev/null 2>&1 || true
done

echo "buckets ready: uploads packages exports"
