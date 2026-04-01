#!/usr/bin/env bash
set -euo pipefail

COMPOSE_DIR="$(cd "$(dirname "$0")/.." && pwd)"

echo "Stopping all database containers..."
docker compose -f "$COMPOSE_DIR/docker-compose.yml" --profile all --profile all-versions down -v
echo "Done."
