#!/usr/bin/env bash
# Stormify benchmark runner.
# Orchestrates: DDL apply -> bench-insert -> bench-read across DB x impl matrix.
#
# Usage:
#   ./runner/run.sh                       # all DBs, all impls (default sizes)
#   ./runner/run.sh sqlite                # one DB
#   ./runner/run.sh sqlite postgresql     # multiple DBs
#   IMPLS="stormify-native jpa-jvm" ./runner/run.sh sqlite
#   BENCH_PARENTS=1000 BENCH_CHILDREN=10000 ./runner/run.sh
#
# Env knobs:
#   BENCH_WARM     (default 5)
#   BENCH_ITERS    (default 500)
#   BENCH_PARENTS  (default 1000)
#   BENCH_CHILDREN (default 10000)
#   IMPLS          (default "stormify-native jpa-jvm jpa-native"; missing impls skipped)
#   DBS            (override list of DBs)

set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BENCH_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
PROJECT_DIR="$(cd "$BENCH_DIR/.." && pwd)"

STORMIFY_BIN="$BENCH_DIR/stormify-native/build/bin/linuxX64/releaseExecutable/stormify-native-bench.kexe"
JPA_JAR="$BENCH_DIR/jpa-jvm/build/libs/jpa-jvm-bench.jar"
JPA_NATIVE_BIN="$BENCH_DIR/jpa-jvm/build/native/jpa-native-bench"

RESULTS_DIR="$BENCH_DIR/results"
mkdir -p "$RESULTS_DIR"

CSV="$RESULTS_DIR/results.csv"
LOG="$RESULTS_DIR/run.log"
: > "$CSV"
: > "$LOG"

if [ $# -gt 0 ]; then
    DBS_ARGS="$*"
else
    DBS_ARGS="${DBS:-sqlite mysql postgresql oracle mssql}"
fi

IMPLS_DEFAULT="stormify-native jpa-jvm jpa-native"
IMPLS_LIST="${IMPLS:-$IMPLS_DEFAULT}"

log()   { echo "[$(date +%H:%M:%S)] $*" | tee -a "$LOG"; }
fatal() { log "FATAL: $*"; exit 1; }

run_impl() {
    local impl="$1" db="$2" mode="$3" csv_path="$4" extra_env=""
    case "$impl" in
        stormify-native)
            [ -x "$STORMIFY_BIN" ] || { log "skip stormify-native ($mode/$db) — binary missing"; return 1; }
            BENCH_DB="$db" BENCH_CSV="$csv_path" BENCH_APPEND=1 \
                BENCH_DDL="$BENCH_DIR/schema/ddl-${db}.sql" \
                "$STORMIFY_BIN" "$mode" >>"$LOG" 2>&1
            ;;
        jpa-jvm)
            [ -f "$JPA_JAR" ] || { log "skip jpa-jvm ($mode/$db) — jar missing"; return 1; }
            BENCH_DB="$db" BENCH_CSV="$csv_path" BENCH_APPEND=1 \
                BENCH_DDL="$BENCH_DIR/schema/ddl-${db}.sql" \
                java -jar "$JPA_JAR" "$mode" >>"$LOG" 2>&1
            ;;
        jpa-native)
            [ -x "$JPA_NATIVE_BIN" ] || { log "skip jpa-native ($mode/$db) — binary missing (run native-image-build.sh)"; return 1; }
            BENCH_DB="$db" BENCH_CSV="$csv_path" BENCH_APPEND=1 \
                BENCH_DDL="$BENCH_DIR/schema/ddl-${db}.sql" \
                "$JPA_NATIVE_BIN" "$mode" >>"$LOG" 2>&1
            ;;
        *) log "unknown impl: $impl"; return 1 ;;
    esac
}

# Write CSV header once
echo "db,impl,scenario,kind,iter,duration_ns,rows,rss_kb" > "$CSV"

for db in $DBS_ARGS; do
    log "=== DB: $db ==="
    for impl in $IMPLS_LIST; do
        log "--- impl: $impl ---"

        # Clean DB for this impl. We use stormify-native (cheapest startup) for the DDL apply
        # if available, falling back to whichever impl is being measured.
        if [ -x "$STORMIFY_BIN" ]; then
            log "  prepare via stormify-native"
            BENCH_DB="$db" BENCH_CSV="/dev/null" BENCH_APPEND=1 \
                BENCH_DDL="$BENCH_DIR/schema/ddl-${db}.sql" \
                "$STORMIFY_BIN" prepare >>"$LOG" 2>&1 \
                || { log "  prepare FAILED (db likely down) — skipping $db"; break; }
        else
            run_impl "$impl" "$db" prepare /dev/null || { log "  prepare FAILED — skipping"; break; }
        fi

        log "  bench-insert ($impl)"
        run_impl "$impl" "$db" bench-insert "$CSV" || { log "  insert FAILED"; continue; }

        log "  bench-read ($impl)"
        run_impl "$impl" "$db" bench-read "$CSV" || { log "  read FAILED"; continue; }
    done
done

log "=== Done. CSV: $CSV ==="
log "Generating HTML report..."
python3 "$BENCH_DIR/report/generate-report.py" "$CSV" "$RESULTS_DIR/report.html" 2>>"$LOG" \
    && log "HTML: $RESULTS_DIR/report.html" \
    || log "HTML generation failed — see log."

# Repopulate the docs/src/Benchmarks.md page so the published documentation
# reflects the latest run. The script edits between <!-- MARKER --> tags in
# place; it's safe to re-run any number of times.
DOCS_BENCH="$BENCH_DIR/../docs/src/Benchmarks.md"
if [ -f "$DOCS_BENCH" ]; then
    log "Updating docs/src/Benchmarks.md..."
    python3 "$BENCH_DIR/report/generate-benchmarks-md.py" "$CSV" "$DOCS_BENCH" 2>>"$LOG" \
        && log "Docs: $DOCS_BENCH" \
        || log "Docs update failed — see log."
fi
