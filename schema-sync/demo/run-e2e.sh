#!/usr/bin/env bash
# End-to-end test: init schema → run schema-sync → apply migration → verify.
# Per-dialect. Assumes containers from testing/test.sh up are running.

set -euo pipefail
cd "$(dirname "$0")"

DIALECT="${1:-sqlite}"
DEMO_DIR="$(pwd)"
REPO_ROOT="$DEMO_DIR/../.."
SOURCES_DIR="$DEMO_DIR/sources"
OUT_DIR="$DEMO_DIR/out"
mkdir -p "$OUT_DIR"

CONFIG_TOML="$OUT_DIR/.schema-sync.toml"
DEFAULT_TOML="$REPO_ROOT/schema-sync/src/main/resources/default-config.toml"
\cp "$DEFAULT_TOML" "$CONFIG_TOML"
cat "$DEMO_DIR/assignments.toml" >> "$CONFIG_TOML"

case "$DIALECT" in
    sqlite)
        DB_FILE="$OUT_DIR/demo.db"
        rm -f "$DB_FILE"
        sqlite3 "$DB_FILE" < "$DEMO_DIR/db/init-sqlite.sql"
        URL="jdbc:sqlite:$DB_FILE"
        APPLY_CMD="sqlite3 \"$DB_FILE\""
        ;;
    postgresql)
        URL="jdbc:postgresql://localhost:15432/stormify_test?user=stormify&password=Stormify1!"
        PGPASSWORD=Stormify1! psql -h localhost -p 15432 -U stormify -d stormify_test -f "$DEMO_DIR/db/init-postgresql.sql"
        APPLY_CMD="PGPASSWORD=Stormify1! psql -h localhost -p 15432 -U stormify -d stormify_test"
        ;;
    mysql)
        URL="jdbc:mysql://localhost:13306/stormify_test?user=stormify&password=Stormify1!"
        mysql -h 127.0.0.1 -P 13306 -u stormify -pStormify1! stormify_test < "$DEMO_DIR/db/init-mysql.sql"
        APPLY_CMD="mysql -h 127.0.0.1 -P 13306 -u stormify -pStormify1! stormify_test"
        ;;
    mariadb)
        URL="jdbc:mariadb://localhost:13307/stormify_test?user=stormify&password=Stormify1!"
        mysql -h 127.0.0.1 -P 13307 -u stormify -pStormify1! stormify_test < "$DEMO_DIR/db/init-mariadb.sql"
        APPLY_CMD="mysql -h 127.0.0.1 -P 13307 -u stormify -pStormify1! stormify_test"
        ;;
    oracle)
        URL="jdbc:oracle:thin:stormify/Stormify1!@localhost:11521/XEPDB1"
        SQLPLUS="docker exec -i testing-oracle-1 /opt/oracle/product/21c/dbhomeXE/bin/sqlplus -S stormify/Stormify1!@//localhost:1521/XEPDB1"
        $SQLPLUS < "$DEMO_DIR/db/init-oracle.sql"
        APPLY_CMD="$SQLPLUS"
        ;;
    mssql)
        URL="jdbc:sqlserver://localhost:11433;databaseName=stormify_test;user=sa;password=Stormify1!;encrypt=false"
        SQLCMD="docker exec -i testing-mssql-1 /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P Stormify1! -d stormify_test -C"
        $SQLCMD < "$DEMO_DIR/db/init-mssql.sql"
        APPLY_CMD="$SQLCMD"
        ;;
    *)
        echo "Unknown dialect: $DIALECT"
        exit 1
        ;;
esac

OUT_SQL="$OUT_DIR/migration-$DIALECT.sql"
echo "=== Running schema-sync against $DIALECT ==="
(cd "$REPO_ROOT" && gradle :schema-sync:migrationDemo --args="$URL $SOURCES_DIR $OUT_SQL --config=$CONFIG_TOML" -q 2>&1 | grep -v SLF4J)
echo
echo "=== Generated SQL ==="
cat "$OUT_SQL"
echo
echo "=== Applying to $DIALECT ==="
eval "$APPLY_CMD" < "$OUT_SQL"
echo "✓ migration applied cleanly"
