/**
 * @file kdbc.h
 * @brief KDBC Native — Unified C Database Connectivity
 *
 * A lightweight C library that provides a common API for multiple database
 * backends (SQLite, PostgreSQL, MariaDB/MySQL, Oracle, MSSQL).
 * Libraries are loaded at runtime via dlopen, so missing backends are
 * handled gracefully — only the drivers you actually use need to be installed.
 *
 * Key design points:
 * - **Opaque handles**: all state is behind `kdbc_conn`, `kdbc_stmt`, `kdbc_result`
 * - **Runtime driver loading**: native client libraries are loaded via `dlopen`/`LoadLibrary`
 *   at first use — no link-time dependency on any database client
 * - **JDBC-style placeholders**: use `?` in SQL — automatically translated to
 *   `$1`/`$2` (PostgreSQL), `:1`/`:2` (Oracle), or kept as `?` (SQLite, MariaDB, MSSQL)
 * - **1-indexed**: all column and parameter indices start at 1
 *
 * @copyright (C) Panayotis Katsaloulis
 * SPDX-License-Identifier: Apache-2.0
 */
#ifndef KDBC_H
#define KDBC_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* ========================================================================
 * Types and Constants
 * ======================================================================== */

/** @defgroup types Types and Constants
 *  Core types, enumerations, and return codes.
 *  @{
 */

/** @brief Opaque database connection handle. */
typedef struct kdbc_conn   kdbc_conn;

/** @brief Opaque prepared statement handle. */
typedef struct kdbc_stmt   kdbc_stmt;

/** @brief Opaque result set handle. */
typedef struct kdbc_result kdbc_result;

/**
 * @brief Supported database drivers.
 *
 * Each value corresponds to a backend that KDBC can load at runtime.
 * Use kdbc_driver_available() to check whether the native client library
 * for a given driver is installed on the current system.
 */
typedef enum {
    KDBC_SQLITE   = 0,  /**< SQLite (file-based or `:memory:`) */
    KDBC_POSTGRES = 1,  /**< PostgreSQL (via libpq) */
    KDBC_MARIADB  = 2,  /**< MariaDB / MySQL (via libmariadb) */
    KDBC_ORACLE   = 3,  /**< Oracle (via ODPI-C / OCI) */
    KDBC_MSSQL    = 4,  /**< Microsoft SQL Server (via FreeTDS ct-lib) */
    KDBC_DRIVER_COUNT = 5  /**< Number of built-in drivers (not a valid driver value). */
} kdbc_driver;

/**
 * @brief Column / parameter value types.
 *
 * Returned by type-inspection APIs and used internally for parameter binding.
 */
typedef enum {
    KDBC_TYPE_NULL    = 0,  /**< SQL NULL */
    KDBC_TYPE_INT     = 1,  /**< 32-bit integer */
    KDBC_TYPE_LONG    = 2,  /**< 64-bit integer */
    KDBC_TYPE_DOUBLE  = 3,  /**< Double-precision float */
    KDBC_TYPE_STRING  = 4,  /**< UTF-8 string */
    KDBC_TYPE_BLOB    = 5,  /**< Binary large object */
    KDBC_TYPE_BOOL    = 6   /**< Boolean */
} kdbc_type;

/**
 * @brief Generated-key retrieval strategies.
 *
 * Different databases use different mechanisms to return auto-generated
 * primary keys after an INSERT.
 *
 * | Strategy       | Databases                          | Mechanism                  |
 * |----------------|------------------------------------|----------------------------|
 * | `KDBC_GK_NONE`     | Oracle 11g                         | Uses sequences explicitly  |
 * | `KDBC_GK_BY_INDEX` | SQLite, MariaDB/MySQL, MSSQL 2012+ | `last_insert_id` / `SCOPE_IDENTITY` |
 * | `KDBC_GK_BY_NAME`  | PostgreSQL, Oracle 12c+            | `RETURNING` clause         |
 */
typedef enum {
    KDBC_GK_NONE     = 0,  /**< No auto key retrieval (e.g. Oracle 11g — use sequences). */
    KDBC_GK_BY_INDEX = 1,  /**< Key via last_insert_id / SCOPE_IDENTITY (SQLite, MySQL, MSSQL 2012+). */
    KDBC_GK_BY_NAME  = 2   /**< Key via RETURNING clause (PostgreSQL, Oracle 12c+). */
} kdbc_gk_strategy;

