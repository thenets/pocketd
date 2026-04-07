#!/bin/sh
# Gradle wrapper — delegates to the local binary downloaded by 'make setup'.
# Run 'make setup' once before using this script.
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
GRADLE_BIN="$SCRIPT_DIR/.cache/gradle-dist/gradle-8.11.1/bin/gradle"

if [ ! -x "$GRADLE_BIN" ]; then
    echo "error: Gradle binary not found at $GRADLE_BIN" >&2
    echo "       Run 'make setup' first." >&2
    exit 1
fi

exec "$GRADLE_BIN" \
    --gradle-user-home "$SCRIPT_DIR/.cache/gradle-home" \
    "$@"
