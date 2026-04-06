#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
C_SRC_DIR="$PROJECT_DIR/kdbc/src/c"
C_TEST_BIN="$C_SRC_DIR/test/test_kdbc"

ALL_DBS="sqlite postgresql postgresql9 mysql mysql5 mariadb oracle oracle11 mssql"

# Database connection details (matching docker-compose.yml)
db_driver()   { case "$1" in mysql|mysql5) echo "mariadb";; mssql) echo "mssql";; oracle11) echo "oracle";; postgresql9) echo "postgresql";; *) echo "$1";; esac; }
db_url()      { case "$1" in
    sqlite)      echo ":memory:" ;;
    postgresql)  echo "localhost:15432/stormify_test" ;;
    postgresql9) echo "localhost:15431/stormify_test" ;;
    mysql)       echo "localhost:13306/stormify_test" ;;
    mysql5)      echo "localhost:13305/stormify_test" ;;
    mariadb)     echo "localhost:13307/stormify_test" ;;
    oracle)      echo "localhost:11521/XEPDB1" ;;
    # Oracle XE 11g uses the classic XE service name (no PDB), and the
    # docker-compose definition maps the instance to port 11524 and the
    # EL8ISO8859P7 (Greek ISO-8859-7) database character set.
    oracle11)    echo "localhost:11524/XE" ;;
    mssql)       echo "localhost:11433/stormify_test" ;;
esac; }
db_user()     { case "$1" in mssql) echo "sa";; *) echo "stormify";; esac; }
db_password() { echo "Stormify1!"; }

usage() {
    cat <<'EOF'
Usage: ./test.sh <target> [database]

Targets:
  jvm [database]       Run JVM tests (Gradle)
  linux [database]     Run Kotlin/Native linuxX64 tests (Gradle)
  native [database]    Run C native tests
  all                  Run everything (JVM + linux + native, all databases)

Databases:
  sqlite               SQLite (no Docker needed)
  postgresql           PostgreSQL 16
  postgresql9          PostgreSQL 9.6
  mysql                MySQL 8.0
  mysql5               MySQL 5.7
  mariadb              MariaDB 11
  oracle               Oracle 21c XE (AL32UTF8)
  oracle11             Oracle 11g XE (EL8ISO8859P7 / Greek ISO-8859-7)
  mssql                MS SQL Server 2022

If no database specified, runs ALL databases sequentially.

Examples:
  ./test.sh native sqlite        # Quick: C tests with SQLite only
  ./test.sh native               # C tests against all databases
  ./test.sh jvm postgresql       # JVM tests against PostgreSQL
  ./test.sh linux oracle         # Kotlin/Native tests against Oracle
  ./test.sh all                  # Everything
EOF
    exit 1
}

# ========================================================================
# Docker helpers
# ========================================================================

# STORMIFY_KEEP_CONTAINERS=1 — start containers but don't stop them
# (used by test-parallel.sh; cleanup happens once at the end)
KEEP_CONTAINERS="${STORMIFY_KEEP_CONTAINERS:-0}"

start_db() {
    local db="$1"
    [ "$db" = "sqlite" ] && return 0
    echo "Starting $db container..."
    docker compose -f "$SCRIPT_DIR/docker-compose.yml" --profile "$db" up -d
    "$SCRIPT_DIR/scripts/wait-for-db.sh" "$db"
}

stop_db() {
    local db="$1"
    [ "$db" = "sqlite" ] && return 0
    [ "$KEEP_CONTAINERS" = "1" ] && return 0
    echo "Stopping $db container..."
    docker compose -f "$SCRIPT_DIR/docker-compose.yml" --profile "$db" down -v 2>/dev/null || true
}

# ========================================================================
# Build C test binary
# ========================================================================

build_native() {
    echo "Building C test binary..."
    make -C "$C_SRC_DIR" lib 2>&1 | tail -1
    make -C "$C_SRC_DIR" test/test_kdbc 2>&1 | tail -1
    echo ""
}

# ========================================================================
# Run native (C) tests for one database
# ========================================================================

