#!/usr/bin/env bash
# Launch schema-sync against the bundled demo data.
#
# First run: builds the fatJar and copies demo.db + sources/ into a working
# directory under schema-sync/build/. Subsequent runs reuse what's already
# there. `gradle clean` wipes everything (build/ goes away), so the next run
# starts fresh.
#
# The TUI persists state to a .schema-sync.toml in the cwd; this script cd's
# into the work directory before launching, so the toml stays scoped to the
# demo.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SCHEMA_SYNC_DIR="$(dirname "$SCRIPT_DIR")"
REPO_ROOT="$(dirname "$SCHEMA_SYNC_DIR")"

JAR="$SCHEMA_SYNC_DIR/build/libs/schema-sync-all.jar"
WORK_DIR="$SCHEMA_SYNC_DIR/build/demo-work"

if [[ ! -f "$JAR" ]]; then
    echo "Building schema-sync fatJar..."
    (cd "$REPO_ROOT" && gradle :schema-sync:fatJar -q)
fi

if [[ ! -d "$WORK_DIR" ]]; then
    echo "Materialising demo work dir at $WORK_DIR"
    mkdir -p "$WORK_DIR"
    \cp "$SCRIPT_DIR/demo.db" "$WORK_DIR/demo.db"
    \cp -r "$SCRIPT_DIR/sources" "$WORK_DIR/sources"
fi

cd "$WORK_DIR"
exec java -jar "$JAR" \
    --url "jdbc:sqlite:./demo.db" \
    --sources "./sources" \
    "$@"
