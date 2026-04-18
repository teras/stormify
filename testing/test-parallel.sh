#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
LOG_DIR="$SCRIPT_DIR/.logs"
SESSION="stormify-tests"

usage() {
    cat <<'EOF'
Usage: ./test-parallel.sh <target> [options]

Database lifecycle:
  up             Start ALL database containers in parallel (tmux grid)
  down           Stop all database containers

Test targets:
  native         Run C native tests for all databases in parallel
  jvm            Run JVM tests for all databases in parallel
  linux          Run Kotlin/Native linuxX64 tests for all databases in parallel
  linux-arm64    Run Kotlin/Native linuxArm64 tests for all databases in parallel
  android        Run Android unit tests (Robolectric, single pane)
  mingw          Cross-compile and test mingwX64 (single pane)
  examples       Build and run all example projects in parallel
  all            Run everything: each phase in parallel
  clean          Remove test logs
  results        Show results from a previous run

Options:
  --no-tmux      Run in parallel without tmux (background jobs, log files only)

Requires: tmux (for grid view), databases started via 'up' or './test.sh up'
EOF
    exit 1
}

T="$SCRIPT_DIR/test.sh"

# ========================================================================
# Generic tmux grid — runs test.sh $target $item for each item
#
# Usage: run_tmux <target> [item1 item2 ...]
#   With items:  one pane per item, each runs: test.sh $target $item
#   Without items: single pane running: test.sh $target
# ========================================================================