run_native_one() {
    local db="$1"
    local driver=$(db_driver "$db")
    local url=$(db_url "$db")
    local user=$(db_user "$db")
    local pass=$(db_password)

    echo "========================================="
    echo "Native C tests: $db"
    echo "========================================="

    start_db "$db"

    local rc=0
    if [ "$db" = "sqlite" ]; then
        "$C_TEST_BIN" sqlite ":memory:" || rc=$?
    else
        "$C_TEST_BIN" "$driver" "$url" "$user" "$pass" || rc=$?
    fi

    stop_db "$db"

    if [ $rc -eq 0 ]; then
        echo "PASSED: native $db"
    else
        echo "FAILED: native $db (exit code: $rc)"
    fi
    echo ""
    return $rc
}

# ========================================================================
# Run JVM tests for one database
# ========================================================================

run_jvm_one() {
    local db="$1"
    local config_path="$SCRIPT_DIR/config/$db/hikari.properties"

    echo "========================================="
    echo "JVM tests: $db"
    echo "========================================="

    start_db "$db"

    local rc=0
    cd "$PROJECT_DIR"

    if [ -f "$config_path" ]; then
        gradle :stormify:jvmTest \
            -Dstormify.test.config="$config_path" \
            -Dstormify.test.db="$db" \
            --console=plain 2>&1 || rc=$?
    else
        gradle :stormify:jvmTest --console=plain 2>&1 || rc=$?
    fi

    stop_db "$db"

    if [ $rc -eq 0 ]; then
        echo "PASSED: jvm $db"
    else
        echo "FAILED: jvm $db (exit code: $rc)"
    fi
    echo ""
    return $rc
}

# ========================================================================
# Run Kotlin/Native linuxX64 tests for one database
# ========================================================================

run_linux_one() {
    local db="$1"

    echo "========================================="
    echo "Kotlin/Native linuxX64 tests: $db"
    echo "========================================="

    start_db "$db"

    local rc=0
    cd "$PROJECT_DIR"

    STORMIFY_TEST_DB="$db" gradle :stormify:linuxX64Test \
        --console=plain 2>&1 || rc=$?

    stop_db "$db"

    if [ $rc -eq 0 ]; then
        echo "PASSED: linux $db"
    else
        echo "FAILED: linux $db (exit code: $rc)"
    fi
    echo ""
    return $rc
}

# ========================================================================
# Run tests for multiple databases
# ========================================================================

run_for_dbs() {
    local runner="$1"
    shift
    local dbs="$@"
    local failed=0
    local results=""

    for db in $dbs; do
        if $runner "$db"; then
            results="$results  PASS: $db\n"
        else
            results="$results  FAIL: $db\n"
            failed=$((failed + 1))
        fi
    done

    echo "========================================="
    echo "Summary:"
    echo -e "$results"
    return $failed
}

# ========================================================================
# Main
# ========================================================================

TARGET="${1:-}"
DB="${2:-}"

case "$TARGET" in
    native)
        build_native
        if [ -n "$DB" ]; then
            run_native_one "$DB"
        else
            run_for_dbs run_native_one $ALL_DBS
        fi
        ;;

    jvm)
        if [ -n "$DB" ]; then
            run_jvm_one "$DB"
        else
            run_for_dbs run_jvm_one $ALL_DBS
        fi
        ;;

    linux)
        if [ -n "$DB" ]; then
            run_linux_one "$DB"
        else
            run_for_dbs run_linux_one $ALL_DBS
        fi
        ;;

    all)
        build_native
        failed=0
        echo ""
        echo "############### NATIVE TESTS ###############"
        echo ""
        run_for_dbs run_native_one $ALL_DBS || failed=$((failed + $?))
        echo ""
        echo "############### JVM TESTS ###############"
        echo ""
        run_for_dbs run_jvm_one $ALL_DBS || failed=$((failed + $?))
        echo ""
        echo "############### KOTLIN/NATIVE LINUX TESTS ###############"
        echo ""
        run_for_dbs run_linux_one $ALL_DBS || failed=$((failed + $?))
        exit $failed
        ;;

    *)
        usage
        ;;
esac
