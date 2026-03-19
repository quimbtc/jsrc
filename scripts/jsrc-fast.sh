#!/usr/bin/env bash
# Fast jsrc launcher with startup optimization.
# Supports HotSpot CDS and OpenJ9 shared class cache.
# Generate cache first: ./scripts/generate-cds.sh

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR="$SCRIPT_DIR/../target/jsrc.jar"
CDS_ARCHIVE="$SCRIPT_DIR/../target/jsrc.jsa"

if java -version 2>&1 | grep -q "OpenJ9"; then
    exec java -Xshareclasses:name=jsrc -jar "$JAR" "$@"
elif [ -f "$CDS_ARCHIVE" ]; then
    exec java -XX:SharedArchiveFile="$CDS_ARCHIVE" -jar "$JAR" "$@"
else
    exec java -jar "$JAR" "$@"
fi