/** Returned on success. */
#define KDBC_OK    0
/** Returned on failure — call the appropriate error function for details. */
#define KDBC_ERROR (-1)

/** @} */ /* end of types */

/* ========================================================================
 * Driver Discovery
 * ======================================================================== */

/** @defgroup discovery Driver Discovery
 *  Check which database backends are available at runtime.
 *  @{
 */

/**
 * @brief Check if a driver's native library is available on this system.
 *
 * Triggers a `dlopen` attempt on first call for the given driver.
 * The result is cached — subsequent calls are cheap.
 *
 * @param driver  The driver to check.
 * @return 1 if the native library was found, 0 otherwise.
 */
int kdbc_driver_available(kdbc_driver driver);

/**
 * @brief Get the human-readable name of a driver.
 * @param driver  The driver to query.
 * @return A static string such as `"SQLite"`, `"PostgreSQL"`, etc.
 */
const char *kdbc_driver_name(kdbc_driver driver);

/**
 * @brief Get the default generated-key strategy for a driver.
 *
 * This is the compile-time default. For a connection-specific strategy
 * (which may differ based on server version), use kdbc_conn_gk_strategy().
 *
 * @param driver  The driver to query.
 * @return The default strategy for this driver type.
 */
kdbc_gk_strategy kdbc_driver_gk_strategy(kdbc_driver driver);

/**
 * @brief Get the generated-key strategy for an open connection.
 *
 * May differ from the driver default based on server version.
 * For example, Oracle 12c+ returns ::KDBC_GK_BY_NAME while
 * Oracle 11g returns ::KDBC_GK_NONE.
 *
 * @param conn  An open connection.
 * @return The strategy appropriate for this connection's server version.
 */
kdbc_gk_strategy kdbc_conn_gk_strategy(kdbc_conn *conn);

/**
 * @brief Check if a driver supports `RELEASE SAVEPOINT`.
 *
 * Oracle and MSSQL do **not** support releasing savepoints.
 * Call this before using kdbc_release_savepoint().
 *
 * @param driver  The driver to query.
 * @return 1 if supported, 0 if not.
 */
int kdbc_driver_supports_release_savepoint(kdbc_driver driver);

/** @} */ /* end of discovery */

/* ========================================================================
 * Error Handling
 * ======================================================================== */

/** @defgroup errors Error Handling
 *  Retrieve error messages from connections, statements, or global context.
 *
 *  Each handle (`kdbc_conn`, `kdbc_stmt`) maintains its own error buffer
 *  (1024 bytes). The global error buffer is thread-local, used for errors
 *  that occur outside a connection context (e.g. driver not available,
 *  connection failure).
 *
 *  Returned pointers are valid until the next KDBC call on the same handle,
 *  or until the handle is closed. Returns `""` (empty string) when no error
 *  has occurred.
 *  @{
 */

/**
 * @brief Get the last error message for a connection.
 * @param conn  The connection to query.
 * @return The error message, or `""` if no error.
 */
const char *kdbc_error(kdbc_conn *conn);

/**
 * @brief Get the last error message for a statement.
 * @param stmt  The statement to query.
 * @return The error message, or `""` if no error.
 */
const char *kdbc_stmt_error(kdbc_stmt *stmt);

/**
 * @brief Get the last global (connection-less) error message.
 *
 * Used for errors that occur before a connection exists, such as
 * driver-not-available or connection failure. Thread-local — safe
 * to call from any thread.
 *
 * @return The error message, or `""` if no error.
 */
const char *kdbc_global_error(void);

/** @} */ /* end of errors */

/* ========================================================================
 * Connection Management
 * ======================================================================== */

/** @defgroup connection Connection Management
 *  Open, close, and manage database connections.
 *  @{
 */

/**
 * @brief Open a database connection.
 *
 * The URL format depends on the driver:
 *
 * | Driver             | URL format                  | Example                      |
 * |--------------------|-----------------------------|------------------------------|
 * | ::KDBC_SQLITE      | File path or `:memory:`     | `/tmp/test.db`               |
 * | ::KDBC_POSTGRES    | `host:port/database`        | `localhost:5432/mydb`        |
 * | ::KDBC_MARIADB     | `host:port/database`        | `localhost:3306/mydb`        |
 * | ::KDBC_ORACLE      | `host:port/service_name`    | `localhost:1521/XEPDB1`      |
 * | ::KDBC_MSSQL       | `host:port/database`        | `localhost:1433/mydb`        |
 *
 * @param driver    The database driver to use.
 * @param url       Connection URL (format varies by driver — see table above).
 * @param user      Username (ignored for SQLite — pass NULL).
 * @param password  Password (ignored for SQLite — pass NULL).
 * @return A new connection handle, or NULL on failure.
 *         On failure, call kdbc_global_error() for details.
 */
