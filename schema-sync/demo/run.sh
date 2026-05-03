#!/usr/bin/env bash
# Launch schema-sync against one of three demo variants. The DB schema is the
# same across all three; the variants differ only in the Kotlin entity layout
# (constructor style). Pass one of:
#
#   ./run.sh all       # all properties in the constructor (sources-all)
#   ./run.sh id        # only the primary key in the constructor (sources-id)
#   ./run.sh none      # all properties in the class body (sources-none)
#   ./run.sh blank     # like `id`, but body decls separated by blank lines
#
# Each variant materialises into its own work directory under build/, so the
# .schema-sync.toml + any user edits stay scoped to that variant. `gradle clean`
# wipes everything.

set -euo pipefail

if [[ $# -lt 1 ]]; then
    echo "usage: $0 <all|id|none|blank>" >&2
    exit 64
fi

VARIANT="$1"
case "$VARIANT" in
    all|id|none|blank) ;;
    *) echo "usage: $0 <all|id|none|blank>" >&2; exit 64;;
esac
shift

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SCHEMA_SYNC_DIR="$(dirname "$SCRIPT_DIR")"
REPO_ROOT="$(dirname "$SCHEMA_SYNC_DIR")"

JAR="$SCHEMA_SYNC_DIR/build/libs/schema-sync-all.jar"
WORK_DIR="$SCHEMA_SYNC_DIR/build/demo-$VARIANT"
SOURCES_DIR="$SCRIPT_DIR/sources-$VARIANT"

if [[ ! -f "$JAR" ]]; then
    echo "Building schema-sync fatJar..."
    (cd "$REPO_ROOT" && gradle :schema-sync:fatJar -q)
fi

if [[ ! -d "$WORK_DIR" ]]; then
    echo "Materialising demo work dir at $WORK_DIR (variant=$VARIANT)"
    mkdir -p "$WORK_DIR"
    \cp "$SCRIPT_DIR/demo.db" "$WORK_DIR/demo.db"
    \cp -r "$SOURCES_DIR" "$WORK_DIR/sources"
fi

cd "$WORK_DIR"
exec java -jar "$JAR" \
    --url "jdbc:sqlite:./demo.db" \
    --sources "./sources" \
    "$@"
