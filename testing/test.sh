#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
C_SRC_DIR="$PROJECT_DIR/kdbc/src/c"
C_TEST_BIN="$C_SRC_DIR/test/test_kdbc"

COMPOSE_FILE="$SCRIPT_DIR/docker-compose.yml"

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

# ========================================================================
# Docker lifecycle (used by up/down commands)
# ========================================================================

start_db() {
    local db="$1"
    [ "$db" = "sqlite" ] && return 0
    echo "Starting $db container..."
    docker compose -f "$COMPOSE_FILE" --profile "$db" up -d
    "$SCRIPT_DIR/scripts/wait-for-db.sh" "$db"
}

stop_db() {
    local db="$1"
    [ "$db" = "sqlite" ] && return 0
    echo "Stopping $db container..."
    docker compose -f "$COMPOSE_FILE" --profile "$db" down -v 2>/dev/null || true
}

stop_all_dbs() {
    echo "Stopping all database containers..."
    docker compose -f "$COMPOSE_FILE" --profile all down -v 2>/dev/null || true
}

# ========================================================================
# Health checks (used by test runners)
# ========================================================================

check_db_ready() {
    local db="$1"
    [ "$db" = "sqlite" ] && return 0
    local status
    status=$(docker compose -f "$COMPOSE_FILE" ps --format json "$db" 2>/dev/null \
        | grep -o '"Health":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
    [ "$status" = "healthy" ]
}

require_db() {
    local db="$1"
    [ "$db" = "sqlite" ] && return 0
    if ! check_db_ready "$db"; then
        echo ""
        echo "ERROR: Database '$db' is not running or not healthy."
        echo "Start it with:  ./test.sh up $db"
        echo "Or start all:   ./test.sh up"
        echo ""
        echo "Hint: run './test.sh status' to see which databases are available."
        return 1
    fi
}

# ========================================================================
# Status display
# ========================================================================

show_status() {
    echo "Database container status:"
    echo ""
    printf "  %-14s  %-10s  %-10s\n" "DATABASE" "STATE" "HEALTH"
    printf "  %-14s  %-10s  %-10s\n" "--------" "-----" "------"
    for db in $ALL_DBS; do
        if [ "$db" = "sqlite" ]; then
            printf "  %-14s  %-10s  %-10s\n" "sqlite" "n/a" "always ready"
            continue
        fi
        local info
        info=$(docker compose -f "$COMPOSE_FILE" ps --format json "$db" 2>/dev/null || true)
        if [ -z "$info" ]; then
            printf "  %-14s  %-10s  %-10s\n" "$db" "stopped" "-"
        else
            local state health
            state=$(echo "$info" | grep -o '"State":"[^"]*"' | head -1 | cut -d'"' -f4 || echo "unknown")
            health=$(echo "$info" | grep -o '"Health":"[^"]*"' | head -1 | cut -d'"' -f4 || echo "unknown")
            printf "  %-14s  %-10s  %-10s\n" "$db" "$state" "$health"
        fi
    done
    echo ""
}

usage() {
    cat <<'EOF'
Usage: ./test.sh <target> [database]

Targets:
  jvm [database]         Run JVM tests (Gradle)
  linux [database]       Run Kotlin/Native linuxX64 tests
  linux-arm64 [database] Run Kotlin/Native linuxArm64 tests (via Docker on x64)
  native [database]      Run C native tests
  android                Run Android unit tests (Robolectric, no database needed)
  mingw [database]       Cross-compile and test mingwX64 (Wine required for execution)
  examples               Build and run all example projects
  all                    Run everything (JVM + linux + native + examples, all databases)

Database lifecycle:
  up [database]          Start database container(s) and wait for healthy
  down [database]        Stop database container(s)
  status                 Show which database containers are running
  check                  Exit 0 if all databases are ready, 1 otherwise
  build <target>         Pre-compile test binary for target (native/linux/mingw/jvm)
  list dbs|docker-dbs|examples   Print item list (for scripting)

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

If no database specified, runs ALL supported databases for the target.

Examples:
  ./test.sh up postgresql          # Start PostgreSQL container
  ./test.sh up                     # Start ALL database containers (sequentially)
  ./test.sh status                 # Check which databases are running
  ./test.sh native sqlite          # Quick: C tests with SQLite only
  ./test.sh native                 # C tests against all databases
  ./test.sh jvm postgresql         # JVM tests against PostgreSQL
  ./test.sh linux oracle           # linuxX64 tests against Oracle
  ./test.sh linux-arm64 sqlite     # linuxArm64 tests via Docker
  ./test.sh android                # Android Robolectric tests
  ./test.sh mingw                  # mingwX64 cross-compile + test
  ./test.sh all                    # Everything
  ./test.sh down                   # Stop ALL database containers
EOF
    exit 1
}

# ========================================================================
# Build helpers — each builds once, skips if binary is up-to-date
# ========================================================================

build_native() {
    echo "Building C test binary..."
    make -C "$C_SRC_DIR" lib 2>&1
    make -C "$C_SRC_DIR" test/test_kdbc 2>&1
    echo ""
}

LINUX_TEST_BIN="$PROJECT_DIR/stormify/build/bin/linuxX64/debugTest/test.kexe"
ARM64_TEST_BIN="$PROJECT_DIR/stormify/build/bin/linuxArm64/debugTest/test.kexe"
MINGW_TEST_BIN="$PROJECT_DIR/stormify/build/bin/mingwX64/debugTest/test.exe"

build_linux() {
    [ -x "$LINUX_TEST_BIN" ] && return 0
    echo "Building linuxX64 test binary..."
    cd "$PROJECT_DIR"
    gradle :conformance:linkDebugTestLinuxX64 --console=plain 2>&1
    echo ""
}

build_arm64() {
    [ -x "$ARM64_TEST_BIN" ] && return 0
    echo "Building linuxArm64 test binary..."
    cd "$PROJECT_DIR"
    gradle :conformance:linkDebugTestLinuxArm64 --console=plain 2>&1
    echo ""
}

build_mingw() {
    [ -x "$MINGW_TEST_BIN" ] && return 0
    echo "Building C library for mingw..."
    make -C "$C_SRC_DIR" TARGET=mingw BUILDDIR=build-mingw lib 2>&1
    echo "Building mingwX64 test binary..."
    cd "$PROJECT_DIR"
    gradle :conformance:linkDebugTestMingwX64 --console=plain 2>&1
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

    require_db "$db"

    local rc=0
    if [ "$db" = "sqlite" ]; then
        "$C_TEST_BIN" sqlite ":memory:" || rc=$?
    else
        "$C_TEST_BIN" "$driver" "$url" "$user" "$pass" || rc=$?
    fi

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

    require_db "$db"

    local rc=0
    cd "$PROJECT_DIR"

    if [ -f "$config_path" ]; then
        gradle :conformance:jvmTest \
            -Dstormify.test.config="$config_path" \
            -Dstormify.test.db="$db" \
            --console=plain 2>&1 || rc=$?
    else
        gradle :conformance:jvmTest --console=plain 2>&1 || rc=$?
    fi

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

    require_db "$db"
    build_linux

    local rc=0
    STORMIFY_TEST_DB="$db" "$LINUX_TEST_BIN" 2>&1 || rc=$?

    if [ $rc -eq 0 ]; then
        echo "PASSED: linuxX64 $db"
    else
        echo "FAILED: linuxX64 $db (exit code: $rc)"
    fi
    echo ""
    return $rc
}

# ========================================================================
# Run Kotlin/Native linuxArm64 tests for one database
#
# On a native arm64 host (e.g. CI runner), runs the binary directly.
# On an x64 host, runs via Docker multiarch (QEMU emulation).
# Network databases use --network=host so the test binary can reach
# the Docker Compose containers on localhost ports.
# ========================================================================

run_linux_arm64_one() {
    local db="$1"

    echo "========================================="
    echo "Kotlin/Native linuxArm64 tests: $db"
    echo "========================================="

    require_db "$db"
    build_arm64

    local rc=0
    local host_arch
    host_arch="$(uname -m)"

    if [ "$host_arch" = "aarch64" ]; then
        # Native arm64 host — run directly
        STORMIFY_TEST_DB="$db" "$ARM64_TEST_BIN" 2>&1 || rc=$?
    else
        # x64 host — run via Docker multiarch (QEMU)
        docker run --rm --platform linux/arm64 \
            --network=host \
            -v "$PROJECT_DIR/stormify/build/bin/linuxArm64/debugTest:/test:ro" \
            -e "STORMIFY_TEST_DB=$db" \
            ubuntu:22.04 \
            bash -c '
                apt-get update -qq
                apt-get install -y -qq libsqlite3-0 libpq5 libmariadb3 libsybdb5 libatomic1 libaio1 git make gcc wget unzip > /dev/null 2>&1
                # ODPI-C runtime
                git clone --depth 1 --branch v5.6.4 https://github.com/oracle/odpi.git /tmp/odpi 2>/dev/null
                make -C /tmp/odpi -j4 > /dev/null 2>&1
                make -C /tmp/odpi install PREFIX=/usr/local > /dev/null 2>&1
                rm -rf /tmp/odpi
                # Oracle Instant Client arm64
                wget -q https://download.oracle.com/otn_software/linux/instantclient/2370000/instantclient-basiclite-linux.arm64-23.7.0.25.01.zip -O /tmp/ic.zip
                unzip -q /tmp/ic.zip -d /opt/oracle
                echo /opt/oracle/instantclient_* > /etc/ld.so.conf.d/oracle.conf
                ldconfig
                /test/test.kexe
            ' \
            2>&1 || rc=$?
    fi

    if [ $rc -eq 0 ]; then
        echo "PASSED: linuxArm64 $db"
    else
        echo "FAILED: linuxArm64 $db (exit code: $rc)"
    fi
    echo ""
    return $rc
}

# ========================================================================
# Run Android unit tests (Robolectric)
# ========================================================================

run_android() {
    echo "========================================="
    echo "Android unit tests (Robolectric)"
    echo "========================================="

    local rc=0
    cd "$PROJECT_DIR"
    gradle :conformance:testDebugUnitTest --console=plain 2>&1 || rc=$?

    if [ $rc -eq 0 ]; then
        echo "PASSED: android"
    else
        echo "FAILED: android (exit code: $rc)"
    fi
    echo ""
    return $rc
}

# ========================================================================
# Run mingwX64 tests for one database
# ========================================================================

build_mingw() {
    local test_exe="$PROJECT_DIR/stormify/build/bin/mingwX64/debugTest/test.exe"
    [ -x "$test_exe" ] && return 0

    echo "Building C library for mingw..."
    make -C "$C_SRC_DIR" TARGET=mingw BUILDDIR=build-mingw lib 2>&1

    echo "Building mingwX64 test binary..."
    cd "$PROJECT_DIR"
    gradle :conformance:linkDebugTestMingwX64 --console=plain 2>&1
}

run_mingw_one() {
    local db="$1"

    echo "========================================="
    echo "mingwX64 tests: $db"
    echo "========================================="

    require_db "$db"
    build_mingw

    local rc=0
    if command -v wine &>/dev/null; then
        STORMIFY_TEST_DB="$db" wine "$MINGW_TEST_BIN" 2>&1 || rc=$?
    else
        echo "Wine not available. Build succeeded; skipping execution."
        echo "Run on Windows: STORMIFY_TEST_DB=$db test.exe"
    fi

    if [ $rc -eq 0 ]; then
        echo "PASSED: mingw $db"
    else
        echo "FAILED: mingw $db (exit code: $rc)"
    fi
    echo ""
    return $rc
}

# ========================================================================
# Run example projects
# ========================================================================

ALL_EXAMPLES="java kotlin-jvm kotlin-linux kotlin-multiplatform"

run_example_one() {
    local example="$1"
    local example_dir="$PROJECT_DIR/examples/$example"

    echo "========================================="
    echo "Example: $example"
    echo "========================================="

    # Examples default to the last-released stormify version so they build
    # standalone from Maven Central. When run from inside the parent stormify
    # checkout (as here), we override that default with the parent's in-dev
    # version so the example resolves against whatever `publishToMavenLocal`
    # produced — validating the unreleased code end-to-end.
    local parent_version
    parent_version=$(grep -oE 'version = "[^"]+"' "$PROJECT_DIR/build.gradle.kts" \
        | head -1 | sed -E 's/version = "(.*)"/\1/')

    local rc=0
    case "$example" in
        java)
            cd "$example_dir"
            mvn clean compile exec:java -q -Dstormify.version="$parent_version" 2>&1 || rc=$?
            ;;
        kotlin-jvm)
            cd "$example_dir"
            gradle clean run -PstormifyVersion="$parent_version" --console=plain 2>&1 || rc=$?
            ;;
        kotlin-linux)
            cd "$example_dir"
            gradle clean runDebugExecutableLinuxX64 -PstormifyVersion="$parent_version" --console=plain 2>&1 || rc=$?
            ;;
        kotlin-multiplatform)
            cd "$example_dir"
            gradle clean jvmRun -DmainClass=demo.MainKt -PstormifyVersion="$parent_version" --console=plain 2>&1 || rc=$?
            ;;
    esac

    if [ $rc -eq 0 ]; then
        echo "PASSED: example $example"
    else
        echo "FAILED: example $example (exit code: $rc)"
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
    list)
        case "${DB:-}" in
            dbs)        echo $ALL_DBS ;;
            docker-dbs) for db in $ALL_DBS; do [ "$db" != "sqlite" ] && printf "%s " "$db"; done; echo ;;
            examples)   echo $ALL_EXAMPLES ;;
            *)          echo "Usage: ./test.sh list dbs|docker-dbs|examples"; exit 1 ;;
        esac
        ;;

    build)
        case "${DB:-}" in
            native)     build_native ;;
            linux)      build_linux ;;
            linux-arm64) build_arm64 ;;
            mingw)      build_mingw ;;
            jvm)        echo "Compiling JVM test classes..."
                        cd "$PROJECT_DIR"
                        gradle :conformance:jvmTestClasses --console=plain 2>&1 ;;
            android)    echo "Compiling Android test classes..."
                        cd "$PROJECT_DIR"
                        gradle :conformance:compileDebugUnitTestKotlin --console=plain 2>&1 ;;
            *)          echo "Usage: ./test.sh build native|linux|linux-arm64|mingw|jvm|android"; exit 1 ;;
        esac
        ;;

    check)
        # Exit 0 if all databases are ready, 1 otherwise
        missing=""
        for db in $ALL_DBS; do
            if ! check_db_ready "$db"; then
                missing="$missing $db"
            fi
        done
        if [ -n "$missing" ]; then
            echo "ERROR: Not running:$missing"
            echo "Start with: ./test.sh up  or  ./test-parallel.sh up"
            exit 1
        fi
        echo "All databases ready."
        ;;

    up)
        if [ -z "$DB" ] || [ "$DB" = "all" ]; then
            for db in $ALL_DBS; do
                start_db "$db"
            done
        else
            start_db "$DB"
        fi
        ;;

    down)
        if [ -z "$DB" ] || [ "$DB" = "all" ]; then
            stop_all_dbs
        else
            stop_db "$DB"
        fi
        ;;

    status)
        show_status
        ;;

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

    linux-arm64)
        if [ -n "$DB" ]; then
            run_linux_arm64_one "$DB"
        else
            run_for_dbs run_linux_arm64_one $ALL_DBS
        fi
        ;;

    android)
        run_android
        ;;

    mingw)
        if [ -n "$DB" ]; then
            run_mingw_one "$DB"
        else
            run_for_dbs run_mingw_one $ALL_DBS
        fi
        ;;

    examples)
        if [ -n "$DB" ]; then
            run_example_one "$DB"
        else
            run_for_dbs run_example_one $ALL_EXAMPLES
        fi
        ;;

    all)
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
        echo ""
        echo "############### KOTLIN/NATIVE ARM64 TESTS ###############"
        echo ""
        run_for_dbs run_linux_arm64_one $ALL_DBS || failed=$((failed + $?))
        echo ""
        echo "############### ANDROID TESTS ###############"
        echo ""
        run_android || failed=$((failed + 1))
        echo ""
        echo "############### MINGW TESTS ###############"
        echo ""
        run_for_dbs run_mingw_one $ALL_DBS || failed=$((failed + $?))
        echo ""
        echo "############### EXAMPLES ###############"
        echo ""
        run_for_dbs run_example_one $ALL_EXAMPLES || failed=$((failed + $?))
        exit $failed
        ;;

    *)
        usage
        ;;
esac