kdbc_conn *kdbc_connect(kdbc_driver driver, const char *url,
                        const char *user, const char *password);

/**
 * @brief Close a connection and free all associated resources.
 *
 * Safe to call with NULL (no-op).
 *
 * @param conn  The connection to close, or NULL.
 */
void kdbc_close(kdbc_conn *conn);

/**
 * @brief Get the driver type of a connection.
 * @param conn  An open connection.
 * @return The driver enum value.
 */
kdbc_driver kdbc_conn_driver(kdbc_conn *conn);

/**
 * @brief Cancel a running statement from another thread.
 *
 * Best-effort asynchronous cancellation of any statement currently executing
 * on this connection. Designed to be called from a thread **other** than the
 * one blocked inside a KDBC call — this is the mechanism by which higher-level
 * code (e.g. a coroutine cancellation handler) can interrupt a running query.
 *
 * When the cancel takes effect, the blocking call on the other thread will
 * return with `KDBC_ERROR` and a driver-specific error message.
 *
 * Each driver dispatches to the underlying library's async-cancel primitive:
 * - PostgreSQL: `PQcancel`
 * - SQLite: `sqlite3_interrupt`
 * - Oracle: `dpiConn_breakExecution`
 * - MariaDB: `mariadb_cancel`
 * - MSSQL: `ct_cancel`
 *
 * @warning **Thread safety**: safe to call concurrently with a blocking KDBC
 *          call on the same connection. **Not** safe to call concurrently
 *          with kdbc_close() on the same connection.
 *
 * @param conn  The connection whose running statement should be cancelled.
 * @return ::KDBC_OK if the cancel request was dispatched,
 *         ::KDBC_ERROR if conn is NULL, the driver doesn't support cancellation,
 *         or the underlying primitive reported a failure.
 */
int kdbc_cancel(kdbc_conn *conn);

/** @} */ /* end of connection */

/* ========================================================================
 * Direct SQL Execution
 * ======================================================================== */

/** @defgroup direct Direct SQL Execution
 *  Execute SQL without a prepared statement (no parameters).
 *  @{
 */

/**
 * @brief Execute a non-parameterized DML/DDL statement directly.
 *
 * Suitable for DDL (`CREATE TABLE`, `DROP TABLE`) or simple DML without
 * parameters. For parameterized queries, use kdbc_prepare() instead.
 *
 * @param conn  An open connection.
 * @param sql   The SQL statement to execute.
 * @return Number of affected rows (0 for DDL), or ::KDBC_ERROR on failure.
 */
int kdbc_execute_update(kdbc_conn *conn, const char *sql);

/**
 * @brief Execute a non-parameterized SELECT directly.
 *
 * The returned result set owns its resources — close it with
 * kdbc_result_close() when done.
 *
 * @param conn  An open connection.
 * @param sql   The SELECT statement to execute.
 * @return A result set handle, or NULL on failure.
 */
kdbc_result *kdbc_execute_query(kdbc_conn *conn, const char *sql);

/** @} */ /* end of direct */

/* ========================================================================
 * Transaction Control
 * ======================================================================== */

/** @defgroup transactions Transaction Control
 *  Manage transactions, autocommit, and savepoints.
 *
 *  Autocommit is **ON** by default (matching JDBC convention). To use
 *  explicit transactions, disable autocommit first:
 *
 *  @code{.c}
 *  kdbc_set_autocommit(conn, 0);
 *  kdbc_execute_update(conn, "INSERT INTO t VALUES (1)");
 *  kdbc_execute_update(conn, "INSERT INTO t VALUES (2)");
 *  kdbc_commit(conn);
 *  kdbc_set_autocommit(conn, 1);
 *  @endcode
 *
 *  Savepoints allow partial rollback within a transaction:
 *
 *  @code{.c}
 *  kdbc_set_autocommit(conn, 0);
 *  kdbc_execute_update(conn, "INSERT INTO t VALUES (1)");
 *  kdbc_savepoint(conn, "sp1");
 *  kdbc_execute_update(conn, "INSERT INTO t VALUES (2)");
 *  kdbc_rollback_to(conn, "sp1");   // undoes only the second INSERT
 *  kdbc_commit(conn);               // commits the first INSERT
 *  @endcode
 *
 *  @note Oracle and MSSQL do not support `RELEASE SAVEPOINT`.
 *        Check with kdbc_driver_supports_release_savepoint() before calling
 *        kdbc_release_savepoint().
 *  @{
 */

