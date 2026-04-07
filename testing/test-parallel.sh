#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
LOG_DIR="$SCRIPT_DIR/.logs"
RESULTS_FILE="$LOG_DIR/_results"

ALL_DBS=(sqlite postgresql postgresql9 mysql mysql5 mariadb oracle oracle11 mssql)
SESSION="stormify-tests"

usage() {
    cat <<'EOF'
Usage: ./test-parallel.sh <target> [options]

Targets:
  native       Run C native tests for all databases in parallel
  jvm          Run JVM tests for all databases in parallel
  linux        Run Kotlin/Native linuxX64 tests for all databases in parallel
  all          Run native, JVM, and linux tests, each phase in parallel
  clean        Remove test logs
  results      Show results from a previous run

Options:
  --no-tmux    Run in parallel without tmux (background jobs, log files only)

Requires: tmux (for grid view)

The script creates a tiled tmux grid where each pane shows one database's
test output in real-time. After all tests finish, a summary is displayed.
EOF
    exit 1
}

# ========================================================================
# Cleanup
# ========================================================================

cleanup() {
    echo "Stopping database containers..."
    docker compose -f "$SCRIPT_DIR/docker-compose.yml" --profile all down -v 2>/dev/null || true
}

# Pre-create the Docker network so parallel `docker compose up -d` calls
# don't race on network creation.
ensure_network() {
    docker compose -f "$SCRIPT_DIR/docker-compose.yml" up --no-start 2>/dev/null || true
}

# ========================================================================
# Single database test runner (called inside each tmux pane or bg job)
# ========================================================================

run_one() {
    local target="$1"
    local db="$2"
    local log_file="$LOG_DIR/${target}_${db}.log"
    local rc=0

    echo "[$db] Starting $target tests..." | tee "$log_file"

    STORMIFY_KEEP_CONTAINERS=1 "$SCRIPT_DIR/test.sh" "$target" "$db" >> "$log_file" 2>&1 || rc=$?

    if [ $rc -eq 0 ]; then
        echo "PASS" > "$LOG_DIR/${target}_${db}.result"
        echo "" >> "$log_file"
        echo "================================" >> "$log_file"
        echo "  RESULT: PASSED" >> "$log_file"
        echo "================================" >> "$log_file"
    else
        echo "FAIL:$rc" > "$LOG_DIR/${target}_${db}.result"
        echo "" >> "$log_file"
        echo "================================" >> "$log_file"
        echo "  RESULT: FAILED (exit $rc)" >> "$log_file"
        echo "================================" >> "$log_file"
    fi

    return $rc
}

# ========================================================================
# Collect results from all databases
# ========================================================================

collect_results() {
    local target="$1"
    local failed=0
    local total=0
    local passed=0

    echo ""
    echo "========================================="
    echo "  RESULTS: $target tests"
    echo "========================================="
    echo ""

    for db in "${ALL_DBS[@]}"; do
        total=$((total + 1))
        local result_file="$LOG_DIR/${target}_${db}.result"
        if [ -f "$result_file" ]; then
            local result=$(cat "$result_file")
            if [ "$result" = "PASS" ]; then
                passed=$((passed + 1))
                printf "  %-14s  PASSED\n" "$db"
            else
                failed=$((failed + 1))
                local code="${result#FAIL:}"
                printf "  %-14s  FAILED (exit %s)\n" "$db" "$code"
            fi
        else
            failed=$((failed + 1))
            printf "  %-14s  NO RESULT (may still be running?)\n" "$db"
        fi
    done

    echo ""
    echo "-----------------------------------------"
    echo "  Total: $total  Passed: $passed  Failed: $failed"
    echo "-----------------------------------------"
    echo ""
    echo "Logs: $LOG_DIR/"
    echo ""

    return $failed
}

# ========================================================================
# tmux grid runner
# ========================================================================

