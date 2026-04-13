# KDBC C Usage Guide

This guide covers practical usage of the KDBC C library (`libkdbc`). For the complete API reference,
see the [Doxygen documentation](kdbc-c/html/index.html).

## Building

### Prerequisites

- C11-compatible compiler (GCC, Clang, or MinGW for cross-compilation)
- Database client headers are vendored in `include/vendor/` — no system packages needed for compilation

!!! note "All drivers compile unconditionally"
    The build compiles all five driver backends using vendored headers.
    Missing runtime libraries are detected lazily via `dlopen` — you only need the client
    library installed on the machine where the application **runs**, not where it's built.

For **running** tests or applications, install the runtime libraries for the databases you use:

=== "Debian / Ubuntu"

    ```bash
    sudo apt install libsqlite3-0     # SQLite
    sudo apt install libpq5           # PostgreSQL
    sudo apt install libmariadb3      # MariaDB / MySQL
    sudo apt install libsybdb5        # MS SQL Server
    ```

=== "Arch Linux"

    ```bash
    sudo pacman -S sqlite             # SQLite
    sudo pacman -S postgresql-libs    # PostgreSQL
    sudo pacman -S mariadb-libs       # MariaDB / MySQL
    sudo pacman -S freetds            # MS SQL Server
    ```

### Build Commands

```bash
cd kdbc/src/c

# Build static and shared libraries (Linux x64)
make lib

# Cross-compile for Windows x64
make TARGET=mingw BUILDDIR=build-mingw lib

# Cross-compile for Linux ARM64
make TARGET=arm64 BUILDDIR=build-arm64 lib

# Run test suite (SQLite in-memory by default)
make test

# Generate Doxygen API reference
make docs

# Clean build artifacts
make clean
```

This produces:

- `libkdbc.a` -- static library
- `libkdbc.so` / `libkdbc.dll` -- shared library

### Linking Your Application

**Static linking** (recommended for deployment):

```bash
gcc -o myapp myapp.c libkdbc.a -ldl -lpthread
```

**Shared linking**:

```bash
gcc -o myapp myapp.c -L. -lkdbc -ldl -lpthread
```

No database client libraries are needed at link time. They are loaded at runtime via `dlopen`.

## Quick Start

A complete example that creates a table, inserts a row, and queries it back:

```c
#include "kdbc.h"
#include <stdio.h>

int main(void) {
    // Check driver availability
    if (!kdbc_driver_available(KDBC_SQLITE)) {
        fprintf(stderr, "SQLite not available\n");
        return 1;
    }

    // Connect
    kdbc_conn *conn = kdbc_connect(KDBC_SQLITE, ":memory:", NULL, NULL);
    if (!conn) {
        fprintf(stderr, "Connect failed: %s\n", kdbc_global_error());
        return 1;
    }

    // Create table
    kdbc_execute_update(conn,
        "CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT, age INTEGER)");

    // Insert with prepared statement
    kdbc_stmt *stmt = kdbc_prepare(conn,
        "INSERT INTO users (name, age) VALUES (?, ?)");
    kdbc_bind_string(stmt, 1, "Alice");
    kdbc_bind_int(stmt, 2, 30);
    kdbc_execute_update_stmt(stmt);
    kdbc_stmt_close(stmt);

    // Query
    kdbc_result *rs = kdbc_execute_query(conn,
        "SELECT id, name, age FROM users");
    while (kdbc_next(rs) == 1) {
        printf("id=%lld name=%s age=%lld\n",
               (long long)kdbc_get_long(rs, 1),
               kdbc_get_string(rs, 2),
               (long long)kdbc_get_long(rs, 3));
    }
    kdbc_result_close(rs);

    kdbc_close(conn);
    return 0;
}
```

Output:

```text
id=1 name=Alice age=30
```

## Connection URLs

Each driver expects a URL in a specific format, passed as the second argument to `kdbc_connect`:

| Driver | URL Format | Example |
|--------|-----------|---------|
| SQLite | File path or `:memory:` | `/tmp/test.db` |
| PostgreSQL | `host:port/database` | `localhost:5432/mydb` |
| MariaDB / MySQL | `host:port/database` | `localhost:3306/mydb` |
| Oracle | `host:port/service_name` | `localhost:1521/XEPDB1` |
| MSSQL | `host:port/database` | `localhost:1433/mydb` |

