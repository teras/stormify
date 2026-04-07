# KDBC — Unified C Database Connectivity

A lightweight C11 library that provides a single API for multiple database backends.
Libraries are loaded at runtime via `dlopen`, so missing backends are handled
gracefully — only the drivers you actually use need to be installed.

## Supported Databases

| Database | Driver | Native Library |
|----------|--------|----------------|
| SQLite | `KDBC_SQLITE` | libsqlite3 |
| PostgreSQL | `KDBC_POSTGRES` | libpq |
| MariaDB / MySQL | `KDBC_MARIADB` | libmariadb |
| Oracle | `KDBC_ORACLE` | ODPI-C |
| MS SQL Server | `KDBC_MSSQL` | FreeTDS |

## Quick Example

```c
#include "kdbc.h"
#include <stdio.h>

int main(void) {
    kdbc_conn *conn = kdbc_connect(KDBC_SQLITE, ":memory:", NULL, NULL);

    kdbc_execute_update(conn,
        "CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT)");

    kdbc_stmt *stmt = kdbc_prepare(conn, "INSERT INTO users (name) VALUES (?)");
    kdbc_bind_string(stmt, 1, "Alice");
    kdbc_execute_update_stmt(stmt);
    kdbc_stmt_close(stmt);

    kdbc_result *rs = kdbc_execute_query(conn, "SELECT id, name FROM users");
    while (kdbc_next(rs) == 1)
        printf("%lld: %s\n", (long long)kdbc_get_long(rs, 1), kdbc_get_string(rs, 2));
    kdbc_result_close(rs);

    kdbc_close(conn);
}
```

## Key Design Points

- **Opaque handles**: `kdbc_conn`, `kdbc_stmt`, `kdbc_result`
- **Runtime driver loading**: no link-time dependency on any database client
- **JDBC-style `?` placeholders**: auto-translated to `$1` (PostgreSQL), `:1` (Oracle), or native `?`
- **1-indexed** column and parameter indices
- **Per-handle error buffers** with thread-local global fallback

## API Groups

Browse the API by section using the navigation on the left, or see the
[complete reference for kdbc.h](kdbc_8h.html).
