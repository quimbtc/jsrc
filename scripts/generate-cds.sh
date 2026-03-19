#!/usr/bin/env bash
# Generate startup optimization cache for faster jsrc startup.
# Supports both HotSpot CDS and OpenJ9 AOT.
# Run once after building: ./scripts/generate-cds.sh
# Then use: ./scripts/jsrc-fast.sh <args>

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR="$SCRIPT_DIR/../target/jsrc.jar"
CDS_ARCHIVE="$SCRIPT_DIR/../target/jsrc.jsa"

if [ ! -f "$JAR" ]; then
    echo "Error: $JAR not found. Run 'mvn package -DskipTests' first." >&2
    exit 1
fi

# Detect JVM type
if java -version 2>&1 | grep -q "OpenJ9"; then
    echo "OpenJ9 detected — using AOT shared cache."
    # OpenJ9 uses shared class cache (SCC) automatically
    # Warm it up with a training run
    java -Xshareclasses:name=jsrc -jar "$JAR" --help > /dev/null 2>&1 || true
    echo "OpenJ9 shared class cache warmed up."
    echo "Use: java -Xshareclasses:name=jsrc -jar $JAR <args>"
else
    echo "HotSpot detected — generating CDS archive."
    java -XX:ArchiveClassesAtExit="$CDS_ARCHIVE" -jar "$JAR" --help > /dev/null 2>&1 || true
    if [ -f "$CDS_ARCHIVE" ]; then
        echo "CDS archive created: $CDS_ARCHIVE ($(du -h "$CDS_ARCHIVE" | cut -f1))"
    else
        echo "Warning: CDS archive was not created. Your JVM may not support AppCDS." >&2
    fi
fi