/**
 * @brief Enable or disable autocommit.
 * @param conn     An open connection.
 * @param enabled  1 to enable (default), 0 to disable.
 * @return ::KDBC_OK or ::KDBC_ERROR.
 */
int kdbc_set_autocommit(kdbc_conn *conn, int enabled);

/**
 * @brief Commit the current transaction.
 * @param conn  An open connection.
 * @return ::KDBC_OK or ::KDBC_ERROR.
 */
int kdbc_commit(kdbc_conn *conn);

/**
 * @brief Roll back the current transaction.
 * @param conn  An open connection.
 * @return ::KDBC_OK or ::KDBC_ERROR.
 */
int kdbc_rollback(kdbc_conn *conn);

/**
 * @brief Create a savepoint.
 * @param conn  An open connection (autocommit must be off).
 * @param name  Savepoint name.
 * @return ::KDBC_OK or ::KDBC_ERROR.
 */
int kdbc_savepoint(kdbc_conn *conn, const char *name);

/**
 * @brief Roll back to a savepoint.
 * @param conn  An open connection.
 * @param name  Savepoint name (previously created with kdbc_savepoint()).
 * @return ::KDBC_OK or ::KDBC_ERROR.
 */
int kdbc_rollback_to(kdbc_conn *conn, const char *name);

/**
 * @brief Release a savepoint.
 *
 * @note Not supported on Oracle and MSSQL — check with
 *       kdbc_driver_supports_release_savepoint() first.
 *
 * @param conn  An open connection.
 * @param name  Savepoint name to release.
 * @return ::KDBC_OK or ::KDBC_ERROR.
 */
int kdbc_release_savepoint(kdbc_conn *conn, const char *name);

/** @} */ /* end of transactions */

/* ========================================================================
 * Database Metadata
 * ======================================================================== */

/** @defgroup metadata Database Metadata
 *  Query server product name and version.
 *
 *  Strings are owned by the connection — valid until kdbc_close().
 *  @{
 */

/**
 * @brief Get the database product name (e.g. `"SQLite"`, `"PostgreSQL"`).
 * @param conn  An open connection.
 * @return Product name string.
 */
const char *kdbc_product_name(kdbc_conn *conn);

/**
 * @brief Get the full database version string.
 * @param conn  An open connection.
 * @return Version string (e.g. `"3.39.4"`, `"15.2"`).
 */
const char *kdbc_product_version(kdbc_conn *conn);

/**
 * @brief Get the major version number.
 * @param conn  An open connection.
 * @return Major version (e.g. 15 for PostgreSQL 15.2).
 */
int kdbc_major_version(kdbc_conn *conn);

/**
 * @brief Get the minor version number.
 * @param conn  An open connection.
 * @return Minor version (e.g. 2 for PostgreSQL 15.2).
 */
int kdbc_minor_version(kdbc_conn *conn);

/** @} */ /* end of metadata */

/* ========================================================================
 * Prepared Statements and Parameter Binding
 * ======================================================================== */

/** @defgroup statements Prepared Statements
 *  Prepare SQL, bind parameters, and manage statement lifecycle.
 *
 *  Use `?` as parameter placeholders — KDBC translates them automatically:
 *  - PostgreSQL: `$1`, `$2`, ...
 *  - Oracle: `:1`, `:2`, ...
 *  - SQLite, MariaDB, MSSQL: kept as `?`
 *
 *  All parameter indices are **1-indexed**.
 *
 *  @code{.c}
 *  kdbc_stmt *stmt = kdbc_prepare(conn, "INSERT INTO users (name, age) VALUES (?, ?)");
 *  kdbc_bind_string(stmt, 1, "Alice");
 *  kdbc_bind_int(stmt, 2, 30);
 *  int rows = kdbc_execute_update_stmt(stmt);
 *  kdbc_stmt_close(stmt);
 *  @endcode
 *  @{
 */

