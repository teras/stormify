#!/usr/bin/env bash
# Provision and tear down database servers on macOS via Homebrew services.
# Used by the macos-native CI matrix job (no Docker — Docker on macOS would
# require colima/VM overhead).
#
# Each database is configured to listen on the SAME ports as testing/docker-compose.yml
# so the test factory (tests/conformance/src/linuxX64Test/kotlin/test/TestDataSourceFactory.kt)
# does not need macOS-specific URL overrides.
#
# Credentials match docker-compose: user "stormify", password "Stormify1!", database "stormify_test".
#
# Usage:
#   ./macos-services.sh up <db>     — install, configure, start, create user/db
#   ./macos-services.sh down <db>   — stop service

set -euo pipefail

CMD="${1:-}"
DB="${2:-}"

if [ -z "$CMD" ] || [ -z "$DB" ]; then
    echo "Usage: $0 up|down <db>" >&2
    exit 1
fi

BREW_PREFIX="$(brew --prefix)"

wait_for() {
    # $1 = description, $2 = test command (eval'd)
    local desc="$1"; local check="$2"
    for i in $(seq 1 60); do
        if eval "$check" >/dev/null 2>&1; then
            echo "  ✓ $desc ready"
            return 0
        fi
        sleep 1
    done
    echo "  ✗ $desc did not become ready within 60s" >&2
    return 1
}

up_postgresql() {
    brew install postgresql@15
    local pgbin="$BREW_PREFIX/opt/postgresql@15/bin"
    local datadir="$BREW_PREFIX/var/postgresql@15"
    # Reset to a known clean state on each CI run
    rm -rf "$datadir"
    # Default initdb writes pg_hba with `trust` for local + 127.0.0.1/::1.
    # Trust means PostgreSQL ignores the password entirely — perfect for tests
    # since the stormify user's password matches the test factory but auth
    # never actually verifies it.
    "$pgbin/initdb" -D "$datadir" -U "$(whoami)"
    # Listen on docker-compose port (15432) so test factory URLs work as-is
    echo "port = 15432" >> "$datadir/postgresql.conf"
    brew services start postgresql@15
    wait_for "PostgreSQL" "$pgbin/psql -h localhost -p 15432 -U $(whoami) -d postgres -c 'SELECT 1'"
    "$pgbin/psql" -h localhost -p 15432 -U "$(whoami)" -d postgres -c \
        "CREATE USER stormify WITH PASSWORD 'Stormify1!' SUPERUSER;"
    "$pgbin/psql" -h localhost -p 15432 -U "$(whoami)" -d postgres -c \
        "CREATE DATABASE stormify_test OWNER stormify;"
    # Per-encoding databases mirror testing/config/postgresql/init.sql so the
    # NativeServerEncodingTest finds the same fixtures as the docker setup.
    "$pgbin/psql" -h localhost -p 15432 -U "$(whoami)" -d postgres <<SQL
CREATE DATABASE stormify_enc_latin1
    ENCODING 'LATIN1'     LC_COLLATE 'C' LC_CTYPE 'C' TEMPLATE template0
    OWNER stormify;
CREATE DATABASE stormify_enc_iso7
    ENCODING 'ISO_8859_7' LC_COLLATE 'C' LC_CTYPE 'C' TEMPLATE template0
    OWNER stormify;
CREATE DATABASE stormify_enc_utf8
    ENCODING 'UTF8'       LC_COLLATE 'C' LC_CTYPE 'C' TEMPLATE template0
    OWNER stormify;
SQL
}

down_postgresql() {
    brew services stop postgresql@15 || true
}

up_mysql() {
    brew install mysql
    local mybin="$BREW_PREFIX/opt/mysql/bin"
    local datadir="$BREW_PREFIX/var/mysql"
    # Initialize on docker-compose port (13306)
    rm -rf "$datadir"
    "$mybin/mysqld" --initialize-insecure --datadir="$datadir"
    # Override port via my.cnf
    cat > "$BREW_PREFIX/etc/my.cnf" <<EOF
[mysqld]
port = 13306
datadir = $datadir
[client]
port = 13306
EOF
    brew services start mysql
    wait_for "MySQL" "$mybin/mysql -h 127.0.0.1 -P 13306 -u root -e 'SELECT 1'"
    "$mybin/mysql" -h 127.0.0.1 -P 13306 -u root <<SQL
CREATE DATABASE stormify_test;
CREATE USER 'stormify'@'%' IDENTIFIED BY 'Stormify1!';
CREATE USER 'stormify'@'localhost' IDENTIFIED BY 'Stormify1!';
GRANT ALL PRIVILEGES ON stormify_test.* TO 'stormify'@'%';
GRANT ALL PRIVILEGES ON stormify_test.* TO 'stormify'@'localhost';
FLUSH PRIVILEGES;
SQL
    "$mybin/mysql" -h 127.0.0.1 -P 13306 -u stormify -pStormify1! -D stormify_test \
        -e 'SELECT 1' || {
        echo "ERROR: stormify user cannot authenticate to MySQL via TCP" >&2
        exit 1
    }
}

down_mysql() {
    brew services stop mysql || true
}

up_mariadb() {
    brew install mariadb
    local mdbin="$BREW_PREFIX/opt/mariadb/bin"
    local datadir="$BREW_PREFIX/var/mysql"
    rm -rf "$datadir"
    "$mdbin/mariadb-install-db" --datadir="$datadir" --auth-root-authentication-method=normal
    mkdir -p "$BREW_PREFIX/etc/my.cnf.d"
    cat > "$BREW_PREFIX/etc/my.cnf.d/stormify.cnf" <<EOF
[mariadbd]
port = 13307
datadir = $datadir
[client]
port = 13307
EOF
    brew services start mariadb
    wait_for "MariaDB" "$mdbin/mariadb -h 127.0.0.1 -P 13307 -u root -e 'SELECT 1'"
    "$mdbin/mariadb" -h 127.0.0.1 -P 13307 -u root <<SQL
CREATE DATABASE stormify_test;
CREATE USER 'stormify'@'%' IDENTIFIED BY 'Stormify1!';
CREATE USER 'stormify'@'localhost' IDENTIFIED BY 'Stormify1!';
GRANT ALL PRIVILEGES ON stormify_test.* TO 'stormify'@'%';
GRANT ALL PRIVILEGES ON stormify_test.* TO 'stormify'@'localhost';
FLUSH PRIVILEGES;
SQL
    # Sanity: verify stormify user can authenticate via TCP with password.
    "$mdbin/mariadb" -h 127.0.0.1 -P 13307 -u stormify -pStormify1! -D stormify_test \
        -e 'SELECT 1' || {
        echo "ERROR: stormify user cannot authenticate to MariaDB via TCP" >&2
        exit 1
    }
}

down_mariadb() {
    brew services stop mariadb || true
}

case "$CMD" in
    up)
        case "$DB" in
            postgresql)  up_postgresql ;;
            mysql)       up_mysql ;;
            mariadb)     up_mariadb ;;
            sqlite)      ;;  # nothing to do
            *)           echo "Unsupported macOS DB: $DB" >&2; exit 1 ;;
        esac
        ;;
    down)
        case "$DB" in
            postgresql)  down_postgresql ;;
            mysql)       down_mysql ;;
            mariadb)     down_mariadb ;;
            sqlite)      ;;
            *)           echo "Unsupported macOS DB: $DB" >&2; exit 1 ;;
        esac
        ;;
    *)
        echo "Usage: $0 up|down <db>" >&2
        exit 1
        ;;
esac
