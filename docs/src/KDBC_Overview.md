# KDBC Overview

KDBC (Kotlin Database Connectivity) is a unified database connectivity layer that works across JVM, Linux
(native), Android, iOS, and macOS. Think of it as "JDBC, but cross-platform" -- it provides the same
familiar interfaces (DataSource, Connection, PreparedStatement, ResultSet) on every platform Stormify
supports.

## Two Faces of KDBC

KDBC has two layers that can be used independently:

**1. C Library (`libkdbc`)** -- a standalone C11 library for native/embedded use. It provides a single API
that talks to SQLite, PostgreSQL, MariaDB/MySQL, Oracle, and MSSQL, loading driver libraries at runtime
via `dlopen`. You can use it from any C or C++ project without Kotlin.

**2. Kotlin Multiplatform Wrapper** -- a set of common interfaces (`DataSource`, `Connection`,
`PreparedStatement`, `ResultSet`, etc.) with platform-specific implementations:

- **JVM**: wraps `java.sql.*` (zero overhead delegation to JDBC)
- **Native (Linux)**: wraps the C library above via Kotlin/Native cinterop
- **Android**: wraps `android.database.sqlite.SQLiteDatabase`
- **iOS / macOS**: wraps SQLite via cinterop (same C driver as Linux)

## Architecture

### Opaque Handles

The C library uses three opaque handle types:

| Handle | Represents | Lifecycle |
|--------|-----------|-----------|
| `kdbc_conn` | A database connection | `kdbc_connect` ... `kdbc_close` |
| `kdbc_stmt` | A prepared statement | `kdbc_prepare` ... `kdbc_stmt_close` |
| `kdbc_result` | A result set (rows) | `kdbc_execute_query` ... `kdbc_result_close` |

### Driver Virtual Table

Each database backend implements a driver vtable. The core dispatches calls to the active driver through
function pointers -- the application code never interacts with driver internals directly.

### Runtime Library Loading

KDBC loads database client libraries at runtime using `dlopen` (Linux/macOS) or `LoadLibrary` (Windows).
This means:

- You compile and link against `libkdbc` only -- no database client libraries needed at link time.
- Missing drivers are handled gracefully: `kdbc_driver_available()` returns 0 if the native library
  is not installed, and `kdbc_connect()` returns NULL with a descriptive error.
- You can ship a single binary that works with whichever databases the user has installed.

## Supported Databases

| Database | Driver Constant | Native Library | Platforms |
|----------|----------------|----------------|-----------|
| SQLite | `KDBC_SQLITE` | libsqlite3 | Linux, macOS, iOS, Android |
| PostgreSQL | `KDBC_POSTGRES` | libpq | Linux |
| MariaDB / MySQL | `KDBC_MARIADB` | libmariadb | Linux |
| Oracle | `KDBC_ORACLE` | ODPI-C | Linux |
| MS SQL Server | `KDBC_MSSQL` | FreeTDS (libsybdb) | Linux |

## Key Design Decisions

### JDBC-Style Placeholders

All SQL uses `?` as parameter placeholders, regardless of the backend. KDBC automatically translates
to the native placeholder syntax:

| Database | Native Syntax | KDBC Input |
|----------|--------------|------------|
| SQLite, MariaDB, MSSQL | `?` | `?` (no change) |
| PostgreSQL | `$1`, `$2`, ... | `?` |
| Oracle | `:1`, `:2`, ... | `?` |

### 1-Indexed Parameters and Columns

All parameter indices and column indices are 1-based (matching JDBC convention), not 0-based.

### Per-Handle Error Buffers

Each connection and statement maintains its own error buffer. There is also a thread-local global error
for cases where no handle exists yet (e.g., connection failures). Error messages from the underlying
database pass through verbatim (e.g., you'll see `ORA-00942: table or view does not exist` from Oracle).

### Generated Key Strategies

Different databases retrieve auto-generated keys differently. KDBC abstracts this with three strategies:

| Strategy | Databases | Mechanism |
|----------|----------|-----------|
| `KDBC_GK_BY_INDEX` | SQLite, MySQL, MSSQL 2012+ | `last_insert_id()` / `SCOPE_IDENTITY()` |
| `KDBC_GK_BY_NAME` | PostgreSQL, Oracle 12c+ | `RETURNING` clause |
| `KDBC_GK_NONE` | Oracle 11g | Uses sequences (no auto-key) |

## Platform Summary

| Platform | Databases | Implementation | Status |
|----------|----------|----------------|--------|
| **JVM** | Any JDBC-compatible | Wraps `java.sql.*` | Tested |
| **Linux (linuxX64)** | All 5 (SQLite, PG, MariaDB, Oracle, MSSQL) | C library via cinterop | Tested |
| **Android** | SQLite | Wraps `android.database.sqlite.*` | Tested |
| **iOS** | SQLite | C library via cinterop | Untested |
| **macOS** | SQLite | C library via cinterop | Untested |

!!! note "Native platform testing"
    The native C library and Kotlin/Native bindings are tested on **Linux** and **Android** (via Robolectric).
    iOS and macOS targets compile but have not been tested in production.

## What's Next

- [KDBC C Guide](KDBC_C_Guide.md) -- building and using the C library directly
- [KDBC Kotlin Guide](KDBC_Kotlin_Guide.md) -- using KDBC from Kotlin Multiplatform
- [C API Reference](kdbc-c/html/index.html) -- auto-generated Doxygen documentation
