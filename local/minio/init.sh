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
#              ONE bucket served to browsers, and only through the content
#              origin on :8090 -- never through the application origin.
# exports   -- generated reports, fetched by expiring presigned link (T-7.8)
for b in uploads packages exports; do
  mc mb --ignore-existing "local/$b"
done

# Nothing is public. Even `packages`, which browsers read: it is reached through
# the content origin, which will sign for it. A public bucket would make every
# tenant's uploaded content world-readable by URL guess, and it would do so
# silently.
for b in uploads packages exports; do
  mc anonymous set none "local/$b" >/dev/null 2>&1 || true
done

echo "buckets ready: uploads packages exports"