/**
 * @brief Prepare a SQL statement.
 *
 * @param conn  An open connection.
 * @param sql   SQL with `?` parameter placeholders.
 * @return A prepared statement handle, or NULL on failure.
 */
kdbc_stmt *kdbc_prepare(kdbc_conn *conn, const char *sql);

/**
 * @brief Prepare a SQL statement that returns generated keys.
 *
 * Used for INSERT statements when you need to retrieve auto-generated
 * primary keys. The mechanism varies by database:
 * - PostgreSQL / Oracle 12c+: appends `RETURNING col1, col2, ...`
 * - MSSQL: uses `OUTPUT INSERTED.col1, ...`
 * - SQLite / MariaDB: uses `last_insert_rowid()` / `LAST_INSERT_ID()`
 *
 * @param conn       An open connection.
 * @param sql        SQL with `?` parameter placeholders.
 * @param col_names  Array of column names to return (may be NULL for index-based drivers).
 * @param n_cols     Number of entries in @p col_names (0 if NULL).
 * @return A prepared statement handle, or NULL on failure.
 *
 * @see kdbc_generated_keys()
 */
kdbc_stmt *kdbc_prepare_returning(kdbc_conn *conn, const char *sql,
                                  const char **col_names, int n_cols);

/**
 * @brief Bind a NULL value to a parameter.
 * @param stmt  A prepared statement.
 * @param idx   Parameter index (1-indexed).
 * @return ::KDBC_OK or ::KDBC_ERROR.
 */
int kdbc_bind_null(kdbc_stmt *stmt, int idx);

/**
 * @brief Bind a 32-bit integer parameter.
 * @param stmt  A prepared statement.
 * @param idx   Parameter index (1-indexed).
 * @param val   The integer value.
 * @return ::KDBC_OK or ::KDBC_ERROR.
 */
int kdbc_bind_int(kdbc_stmt *stmt, int idx, int val);

/**
 * @brief Bind a 64-bit integer parameter.
 * @param stmt  A prepared statement.
 * @param idx   Parameter index (1-indexed).
 * @param val   The int64 value.
 * @return ::KDBC_OK or ::KDBC_ERROR.
 */
int kdbc_bind_long(kdbc_stmt *stmt, int idx, int64_t val);

/**
 * @brief Bind a double-precision float parameter.
 * @param stmt  A prepared statement.
 * @param idx   Parameter index (1-indexed).
 * @param val   The double value.
 * @return ::KDBC_OK or ::KDBC_ERROR.
 */
int kdbc_bind_double(kdbc_stmt *stmt, int idx, double val);

/**
 * @brief Bind a UTF-8 string parameter.
 *
 * The string is copied internally — the caller may free @p val immediately.
 *
 * @param stmt  A prepared statement.
 * @param idx   Parameter index (1-indexed).
 * @param val   The null-terminated UTF-8 string.
 * @return ::KDBC_OK or ::KDBC_ERROR.
 */
int kdbc_bind_string(kdbc_stmt *stmt, int idx, const char *val);

/**
 * @brief Bind a binary blob parameter.
 *
 * The data is copied internally — the caller may free @p data immediately.
 *
 * @param stmt  A prepared statement.
 * @param idx   Parameter index (1-indexed).
 * @param data  Pointer to the binary data.
 * @param len   Length of @p data in bytes.
 * @return ::KDBC_OK or ::KDBC_ERROR.
 */
int kdbc_bind_blob(kdbc_stmt *stmt, int idx, const void *data, size_t len);

/**
 * @brief Bind a timestamp (date + time) parameter.
 *
 * No timezone — treated as local time. Each driver converts to its native
 * temporal format internally.
 *
 * @param stmt    A prepared statement.
 * @param idx     Parameter index (1-indexed).
 * @param year    Year (e.g. 2024).
 * @param month   Month (1–12).
 * @param day     Day (1–31).
 * @param hour    Hour (0–23).
 * @param minute  Minute (0–59).
 * @param second  Second (0–59).
 * @param usec    Microseconds (0–999999).
 * @return ::KDBC_OK or ::KDBC_ERROR.
 */
int kdbc_bind_timestamp(kdbc_stmt *stmt, int idx,
                        int year, int month, int day,
                        int hour, int minute, int second, int usec);

/**
 * @brief Bind a date-only parameter.
 * @param stmt   A prepared statement.
 * @param idx    Parameter index (1-indexed).
 * @param year   Year.
 * @param month  Month (1–12).
 * @param day    Day (1–31).
 * @return ::KDBC_OK or ::KDBC_ERROR.
 */
