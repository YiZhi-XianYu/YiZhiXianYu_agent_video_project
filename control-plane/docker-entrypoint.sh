#!/bin/sh
set -eu

# Named volumes are initially owned by root. Prepare only the shared top-level
# directories, then run the application without root privileges.
mkdir -p /app/runtime/storage /app/runtime/artifacts /app/runtime/bgm
chown app:app /app/runtime /app/runtime/storage /app/runtime/artifacts /app/runtime/bgm

exec su-exec app:app "$@"