run_tmux_grid() {
    local target="$1"

    if ! command -v tmux &>/dev/null; then
        echo "tmux not found. Install it or use --no-tmux"
        exit 1
    fi

    # Kill existing session if any
    tmux kill-session -t "$SESSION" 2>/dev/null || true

    # Runner script executed inside each tmux pane - shows live output
    local runner_script="$LOG_DIR/_runner.sh"
    cat > "$runner_script" <<'RUNNER'
#!/usr/bin/env bash
set -euo pipefail

db="$1"
target="$2"
SCRIPT_DIR="$3"
LOG_DIR="$4"
log_file="$LOG_DIR/${target}_${db}.log"
export STORMIFY_KEEP_CONTAINERS=1

# Show live output directly in the pane (tee to log file)
echo "=== $db ($target) ==="
echo ""

rc=0
"$SCRIPT_DIR/test.sh" "$target" "$db" 2>&1 | tee "$log_file" || rc=${PIPESTATUS[0]}

echo ""
if [ $rc -eq 0 ]; then
    echo "PASS" > "$LOG_DIR/${target}_${db}.result"
    echo "================================"
    echo "  $db: PASSED"
    echo "================================"
else
    echo "FAIL:$rc" > "$LOG_DIR/${target}_${db}.result"
    echo "================================"
    echo "  $db: FAILED (exit $rc)"
    echo "================================"
fi
RUNNER
    chmod +x "$runner_script"

    # Monitor script - watches for all results and updates status bar
    local monitor_script="$LOG_DIR/_monitor.sh"
    cat > "$monitor_script" <<'MONITOR'
#!/usr/bin/env bash
SESSION="$1"
LOG_DIR="$2"
TOTAL="$3"
TARGET="$4"
shift 4
ALL_DBS=("$@")

while true; do
    done_count=0
    pass_count=0
    fail_count=0
    status=""

    for db in "${ALL_DBS[@]}"; do
        result_file="$LOG_DIR/${TARGET}_${db}.result"
        if [ -f "$result_file" ]; then
            r=$(cat "$result_file")
            if [ "$r" = "PASS" ]; then
                pass_count=$((pass_count + 1))
                status="$status $db:OK"
            else
                fail_count=$((fail_count + 1))
                status="$status #[bold]$db:FAIL#[nobold]"
            fi
            done_count=$((done_count + 1))
        else
            status="$status $db:..."
        fi
    done

    tmux set-option -t "$SESSION" status-left \
        " [$done_count/$TOTAL] $status " 2>/dev/null || exit 0

    if [ "$done_count" -eq "$TOTAL" ]; then
        if [ "$fail_count" -eq 0 ]; then
            tmux set-option -t "$SESSION" status-right \
                " ALL PASSED | Press q to exit " 2>/dev/null || exit 0
        else
            tmux set-option -t "$SESSION" status-right \
                "#[bold] $fail_count FAILED#[nobold] | Press q to exit " 2>/dev/null || exit 0
        fi
        exit 0
    fi

    sleep 2
done
MONITOR
    chmod +x "$monitor_script"

    # Create tmux session with first database
    tmux new-session -d -s "$SESSION" -x 200 -y 50 \
        "$runner_script ${ALL_DBS[0]} $target $SCRIPT_DIR $LOG_DIR"

    # Create remaining panes — one per database, letting tmux's "tiled"
    # layout arrange them into a balanced grid (3x2 for 6 DBs, 4x2 for 7,
    # 3x3 for 8-9, etc. — driven entirely by the number of entries in
    # ALL_DBS).
    for ((i = 1; i < ${#ALL_DBS[@]}; i++)); do
        tmux split-window -t "$SESSION" \
            "$runner_script ${ALL_DBS[$i]} $target $SCRIPT_DIR $LOG_DIR"
        # Rebalance after each split
        tmux select-layout -t "$SESSION" tiled
    done

    # Final tiled layout pass
    tmux select-layout -t "$SESSION" tiled

    # Panes stay visible after command exits (so you can scroll back)
    tmux set-option -t "$SESSION" remain-on-exit on

    # Enable pane borders with titles
    tmux set-option -t "$SESSION" pane-border-status top
    tmux set-option -t "$SESSION" pane-border-format " #{pane_index}: #{pane_title} "

    # Set pane titles
    for i in "${!ALL_DBS[@]}"; do
        tmux select-pane -t "$SESSION:0.$i" -T "${ALL_DBS[$i]}"
    done

    # Status bar style - override default green
    tmux set-option -t "$SESSION" status-style "bg=black,fg=brightwhite,bold"

    # Status bar initial state
    tmux set-option -t "$SESSION" status-left " [0/${#ALL_DBS[@]}] Starting... "
    tmux set-option -t "$SESSION" status-right " Running... "
    tmux set-option -t "$SESSION" status-interval 1

    # Bind 'q' and ';' to kill the session (no Ctrl-B needed)
    tmux bind-key -n q kill-session
    tmux bind-key -n '\;' kill-session

    # Start background monitor to update status bar
    "$monitor_script" "$SESSION" "$LOG_DIR" "${#ALL_DBS[@]}" "$target" "${ALL_DBS[@]}" &
    local monitor_pid=$!

    echo ""
    echo "Attached to tmux grid. Watch tests run live."
    echo "Press 'q' when done to exit and see summary."
    echo ""

    # Attach to session (blocks until session is killed)
    tmux attach -t "$SESSION" || true

    # Cleanup monitor
    kill "$monitor_pid" 2>/dev/null || true
    wait "$monitor_pid" 2>/dev/null || true
}

# ========================================================================
# Pre-compile Gradle targets so parallel runs use the binary directly
# ========================================================================

build_linux() {
    echo "Compiling Kotlin/Native test binary..."
    cd "$PROJECT_DIR"
    gradle :stormify:linkDebugTestLinuxX64 --console=plain 2>&1
    export STORMIFY_PREBUILT=1
    echo ""
}

build_jvm() {
    echo "Compiling JVM test classes..."
    cd "$PROJECT_DIR"
    gradle :stormify:jvmTestClasses --console=plain 2>&1
    echo ""
}

# ========================================================================
# No-tmux parallel runner (background jobs)
# ========================================================================

run_parallel_bg() {
    local target="$1"
    local pids=()
    local dbs_for_pid=()

    echo "Starting $target tests in parallel for all databases..."
    echo "Logs in: $LOG_DIR/"
    echo ""

    for db in "${ALL_DBS[@]}"; do
        run_one "$target" "$db" &
        pids+=($!)
        dbs_for_pid+=("$db")
    done

    echo "Waiting for all tests to complete..."
    echo "  PIDs: ${pids[*]}"
    echo ""

    # Show live tail of logs
    local any_running=true
    while $any_running; do
        any_running=false
        for i in "${!pids[@]}"; do
            if kill -0 "${pids[$i]}" 2>/dev/null; then
                any_running=true
                break
            fi
        done
        if $any_running; then
            # Print status every 5 seconds
            local status=""
            for i in "${!ALL_DBS[@]}"; do
                local db="${ALL_DBS[$i]}"
                local result_file="$LOG_DIR/${target}_${db}.result"
                if [ -f "$result_file" ]; then
                    local r=$(cat "$result_file")
                    if [ "$r" = "PASS" ]; then
                        status="$status  $db:PASS"
                    else
                        status="$status  $db:FAIL"
                    fi
                else
                    status="$status  $db:..."
                fi
            done
            printf "\r%s" "$status"
            sleep 3
        fi
    done
    echo ""

    # Wait for all processes
    local overall_rc=0
    for pid in "${pids[@]}"; do
        wait "$pid" || overall_rc=$((overall_rc + 1))
    done

    collect_results "$target"
    return $?
}

# ========================================================================
# Main
# ========================================================================

TARGET="${1:-}"
MODE="tmux"

# Parse options
shift || true
while [ $# -gt 0 ]; do
    case "$1" in
        --no-tmux) MODE="bg" ;;
        *) echo "Unknown option: $1"; usage ;;
    esac
    shift
done

# Prepare log directory
rm -rf "$LOG_DIR"
mkdir -p "$LOG_DIR"

trap cleanup EXIT
ensure_network

case "$TARGET" in
    native|jvm|linux)
        # Pre-compile Gradle targets so parallel runs use the binary directly
        [ "$TARGET" = "linux" ] && build_linux
        [ "$TARGET" = "jvm" ] && build_jvm
        if [ "$MODE" = "tmux" ]; then
            run_tmux_grid "$TARGET"
            echo ""
            collect_results "$TARGET"
        else
            run_parallel_bg "$TARGET"
        fi
        ;;

    all)
        all_failed=0
        if [ "$MODE" = "tmux" ]; then
            echo "=== Phase 1: Native C tests ==="
            run_tmux_grid "native"
            echo ""
            collect_results "native" || all_failed=$((all_failed + $?))
            echo ""
            echo "=== Phase 2: JVM tests ==="
            build_jvm
            run_tmux_grid "jvm"
            echo ""
            collect_results "jvm" || all_failed=$((all_failed + $?))
            echo ""
            echo "=== Phase 3: Kotlin/Native tests ==="
            build_linux
            run_tmux_grid "linux"
            echo ""
            collect_results "linux" || all_failed=$((all_failed + $?))
        else
            run_parallel_bg "native"
            build_jvm
            run_parallel_bg "jvm"
            build_linux
            run_parallel_bg "linux"
        fi

        # Grand summary
        echo ""
        echo "========================================="
        echo "  GRAND TOTAL"
        echo "========================================="
        echo ""
        grand_pass=0
        grand_total=$((${#ALL_DBS[@]} * 3))
        for phase in native jvm linux; do
            p=0; f=0
            for db in "${ALL_DBS[@]}"; do
                rf="$LOG_DIR/${phase}_${db}.result"
                if [ -f "$rf" ] && [ "$(cat "$rf")" = "PASS" ]; then
                    p=$((p + 1))
                else
                    f=$((f + 1))
                fi
            done
            grand_pass=$((grand_pass + p))
            if [ $f -eq 0 ]; then
                printf "  %-8s  %d/%d PASSED\n" "$phase" "$p" "$((p + f))"
            else
                printf "  %-8s  %d/%d PASSED, %d FAILED\n" "$phase" "$p" "$((p + f))" "$f"
            fi
        done
        echo ""
        echo "-----------------------------------------"
        grand_fail=$((grand_total - grand_pass))
        if [ $grand_fail -eq 0 ]; then
            echo "  ALL $grand_total PASSED"
        else
            echo "  $grand_pass/$grand_total passed, $grand_fail failed"
        fi
        echo "-----------------------------------------"
        ;;

    results)
        # Just show results for a previous run
        RTARGET="${2:-native}"
        collect_results "$RTARGET"
        ;;

    clean)
        rm -rf "$LOG_DIR"
        echo "Cleaned $LOG_DIR"
        ;;

    *)
        usage
        ;;
esac