int kdbc_bind_date(kdbc_stmt *stmt, int idx,
                   int year, int month, int day);

/**
 * @brief Bind a time-only parameter.
 * @param stmt    A prepared statement.
 * @param idx     Parameter index (1-indexed).
 * @param hour    Hour (0–23).
 * @param minute  Minute (0–59).
 * @param second  Second (0–59).
 * @param usec    Microseconds (0–999999).
 * @return ::KDBC_OK or ::KDBC_ERROR.
 */
int kdbc_bind_time(kdbc_stmt *stmt, int idx,
                   int hour, int minute, int second, int usec);

/** @} */ /* end of statements */

/* ========================================================================
 * Statement Execution
 * ======================================================================== */

/** @defgroup execution Statement Execution
 *  Execute prepared statements and retrieve generated keys.
 *  @{
 */

/**
 * @brief Execute a prepared DML statement (INSERT/UPDATE/DELETE).
 * @param stmt  A prepared statement with all parameters bound.
 * @return Number of affected rows, or ::KDBC_ERROR on failure.
 */
int kdbc_execute_update_stmt(kdbc_stmt *stmt);

/**
 * @brief Execute a prepared query (SELECT).
 *
 * The returned result set must be closed with kdbc_result_close().
 *
 * @param stmt  A prepared statement with all parameters bound.
 * @return A result set handle, or NULL on failure.
 */
kdbc_result *kdbc_execute_query_stmt(kdbc_stmt *stmt);

/**
 * @brief Get generated keys after an INSERT.
 *
 * Returns a synthetic result set with one row containing the generated key(s).
 * The statement must have been prepared with kdbc_prepare_returning().
 *
 * @code{.c}
 * kdbc_stmt *stmt = kdbc_prepare_returning(conn,
 *     "INSERT INTO users (name) VALUES (?)", (const char*[]){"id"}, 1);
 * kdbc_bind_string(stmt, 1, "Bob");
 * kdbc_execute_update_stmt(stmt);
 *
 * kdbc_result *keys = kdbc_generated_keys(stmt);
 * if (keys && kdbc_next(keys))
 *     printf("Generated ID: %lld\n", (long long)kdbc_get_long(keys, 1));
 * kdbc_result_close(keys);
 * kdbc_stmt_close(stmt);
 * @endcode
 *
 * @param stmt  A prepared statement after execution.
 * @return A result set with generated keys, or NULL if none.
 */
kdbc_result *kdbc_generated_keys(kdbc_stmt *stmt);

/**
 * @brief Close a prepared statement and free resources.
 *
 * Safe to call with NULL (no-op).
 *
 * @param stmt  The statement to close, or NULL.
 */
void kdbc_stmt_close(kdbc_stmt *stmt);

/**
 * @brief Reset a prepared statement for re-execution.
 *
 * Clears all bound parameters so the statement can be reused with
 * new values.
 *
 * @param stmt  A prepared statement.
 * @return ::KDBC_OK or ::KDBC_ERROR.
 */
int kdbc_stmt_reset(kdbc_stmt *stmt);

/**
 * @brief Set a fetch size hint for query execution.
 *
 * Suggests how many rows the driver should fetch per round-trip.
 * This is a hint — drivers may ignore it.
 *
 * @param stmt  A prepared statement.
 * @param rows  Number of rows to fetch at a time (0 = driver default).
 * @return ::KDBC_OK or ::KDBC_ERROR.
 */
int kdbc_set_fetch_size(kdbc_stmt *stmt, int rows);

/** @} */ /* end of execution */

/* ========================================================================
 * Batch Execution
 * ======================================================================== */

/** @defgroup batch Batch Execution
 *  Execute multiple parameter sets in a single statement.
 *
 *  @code{.c}
 *  kdbc_stmt *stmt = kdbc_prepare(conn, "INSERT INTO t (val) VALUES (?)");
 *  for (int i = 0; i < 100; i++) {
 *      kdbc_bind_int(stmt, 1, i);
 *      kdbc_add_batch(stmt);
 *  }
 *  int total = kdbc_execute_batch(stmt);
 *  kdbc_stmt_close(stmt);
 *  @endcode
 *  @{
 */

