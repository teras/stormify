#!/usr/bin/env bash
set -euo pipefail

DB_NAME="${1:?Usage: wait-for-db.sh <db-name>}"
COMPOSE_DIR="$(cd "$(dirname "$0")/.." && pwd)"

case "$DB_NAME" in
    oracle)   TIMEOUT=120 ;;
    oracle11) TIMEOUT=240 ;;
    mssql)    TIMEOUT=60 ;;
    *)        TIMEOUT=30 ;;
esac

echo "Waiting for $DB_NAME to be healthy (timeout: ${TIMEOUT}s)..."

elapsed=0
while [ $elapsed -lt $TIMEOUT ]; do
    status=$(docker compose -f "$COMPOSE_DIR/docker-compose.yml" ps --format json "$DB_NAME" 2>/dev/null \
        | grep -o '"Health":"[^"]*"' | head -1 | cut -d'"' -f4 || true)

    if [ "$status" = "healthy" ]; then
        echo "$DB_NAME is ready (took ${elapsed}s)"

        # MSSQL needs init script run after healthy
        if [ "$DB_NAME" = "mssql" ]; then
            echo "Running MSSQL init script..."
            docker compose -f "$COMPOSE_DIR/docker-compose.yml" exec -T mssql \
                /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P 'Stormify1!' -C -i /init.sql 2>/dev/null \
            || docker compose -f "$COMPOSE_DIR/docker-compose.yml" exec -T mssql \
                /opt/mssql-tools/bin/sqlcmd -S localhost -U sa -P 'Stormify1!' -i /init.sql 2>/dev/null \
            || echo "Warning: MSSQL init script may have failed"
        fi

        exit 0
    fi

    sleep 2
    elapsed=$((elapsed + 2))
done

echo "ERROR: $DB_NAME did not become healthy within ${TIMEOUT}s"
docker compose -f "$COMPOSE_DIR/docker-compose.yml" logs "$DB_NAME" --tail 20
exit 1
