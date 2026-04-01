#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
COMPOSE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
PROJECT_DIR="$(cd "$COMPOSE_DIR/.." && pwd)"

usage() {
    echo "Usage: $0 <db-name> [--keep] [--module db|kotlin|all]"
    echo ""
    echo "Databases: mysql | mariadb | postgresql | oracle | mssql | sqlite"
    echo ""
    echo "Options:"
    echo "  --keep     Don't stop the container after tests (for debugging)"
    echo "  --module   Which Gradle module to test (default: all)"
    exit 1
}

DB_NAME="${1:?$(usage)}"
shift

KEEP=false
MODULE="all"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --keep) KEEP=true; shift ;;
        --module) MODULE="${2:?--module requires a value}"; shift 2 ;;
        *) usage ;;
    esac
done

# Validate database name
case "$DB_NAME" in
    mysql|mariadb|postgresql|oracle|mssql|sqlite) ;;
    *) echo "ERROR: Unknown database '$DB_NAME'"; usage ;;
esac

# Determine Gradle test targets
case "$MODULE" in
    db)     GRADLE_TARGETS=":db:test" ;;
    kotlin) GRADLE_TARGETS=":kotlin:test" ;;
    all)    GRADLE_TARGETS=":db:test :kotlin:test" ;;
    *)      echo "ERROR: Unknown module '$MODULE'"; usage ;;
esac

CONFIG_PATH="$COMPOSE_DIR/config/$DB_NAME/hikari.properties"

echo "========================================="
echo "Testing Stormify against: $DB_NAME"
echo "========================================="

# Start container (unless sqlite)
if [ "$DB_NAME" != "sqlite" ]; then
    echo "Starting $DB_NAME container..."
    docker compose -f "$COMPOSE_DIR/docker-compose.yml" --profile "$DB_NAME" up -d

    # Wait for healthy
    "$SCRIPT_DIR/wait-for-db.sh" "$DB_NAME"
fi

# Run tests
echo "Running Gradle tests..."
EXIT_CODE=0
cd "$PROJECT_DIR"
gradle $GRADLE_TARGETS \
    -Dstormify.test.config="$CONFIG_PATH" \
    -Dstormify.test.db="$DB_NAME" \
    || EXIT_CODE=$?

# Stop container
if [ "$DB_NAME" != "sqlite" ] && [ "$KEEP" = false ]; then
    echo "Stopping $DB_NAME container..."
    docker compose -f "$COMPOSE_DIR/docker-compose.yml" --profile "$DB_NAME" down -v
fi

echo ""
if [ $EXIT_CODE -eq 0 ]; then
    echo "PASSED: $DB_NAME"
else
    echo "FAILED: $DB_NAME (exit code: $EXIT_CODE)"
fi

exit $EXIT_CODE
