#!/usr/bin/env bash
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

DATABASES="mysql mariadb postgresql oracle mssql sqlite"
declare -A RESULTS

echo "========================================="
echo "Stormify Multi-Database Test Suite"
echo "========================================="
echo ""

for db in $DATABASES; do
    echo "--- Testing: $db ---"
    if "$SCRIPT_DIR/test-db.sh" "$db" "$@"; then
        RESULTS[$db]="PASS"
    else
        RESULTS[$db]="FAIL"
    fi
    echo ""
done

echo "========================================="
echo "Results Summary"
echo "========================================="
printf "%-15s %s\n" "Database" "Status"
printf "%-15s %s\n" "--------" "------"

FAILED=0
for db in $DATABASES; do
    status="${RESULTS[$db]}"
    printf "%-15s %s\n" "$db" "$status"
    if [ "$status" = "FAIL" ]; then
        FAILED=$((FAILED + 1))
    fi
done

echo ""
if [ $FAILED -eq 0 ]; then
    echo "All databases passed."
else
    echo "$FAILED database(s) failed."
fi

exit $FAILED