SQLite also supports URI-style paths (e.g., `file::memory:?cache=shared`).

For network databases, the `user` and `password` parameters are passed as the third and fourth
arguments to `kdbc_connect`.

```c
kdbc_conn *conn = kdbc_connect(KDBC_POSTGRES,
    "localhost:5432/mydb", "dbuser", "dbpass");
```

## Error Handling

KDBC uses a consistent error pattern: check the return code, then retrieve the error message.

**Connection errors** (before a handle exists):

```c
kdbc_conn *conn = kdbc_connect(KDBC_POSTGRES, "badhost:5432/db", "u", "p");
if (!conn) {
    fprintf(stderr, "Connect failed: %s\n", kdbc_global_error());
}
```

**Connection-level errors** (on an open connection):

```c
int rc = kdbc_execute_update(conn, "DROP TABLE nonexistent");
if (rc == KDBC_ERROR) {
    fprintf(stderr, "Error: %s\n", kdbc_error(conn));
}
```

**Statement-level errors** (on a prepared statement):

```c
int rc = kdbc_execute_update_stmt(stmt);
if (rc == KDBC_ERROR) {
    fprintf(stderr, "Error: %s\n", kdbc_stmt_error(stmt));
}
```

Error messages from the underlying database pass through directly. For example, Oracle errors
include the ORA code (`ORA-00942: table or view does not exist`), PostgreSQL errors include
the SQLSTATE, and so on.

!!! tip "Error pointer lifetime"
    The string returned by `kdbc_error`, `kdbc_stmt_error`, and `kdbc_global_error` is valid
    until the next KDBC call on the same handle (or until the handle is closed). Copy it if
    you need to keep it.

## Transactions

Autocommit is **on** by default. To use explicit transactions:

```c
// Disable autocommit
kdbc_set_autocommit(conn, 0);

kdbc_stmt *stmt = kdbc_prepare(conn, "INSERT INTO accounts (name, balance) VALUES (?, ?)");

// First insert
kdbc_bind_string(stmt, 1, "Alice");
kdbc_bind_double(stmt, 2, 1000.0);
kdbc_execute_update_stmt(stmt);
kdbc_stmt_reset(stmt);

// Second insert
kdbc_bind_string(stmt, 1, "Bob");
kdbc_bind_double(stmt, 2, 2000.0);
kdbc_execute_update_stmt(stmt);

// Commit both
kdbc_commit(conn);

kdbc_stmt_close(stmt);
```

To roll back on error:

```c
kdbc_set_autocommit(conn, 0);

int rc = kdbc_execute_update(conn, "INSERT INTO ...");
if (rc == KDBC_ERROR) {
    kdbc_rollback(conn);
} else {
    kdbc_commit(conn);
}
```

### Savepoints

Savepoints allow partial rollback within a transaction:

```c
kdbc_set_autocommit(conn, 0);

kdbc_execute_update(conn, "INSERT INTO t VALUES (1, 'a')");

kdbc_savepoint(conn, "sp1");
kdbc_execute_update(conn, "INSERT INTO t VALUES (2, 'b')");

// Undo only the second insert
kdbc_rollback_to(conn, "sp1");

// Release the savepoint (frees resources)
kdbc_release_savepoint(conn, "sp1");

// Commit -- only the first insert survives
kdbc_commit(conn);
```

!!! warning "RELEASE SAVEPOINT support"
    Oracle and MSSQL do not support `RELEASE SAVEPOINT`. On these databases,
    `kdbc_release_savepoint` is a no-op. You can check at runtime with
    `kdbc_driver_supports_release_savepoint(driver)`.

## Prepared Statements and Parameter Binding

### Binding Parameters

All parameter indices are **1-based**. Available binding functions:

| Function | C Type | SQL Type |
|----------|--------|----------|
| `kdbc_bind_null(stmt, idx)` | -- | NULL |
| `kdbc_bind_int(stmt, idx, val)` | `int` | INTEGER |
| `kdbc_bind_long(stmt, idx, val)` | `int64_t` | BIGINT |
| `kdbc_bind_double(stmt, idx, val)` | `double` | DOUBLE / FLOAT |
| `kdbc_bind_string(stmt, idx, val)` | `const char *` | VARCHAR / TEXT |
| `kdbc_bind_blob(stmt, idx, data, len)` | `const void *, size_t` | BLOB / BYTEA |
| `kdbc_bind_timestamp(stmt, idx, ...)` | year..usec | TIMESTAMP |
| `kdbc_bind_date(stmt, idx, ...)` | year, month, day | DATE |
| `kdbc_bind_time(stmt, idx, ...)` | hour..usec | TIME |