/**
 * @brief Add current parameters as a batch entry.
 *
 * After calling this, bind new parameter values and call again for the
 * next row. Finally call kdbc_execute_batch() to execute all rows.
 *
 * @param stmt  A prepared statement with parameters bound.
 * @return ::KDBC_OK or ::KDBC_ERROR.
 */
int kdbc_add_batch(kdbc_stmt *stmt);

/**
 * @brief Execute all batched parameter sets.
 * @param stmt  A prepared statement with batched entries.
 * @return Total number of affected rows, or ::KDBC_ERROR on failure.
 */
int kdbc_execute_batch(kdbc_stmt *stmt);

/** @} */ /* end of batch */

/* ========================================================================
 * Callable Statements (Stored Procedures)
 * ======================================================================== */

/** @defgroup callable Callable Statements
 *  Execute stored procedures with IN and OUT parameters.
 *
 *  Uses JDBC-style call syntax: `"{CALL proc_name(?, ?)}"`.
 *  The library strips the braces and translates for each database.
 *
 *  @code{.c}
 *  kdbc_stmt *stmt = kdbc_prepare_call(conn, "{CALL get_count(?, ?)}");
 *  kdbc_bind_string(stmt, 1, "active");
 *  kdbc_register_out(stmt, 2);
 *  kdbc_call_execute(stmt);
 *  int64_t count = kdbc_call_get_long(stmt, 2);
 *  kdbc_stmt_close(stmt);
 *  @endcode
 *  @{
 */

/**
 * @brief Prepare a stored procedure call.
 *
 * @param conn  An open connection.
 * @param sql   JDBC-style call syntax, e.g. `"{CALL my_proc(?, ?)}"`.
 * @return A callable statement handle, or NULL on failure.
 */
kdbc_stmt *kdbc_prepare_call(kdbc_conn *conn, const char *sql);

/**
 * @brief Register a parameter as OUT.
 *
 * Must be called before kdbc_call_execute().
 *
 * @param stmt  A callable statement.
 * @param idx   Parameter index (1-indexed).
 * @return ::KDBC_OK or ::KDBC_ERROR.
 */
int kdbc_register_out(kdbc_stmt *stmt, int idx);

/**
 * @brief Execute a callable statement.
 * @param stmt  A callable statement with all parameters bound/registered.
 * @return 1 if the call produced a result set, 0 if not, ::KDBC_ERROR on failure.
 */
int kdbc_call_execute(kdbc_stmt *stmt);

/**
 * @brief Retrieve an OUT parameter as a 64-bit integer.
 * @param stmt  A callable statement after execution.
 * @param idx   OUT parameter index (1-indexed).
 * @return The integer value.
 */
int64_t kdbc_call_get_long(kdbc_stmt *stmt, int idx);

/**
 * @brief Retrieve an OUT parameter as a string.
 * @param stmt  A callable statement after execution.
 * @param idx   OUT parameter index (1-indexed).
 * @return The string value (valid until kdbc_stmt_close()).
 */
const char *kdbc_call_get_string(kdbc_stmt *stmt, int idx);

/** @} */ /* end of callable */

/* ========================================================================
 * Result Set
 * ======================================================================== */

/** @defgroup resultset Result Set
 *  Navigate rows and retrieve column values from query results.
 *
 *  All column indices are **1-indexed**. String and blob pointers are valid
 *  until the next kdbc_next() call or kdbc_result_close().
 *
 *  @code{.c}
 *  kdbc_result *rs = kdbc_execute_query(conn, "SELECT id, name FROM users");
 *  while (kdbc_next(rs) == 1) {
 *      int64_t id = kdbc_get_long(rs, 1);
 *      const char *name = kdbc_get_string(rs, 2);
 *      if (!kdbc_is_null(rs, 2))
 *          printf("%lld: %s\n", (long long)id, name);
 *  }
 *  kdbc_result_close(rs);
 *  @endcode
 *  @{
 */

/**
 * @brief Advance to the next row.
 * @param rs  A result set.
 * @return 1 if a row is available, 0 if no more rows, ::KDBC_ERROR on failure.
 */
int kdbc_next(kdbc_result *rs);

/**
 * @brief Get the number of columns in the result set.
 * @param rs  A result set.
 * @return Column count.
 */
int kdbc_col_count(kdbc_result *rs);

/**
 * @brief Get a column name by index.
 * @param rs   A result set.
 * @param col  Column index (1-indexed).
 * @return Column name, or NULL if index is invalid.
 */
const char *kdbc_col_name(kdbc_result *rs, int col);

