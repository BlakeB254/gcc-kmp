#!/bin/bash
# Start GCC Ktor server with production env vars
set -a
source "$(dirname "$0")/.env"
set +a
cd "$(dirname "$0")"
exec ./gradlew :server:run