String and blob values are **copied internally** by the driver, so you can free or reuse the
source buffer after binding.

### Placeholder Translation

You always write `?` in your SQL. KDBC translates for each driver:

```c
// This works on all five databases:
kdbc_stmt *stmt = kdbc_prepare(conn,
    "SELECT * FROM users WHERE name = ? AND age > ?");
kdbc_bind_string(stmt, 1, "Alice");
kdbc_bind_int(stmt, 2, 25);
```

Behind the scenes, PostgreSQL sees `$1, $2` and Oracle sees `:1, :2`.

### Statement Reuse

Use `kdbc_stmt_reset` to reuse a prepared statement with new parameters.
Reset clears bound parameters, pending batch entries, generated key state,
cached OUT-parameter values, and the error buffer:

```c
kdbc_stmt *stmt = kdbc_prepare(conn,
    "INSERT INTO users (name, age) VALUES (?, ?)");

// First row
kdbc_bind_string(stmt, 1, "Alice");
kdbc_bind_int(stmt, 2, 30);
kdbc_execute_update_stmt(stmt);
kdbc_stmt_reset(stmt);

// Second row
kdbc_bind_string(stmt, 1, "Bob");
kdbc_bind_int(stmt, 2, 25);
kdbc_execute_update_stmt(stmt);

kdbc_stmt_close(stmt);
```

## Batch Execution

For inserting many rows efficiently, use batch execution:

```c
kdbc_stmt *stmt = kdbc_prepare(conn,
    "INSERT INTO users (name, age) VALUES (?, ?)");

// Add rows to batch
kdbc_bind_string(stmt, 1, "Alice");
kdbc_bind_int(stmt, 2, 30);
kdbc_add_batch(stmt);

kdbc_bind_string(stmt, 1, "Bob");
kdbc_bind_int(stmt, 2, 25);
kdbc_add_batch(stmt);

kdbc_bind_string(stmt, 1, "Carol");
kdbc_bind_int(stmt, 2, 28);
kdbc_add_batch(stmt);

// Execute all rows at once
int affected = kdbc_execute_batch(stmt);
printf("Inserted %d rows\n", affected);

kdbc_stmt_close(stmt);
```

## Generated Keys

To retrieve auto-generated keys (e.g., auto-increment IDs) after an INSERT:

```c
// Prepare with column names for RETURNING
const char *cols[] = {"id"};
kdbc_stmt *stmt = kdbc_prepare_returning(conn,
    "INSERT INTO users (name) VALUES (?)", cols, 1);

kdbc_bind_string(stmt, 1, "Alice");
kdbc_execute_update_stmt(stmt);

// Retrieve generated keys
kdbc_result *keys = kdbc_generated_keys(stmt);
if (keys && kdbc_next(keys) == 1) {
    printf("Generated ID: %lld\n", (long long)kdbc_get_long(keys, 1));
}
kdbc_result_close(keys);
kdbc_stmt_close(stmt);
```

The strategy varies by database (see `kdbc_gk_strategy`). For databases using
`KDBC_GK_BY_INDEX` (SQLite, MySQL, MSSQL), the column names are optional -- the driver uses
`last_insert_id()` or `SCOPE_IDENTITY()` internally.

## Stored Procedures

KDBC supports calling stored procedures using JDBC-style syntax:

```c
// Prepare the call
kdbc_stmt *stmt = kdbc_prepare_call(conn, "{CALL add_numbers(?, ?, ?)}");

// Bind IN parameters
kdbc_bind_int(stmt, 1, 10);
kdbc_bind_int(stmt, 2, 20);

// Register OUT parameter
kdbc_register_out(stmt, 3);

// Execute
kdbc_call_execute(stmt);

// Retrieve OUT parameter
int64_t result = kdbc_call_get_long(stmt, 3);
printf("Result: %lld\n", (long long)result);

kdbc_stmt_close(stmt);
```

The `{CALL ...}` braces are stripped internally, and the syntax is translated appropriately
for each database.

## Result Set Navigation

### Iterating Rows