/**
 * @brief Get a column label (alias) by index.
 *
 * Falls back to the column name if no alias was specified in the query.
 *
 * @param rs   A result set.
 * @param col  Column index (1-indexed).
 * @return Column label, or NULL if index is invalid.
 */
const char *kdbc_col_label(kdbc_result *rs, int col);

/**
 * @brief Check if a column value is NULL.
 *
 * Call this after a `kdbc_get_*` function to distinguish NULL from
 * zero or empty string.
 *
 * @param rs   A result set (positioned on a row).
 * @param col  Column index (1-indexed).
 * @return 1 if the value is NULL, 0 otherwise.
 */
int kdbc_is_null(kdbc_result *rs, int col);

/**
 * @brief Get a column value as a 64-bit integer.
 *
 * For type mismatches, reasonable conversions are attempted.
 * Check kdbc_is_null() to distinguish NULL from 0.
 *
 * @param rs   A result set (positioned on a row).
 * @param col  Column index (1-indexed).
 * @return The integer value (0 if NULL or conversion failed).
 */
int64_t kdbc_get_long(kdbc_result *rs, int col);

/**
 * @brief Get a column value as a double.
 *
 * Check kdbc_is_null() to distinguish NULL from 0.0.
 *
 * @param rs   A result set (positioned on a row).
 * @param col  Column index (1-indexed).
 * @return The double value (0.0 if NULL or conversion failed).
 */
double kdbc_get_double(kdbc_result *rs, int col);

/**
 * @brief Get a column value as a UTF-8 string.
 *
 * The returned pointer is valid until the next kdbc_next() call
 * or kdbc_result_close().
 *
 * @param rs   A result set (positioned on a row).
 * @param col  Column index (1-indexed).
 * @return The string value, or NULL if the column is NULL.
 */
const char *kdbc_get_string(kdbc_result *rs, int col);

/**
 * @brief Get a column value as a binary blob.
 *
 * The returned pointer is valid until the next kdbc_next() call
 * or kdbc_result_close().
 *
 * @param rs       A result set (positioned on a row).
 * @param col      Column index (1-indexed).
 * @param out_len  Output: blob size in bytes.
 * @return Pointer to the blob data, or NULL if the column is NULL.
 */
const void *kdbc_get_blob(kdbc_result *rs, int col, size_t *out_len);

/**
 * @brief Get a column value as a timestamp (date + time).
 *
 * @param rs       A result set (positioned on a row).
 * @param col      Column index (1-indexed).
 * @param year     Output: year.
 * @param month    Output: month (1–12).
 * @param day      Output: day (1–31).
 * @param hour     Output: hour (0–23).
 * @param minute   Output: minute (0–59).
 * @param second   Output: second (0–59).
 * @param usec     Output: microseconds (0–999999).
 * @return ::KDBC_OK on success, ::KDBC_ERROR if NULL or conversion failed.
 */
int kdbc_get_timestamp(kdbc_result *rs, int col,
                       int *year, int *month, int *day,
                       int *hour, int *minute, int *second, int *usec);

/**
 * @brief Get a column value as a date (no time component).
 * @param rs     A result set (positioned on a row).
 * @param col    Column index (1-indexed).
 * @param year   Output: year.
 * @param month  Output: month (1–12).
 * @param day    Output: day (1–31).
 * @return ::KDBC_OK on success, ::KDBC_ERROR if NULL or conversion failed.
 */
int kdbc_get_date(kdbc_result *rs, int col,
                  int *year, int *month, int *day);

/**
 * @brief Get a column value as a time (no date component).
 * @param rs       A result set (positioned on a row).
 * @param col      Column index (1-indexed).
 * @param hour     Output: hour (0–23).
 * @param minute   Output: minute (0–59).
 * @param second   Output: second (0–59).
 * @param usec     Output: microseconds (0–999999).
 * @return ::KDBC_OK on success, ::KDBC_ERROR if NULL or conversion failed.
 */
int kdbc_get_time(kdbc_result *rs, int col,
                  int *hour, int *minute, int *second, int *usec);

/**
 * @brief Close a result set and free resources.
 *
 * Safe to call with NULL (no-op).
 *
 * @param rs  The result set to close, or NULL.
 */
void kdbc_result_close(kdbc_result *rs);

/** @} */ /* end of resultset */

#ifdef __cplusplus
}
#endif

#endif /* KDBC_H */