run_tmux() {
    local target="$1"
    shift
    local items=("$@")
    local standalone=false

    if [ ${#items[@]} -eq 0 ]; then
        items=("$target")
        standalone=true
    fi

    if ! command -v tmux &>/dev/null; then
        echo "tmux not found. Install it or use --no-tmux"
        exit 1
    fi

    tmux kill-session -t "$SESSION" 2>/dev/null || true

    # Runner script — executed inside each tmux pane
    local runner="$LOG_DIR/_runner.sh"
    cat > "$runner" <<RUNNER
#!/usr/bin/env bash
set -euo pipefail
item="\$1"; target="\$2"; standalone="\$3"
LOG_DIR="$LOG_DIR"
log="\$LOG_DIR/\${target}_\${item}.log"
echo "=== \$item (\$target) ==="; echo ""
rc=0
t0=\$SECONDS
if [ "\$standalone" = "true" ]; then
    "$T" "\$target" 2>&1 | tee "\$log" || rc=\${PIPESTATUS[0]}
else
    "$T" "\$target" "\$item" 2>&1 | tee "\$log" || rc=\${PIPESTATUS[0]}
fi
dur=\$((SECONDS - t0))
echo ""
if [ \$rc -eq 0 ]; then
    echo "PASS:\$dur" > "\$LOG_DIR/\${target}_\${item}.result"
    echo "================================"
    echo "  \$item: PASSED (\${dur}s)"
    echo "================================"
else
    echo "FAIL:\$rc:\$dur" > "\$LOG_DIR/\${target}_\${item}.result"
    echo "================================"
    echo "  \$item: FAILED (exit \$rc, \${dur}s)"
    echo "================================"
fi
RUNNER
    chmod +x "$runner"

    # Monitor script — updates tmux status bar
    local monitor="$LOG_DIR/_monitor.sh"
    cat > "$monitor" <<MONITOR
#!/usr/bin/env bash
SESSION="$SESSION"; LOG_DIR="$LOG_DIR"; TARGET="\$1"; shift; ITEMS=("\$@")
TOTAL=\${#ITEMS[@]}
while true; do
    d=0; p=0; f=0; s=""
    for i in "\${ITEMS[@]}"; do
        rf="\$LOG_DIR/\${TARGET}_\${i}.result"
        if [ -f "\$rf" ]; then
            r=\$(cat "\$rf"); d=\$((d+1))
            dur=\$(echo "\$r" | awk -F: '{print \$NF}')
            if [[ "\$r" == PASS* ]]; then p=\$((p+1)); s="\$s \$i:OK(\${dur}s)"
            else f=\$((f+1)); s="\$s #[bold]\$i:FAIL(\${dur}s)#[nobold]"; fi
        else s="\$s \$i:..."; fi
    done
    tmux set-option -t "\$SESSION" status-left " [\$d/\$TOTAL]\$s " 2>/dev/null || exit 0
    if [ "\$d" -eq "\$TOTAL" ]; then
        if [ "\$f" -eq 0 ]; then tmux set-option -t "\$SESSION" status-right " ALL PASSED | Press q to exit " 2>/dev/null
        else tmux set-option -t "\$SESSION" status-right "#[bold] \$f FAILED#[nobold] | Press q to exit " 2>/dev/null; fi
        exit 0
    fi
    sleep 2
done
MONITOR
    chmod +x "$monitor"

    # Create tmux session
    tmux new-session -d -s "$SESSION" -x 200 -y 50 \
        "$runner ${items[0]} $target $standalone"
    for ((i = 1; i < ${#items[@]}; i++)); do
        tmux split-window -t "$SESSION" "$runner ${items[$i]} $target $standalone"
        tmux select-layout -t "$SESSION" tiled
    done
    tmux select-layout -t "$SESSION" tiled
    tmux set-option -t "$SESSION" remain-on-exit on
    tmux set-option -t "$SESSION" pane-border-status top
    tmux set-option -t "$SESSION" pane-border-format " #{pane_index}: #{pane_title} "
    for i in "${!items[@]}"; do
        tmux select-pane -t "$SESSION:0.$i" -T "${items[$i]}"
    done
    tmux set-option -t "$SESSION" status-style "bg=black,fg=brightwhite,bold"
    tmux set-option -t "$SESSION" status-left " [0/${#items[@]}] Starting... "
    tmux set-option -t "$SESSION" status-right " Running... "
    tmux set-option -t "$SESSION" status-interval 1
    tmux bind-key -n q kill-session
    tmux bind-key -n '\;' kill-session

    "$monitor" "$target" "${items[@]}" &
    local mpid=$!

    echo ""
    echo "Press 'q' to exit."
    echo ""
    tmux attach -t "$SESSION" || true
    kill "$mpid" 2>/dev/null || true
    wait "$mpid" 2>/dev/null || true
}

# ========================================================================
# Generic background runner (--no-tmux)
# ========================================================================

run_bg() {
    local target="$1"
    shift
    local items=("$@")
    local standalone=false
    local pids=()

    if [ ${#items[@]} -eq 0 ]; then
        items=("$target")
        standalone=true
    fi

    echo "Starting $target in parallel (${#items[@]} items)..."
    for item in "${items[@]}"; do
        (
            local log="$LOG_DIR/${target}_${item}.log"
            local rc=0
            local t0=$SECONDS
            if $standalone; then
                "$T" "$target" > "$log" 2>&1 || rc=$?
            else
                "$T" "$target" "$item" > "$log" 2>&1 || rc=$?
            fi
            local dur=$((SECONDS - t0))
            if [ $rc -eq 0 ]; then echo "PASS:$dur" > "$LOG_DIR/${target}_${item}.result"
            else echo "FAIL:$rc:$dur" > "$LOG_DIR/${target}_${item}.result"; fi
        ) &
        pids+=($!)
    done

    for pid in "${pids[@]}"; do wait "$pid" || true; done
    # Intentionally NOT calling `collect` here: every caller that reaches this
    # code path already invokes collect after `run` returns, and letting
    # collect's non-zero return (= number of failed items) propagate would
    # trip `set -e` and abort the multi-phase `all` flow before later phases.
}

# ========================================================================
# Collect and display results
# ========================================================================

collect() {
    local target="$1"
    shift
    local items=("$@")
    local passed=0 failed=0 total_dur=0

    echo ""
    echo "========================================="
    echo "  RESULTS: $target"
    echo "========================================="
    echo ""
    for item in "${items[@]}"; do
        local rf="$LOG_DIR/${target}_${item}.result"
        local content="" dur=0
        if [ -f "$rf" ]; then
            content="$(cat "$rf")"
            dur="${content##*:}"
            [[ "$dur" =~ ^[0-9]+$ ]] || dur=0
            total_dur=$((total_dur + dur))
        fi
        local tfmt
        if [ "$dur" -ge 60 ]; then tfmt="$((dur/60))m$((dur%60))s"; else tfmt="${dur}s"; fi
        if [[ "$content" == PASS* ]]; then
            passed=$((passed + 1))
            printf "  %-14s  PASSED  %8s\n" "$item" "$tfmt"
        else
            failed=$((failed + 1))
            printf "  %-14s  FAILED  %8s\n" "$item" "$tfmt"
        fi
    done
    echo ""
    local tfmt
    if [ "$total_dur" -ge 60 ]; then tfmt="$((total_dur/60))m$((total_dur%60))s"; else tfmt="${total_dur}s"; fi
    echo "  Total: $((passed + failed))  Passed: $passed  Failed: $failed  Wall (sum): $tfmt"
    echo "  Logs: $LOG_DIR/"
    echo ""
    return $failed
}

# ========================================================================
# Main
# ========================================================================

TARGET="${1:-}"
MODE="tmux"
shift || true
while [ $# -gt 0 ]; do
    case "$1" in
        --no-tmux) MODE="bg" ;;
        *) echo "Unknown option: $1"; usage ;;
    esac
    shift
done

rm -rf "$LOG_DIR"
mkdir -p "$LOG_DIR"

run() {
    if [ "$MODE" = "tmux" ]; then run_tmux "$@"; else run_bg "$@"; fi
}

case "$TARGET" in
    up)
        # Pre-create Docker network to avoid race on parallel startup
        docker compose -f "$SCRIPT_DIR/docker-compose.yml" up --no-start 2>/dev/null || true
        run up $("$T" list docker-dbs)
        echo ""
        "$T" status
        ;;

    down)
        "$T" down
        ;;

    native|jvm|linux|linux-arm64|mingw)
        "$T" check
        "$T" build "$TARGET"
        run "$TARGET" $("$T" list dbs)
        echo ""
        collect "$TARGET" $("$T" list dbs)
        ;;

    android)
        run "$TARGET"
        echo ""
        collect "$TARGET" "$TARGET"
        ;;

    examples)
        run examples $("$T" list examples)
        echo ""
        collect examples $("$T" list examples)
        ;;

    all)
        "$T" check
        all_failed=0

        echo "=== Phase 1: Native C tests ==="
        run native $("$T" list dbs)
        collect native $("$T" list dbs) || all_failed=$((all_failed + $?))

        echo "=== Phase 2: JVM tests (sequential — KSP cache is not safe for concurrent gradle invocations) ==="
        "$T" build jvm
        for db in $("$T" list dbs); do
            log="$LOG_DIR/jvm_${db}.log"
            t0=$SECONDS
            if "$T" jvm "$db" > "$log" 2>&1; then
                echo "PASS:$((SECONDS - t0))" > "$LOG_DIR/jvm_${db}.result"
            else
                echo "FAIL:$?:$((SECONDS - t0))" > "$LOG_DIR/jvm_${db}.result"
            fi
        done
        collect jvm $("$T" list dbs) || all_failed=$((all_failed + $?))

        echo "=== Phase 3: Kotlin/Native tests ==="
        "$T" build linux
        run linux $("$T" list dbs)
        collect linux $("$T" list dbs) || all_failed=$((all_failed + $?))

        echo "=== Phase 4: Android ==="
        run android
        collect android android || all_failed=$((all_failed + $?))

        echo "=== Phase 5: mingw ==="
        run mingw $("$T" list dbs)
        collect mingw $("$T" list dbs) || all_failed=$((all_failed + $?))

        echo "=== Phase 6: Examples ==="
        run examples $("$T" list examples)
        collect examples $("$T" list examples) || all_failed=$((all_failed + $?))

        echo ""
        echo "========================================="
        echo "  GRAND TOTAL: $all_failed phase(s) with failures"
        echo "========================================="
        ;;

    results)
        RTARGET="${2:-native}"
        collect "$RTARGET" $("$T" list dbs)
        ;;

    clean)
        rm -rf "$LOG_DIR"
        echo "Cleaned $LOG_DIR"
        ;;

    *)
        usage
        ;;
esac