`kdbc_next` returns:

- `1` -- a row is available
- `0` -- no more rows
- `KDBC_ERROR` -- an error occurred

```c
kdbc_result *rs = kdbc_execute_query(conn, "SELECT id, name FROM users");
if (!rs) {
    fprintf(stderr, "Query failed: %s\n", kdbc_error(conn));
    return;
}

while (kdbc_next(rs) == 1) {
    int64_t id = kdbc_get_long(rs, 1);
    const char *name = kdbc_get_string(rs, 2);
    printf("id=%lld name=%s\n", (long long)id, name);
}
kdbc_result_close(rs);
```

### Handling NULLs

After calling a `kdbc_get_*` function, check `kdbc_is_null` to distinguish NULL from
zero/empty-string:

```c
int64_t age = kdbc_get_long(rs, 3);
if (kdbc_is_null(rs, 3)) {
    printf("age is NULL\n");
} else {
    printf("age=%lld\n", (long long)age);
}
```

### Column Metadata

```c
int cols = kdbc_col_count(rs);
for (int i = 1; i <= cols; i++) {
    printf("Column %d: name=%s label=%s\n",
           i, kdbc_col_name(rs, i), kdbc_col_label(rs, i));
}
```

!!! tip "String and blob pointer lifetime"
    Pointers returned by `kdbc_get_string` and `kdbc_get_blob` are valid until the next
    `kdbc_next()` call or `kdbc_result_close()`. Copy the data if you need it beyond that.

### Date/Time Retrieval

```c
int year, month, day, hour, minute, second, usec;

if (kdbc_get_timestamp(rs, 4, &year, &month, &day,
                        &hour, &minute, &second, &usec) == KDBC_OK) {
    printf("%04d-%02d-%02d %02d:%02d:%02d.%06d\n",
           year, month, day, hour, minute, second, usec);
}
```

Separate `kdbc_get_date` and `kdbc_get_time` functions are available for date-only and
time-only columns.

## Thread Safety

KDBC follows a simple threading model:

- **One thread per connection**. Do not use the same `kdbc_conn` from multiple threads
  simultaneously.
- Separate connections can be used from separate threads without any synchronization.
- `kdbc_cancel` is the **one exception** -- it is explicitly designed to be called from a
  different thread than the one using the connection.

## Async Cancellation

`kdbc_cancel` interrupts a long-running query from another thread:

```c
#include <pthread.h>

// Thread function that cancels after a timeout
void *cancel_thread(void *arg) {
    kdbc_conn *conn = (kdbc_conn *)arg;
    sleep(5);  // Wait 5 seconds
    kdbc_cancel(conn);
    return NULL;
}

// Main thread
kdbc_conn *conn = kdbc_connect(KDBC_POSTGRES, "localhost:5432/mydb", "u", "p");

pthread_t tid;
pthread_create(&tid, NULL, cancel_thread, conn);

// This query will be cancelled after 5 seconds
kdbc_result *rs = kdbc_execute_query(conn, "SELECT pg_sleep(3600)");
if (!rs) {
    printf("Query cancelled: %s\n", kdbc_error(conn));
}

pthread_join(tid, NULL);
kdbc_close(conn);
```

Each driver uses its native cancellation primitive:

| Driver | Cancellation Function |
|--------|----------------------|
| SQLite | `sqlite3_interrupt` |
| PostgreSQL | `PQcancel` |
| MariaDB | `mariadb_cancel` |
| Oracle | `dpiConn_breakExecution` |
| MSSQL | `dbcancel` |

!!! warning "Cancel vs Close"
    Do not call `kdbc_cancel` concurrently with `kdbc_close` on the same connection.
    Serialize cancellation with connection teardown in your application code.

## Driver Discovery

Check at runtime which databases are available:

```c
for (int i = 0; i < KDBC_DRIVER_COUNT; i++) {
    printf("%s: %s\n",
           kdbc_driver_name(i),
           kdbc_driver_available(i) ? "available" : "not found");
}
```

This lets you build adaptive applications that work with whatever databases are installed.

## Database Metadata

After connecting, you can query database metadata:

```c
printf("Product: %s %s\n",
       kdbc_product_name(conn),
       kdbc_product_version(conn));
printf("Version: %d.%d\n",
       kdbc_major_version(conn),
       kdbc_minor_version(conn));
```
