#!/usr/bin/env bash
# Launch schema-sync against one of the demo variants. Pass one of:
#
#   ./run.sh ctor-all     # all properties in the constructor (sources-ctor-all)
#   ./run.sh ctor-pk      # only the primary key in the constructor (sources-ctor-pk)
#   ./run.sh body         # all properties in the class body (sources-body)
#   ./run.sh body-spaced  # like ctor-pk, but body decls separated by blank lines
#   ./run.sh <db>         # synthetic 800-table / 1000-entity stress fixture
#                         # against <db> ∈ { sqlite postgresql postgresql9
#                         # mysql mysql5 mariadb oracle oracle11 mssql }.
#                         # Non-sqlite databases must be running:
#                         #   ../testing/test.sh up <db>
#
# The first four share the same pre-baked demo.db; only the Kotlin entity
# layout (constructor style) differs. The database variants are seeded on
# demand by the :schema-sync:seedMockup gradle task — see
# src/test/kotlin/.../mockup/README.md for the spec.
#
# Each variant materialises into its own work directory under build/, so
# the .schema-sync.toml + any user edits stay scoped. `gradle clean`
# wipes everything.

set -euo pipefail

print_usage() {
    {
        echo "usage: $0 <variant>"
        echo "  entity-style: ctor-all | ctor-pk | body | body-spaced"
        echo "  mockup db:    sqlite | postgresql | postgresql9 | mysql | mysql5 |"
        echo "                mariadb | oracle | oracle11 | mssql"
    } >&2
}

if [[ $# -lt 1 ]]; then
    print_usage
    exit 64
fi

VARIANT="$1"
case "$VARIANT" in
    ctor-all|ctor-pk|body|body-spaced|sqlite|postgresql|postgresql9|mysql|mysql5|mariadb|oracle|oracle11|mssql) ;;
    *) print_usage; exit 64;;
esac
shift

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SCHEMA_SYNC_DIR="$(dirname "$SCRIPT_DIR")"
REPO_ROOT="$(dirname "$SCHEMA_SYNC_DIR")"

JAR="$SCHEMA_SYNC_DIR/build/libs/schema-sync-all.jar"

# Mockup connection coordinates per database. Mirrors testing/test.sh so
# the same docker containers can host both the conformance suite and the
# mockup fixture without colliding (mockup uses its own object names).
mockup_url() {
    case "$1" in
        sqlite)      echo "jdbc:sqlite:$WORK_DIR/demo.db" ;;
        postgresql)  echo "jdbc:postgresql://localhost:15432/stormify_test" ;;
        postgresql9) echo "jdbc:postgresql://localhost:15431/stormify_test" ;;
        mysql)       echo "jdbc:mysql://localhost:13306/stormify_test" ;;
        mysql5)      echo "jdbc:mysql://localhost:13305/stormify_test" ;;
        mariadb)     echo "jdbc:mariadb://localhost:13307/stormify_test" ;;
        oracle)      echo "jdbc:oracle:thin:@localhost:11521/XEPDB1" ;;
        oracle11)    echo "jdbc:oracle:thin:@localhost:11524:XE" ;;
        mssql)       echo "jdbc:sqlserver://localhost:11433;databaseName=stormify_test;encrypt=false" ;;
        *) echo "Unknown database: $1" >&2; return 1 ;;
    esac
}
mockup_user()     { case "$1" in sqlite) echo "";; mssql) echo "sa";; *) echo "stormify";; esac; }
mockup_password() { case "$1" in sqlite) echo "";; *) echo "Stormify1!";; esac; }

if [[ ! -f "$JAR" ]]; then
    echo "Building schema-sync fatJar..."
    (cd "$REPO_ROOT" && gradle :schema-sync:fatJar -q)
fi

case "$VARIANT" in
    ctor-all|ctor-pk|body|body-spaced)
        DB="sqlite"
        URL="jdbc:sqlite:./demo.db"
        USER=""
        PASSWORD=""
        WORK_DIR="$SCHEMA_SYNC_DIR/build/demo-$VARIANT"
        SOURCES_DIR="$SCRIPT_DIR/sources-$VARIANT"
        if [[ ! -d "$WORK_DIR" ]]; then
            echo "Materialising demo work dir at $WORK_DIR (variant=$VARIANT)"
            mkdir -p "$WORK_DIR"
            \cp "$SCRIPT_DIR/demo.db" "$WORK_DIR/demo.db"
            \cp -r "$SOURCES_DIR" "$WORK_DIR/sources"
        fi
        ;;
    *)
        DB="$VARIANT"
        WORK_DIR="$SCHEMA_SYNC_DIR/build/demo-mockup-$DB"
        USER="$(mockup_user "$DB")"
        PASSWORD="$(mockup_password "$DB")"
        # `mockup_url` references WORK_DIR for the sqlite case, so resolve
        # it AFTER WORK_DIR is computed.
        URL="$(mockup_url "$DB")" || exit 64

        # The mockup work dir is materialised on demand via the seedMockup
        # gradle task. We re-seed only when the work dir is missing — the
        # FULL shape takes ~10s on sqlite, longer on networked databases,
        # and the user typically wants the same fixture on subsequent runs.
        if [[ ! -d "$WORK_DIR" && "$DB" != "sqlite" ]]; then
            # Pre-flight container check so the user gets a clear error
            # before the seeder fails with a wrapped JDBC exception.
            if ! "$SCHEMA_SYNC_DIR/../testing/test.sh" status 2>/dev/null \
                    | awk -v db="$DB" '$1==db && $3=="healthy" { found=1 } END { exit !found }'; then
                echo "Database '$DB' is not running. Start it with:" >&2
                echo "  ../testing/test.sh up $DB" >&2
                exit 1
            fi
        fi
        if [[ ! -d "$WORK_DIR" ]]; then
            echo "Seeding mockup at $WORK_DIR (database=$DB)"
            mkdir -p "$WORK_DIR"
            SEED_ARGS=(
                "--jdbc-url=$URL"
                "--entities-dir=$WORK_DIR/sources"
                "--shape=full"
                "--rows-per-table=100"
            )
            [[ -n "$USER" ]] && SEED_ARGS+=("--user=$USER")
            [[ -n "$PASSWORD" ]] && SEED_ARGS+=("--password=$PASSWORD")
            (cd "$REPO_ROOT" && gradle :schema-sync:seedMockup -q --args="${SEED_ARGS[*]}")
        fi
        ;;
esac

cd "$WORK_DIR"
# --sun-misc-unsafe-memory-access=allow silences the JVM's "terminally
# deprecated method in sun.misc.Unsafe" warnings printed by HotSpot directly
# to FD 2 (bypassing System.err) when the bundled Kotlin compiler embeddable
# loads. Available on JDK 23+; older JDKs ignore the flag harmlessly.
RUN_ARGS=(--url "$URL" --sources "./sources")
[[ -n "$USER" ]] && RUN_ARGS+=(--user "$USER")
[[ -n "$PASSWORD" ]] && RUN_ARGS+=(--password "$PASSWORD")
exec java --sun-misc-unsafe-memory-access=allow -jar "$JAR" "${RUN_ARGS[@]}" "$@"
