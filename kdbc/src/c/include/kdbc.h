/*
 * KDBC Native - Unified Database Connectivity for Kotlin/Native
 *
 * A lightweight C library that provides a common API for multiple database
 * backends (SQLite, PostgreSQL, MariaDB/MySQL, Oracle, FreeTDS/MSSQL).
 * Libraries are loaded at runtime via dlopen, so missing backends are
 * handled gracefully - only the drivers you actually use need to be installed.
 *
 * SPDX-License-Identifier: Apache-2.0
 * (C) Panayotis Katsaloulis
 */
#ifndef KDBC_H
#define KDBC_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Opaque handle types */
typedef struct kdbc_conn   kdbc_conn;
typedef struct kdbc_stmt   kdbc_stmt;
typedef struct kdbc_result kdbc_result;

/* Supported database drivers */
typedef enum {
    KDBC_SQLITE   = 0,
    KDBC_POSTGRES = 1,
    KDBC_MARIADB  = 2,
    KDBC_ORACLE   = 3,
    KDBC_FREETDS  = 4,
    KDBC_DRIVER_COUNT = 5
} kdbc_driver;

/* Column/parameter types returned by kdbc_col_type */
typedef enum {
    KDBC_TYPE_NULL    = 0,
    KDBC_TYPE_INT     = 1,
    KDBC_TYPE_LONG    = 2,
    KDBC_TYPE_DOUBLE  = 3,
    KDBC_TYPE_STRING  = 4,
    KDBC_TYPE_BLOB    = 5,
    KDBC_TYPE_BOOL    = 6
} kdbc_type;

/* Generated key retrieval strategies (matches Stormify's SqlDialect) */
typedef enum {
    KDBC_GK_NONE     = 0,  /* No auto key retrieval (e.g. Oracle 11g, uses sequences) */
    KDBC_GK_BY_INDEX = 1,  /* Key via last_insert_id / SCOPE_IDENTITY (SQLite, MySQL, MSSQL 2012+) */
    KDBC_GK_BY_NAME  = 2   /* Key via RETURNING clause (PostgreSQL, Oracle 12c+) */
} kdbc_gk_strategy;

/* Return codes */
#define KDBC_OK    0
#define KDBC_ERROR (-1)

/* ========================================================================
 * Driver discovery
 * ======================================================================== */

/**
 * Check if a database driver's native library is available on this system.
 * Returns 1 if available, 0 if not. Safe to call at any time.
 */
int kdbc_driver_available(kdbc_driver driver);

/**
 * Get the human-readable name of a driver (e.g. "SQLite", "PostgreSQL").
 */
const char *kdbc_driver_name(kdbc_driver driver);

/**
 * Get the generated key retrieval strategy for a driver.
 * Used by the Kotlin layer to decide how to prepare INSERT statements.
 */
kdbc_gk_strategy kdbc_driver_gk_strategy(kdbc_driver driver);

/**
 * Get the generated key strategy for an open connection.
 * May differ from the driver default based on server version.
 * E.g., Oracle 12c+ returns BY_NAME, Oracle 11g returns NONE.
 */
kdbc_gk_strategy kdbc_conn_gk_strategy(kdbc_conn *conn);

/**
 * Check if a driver supports releasing savepoints.
 * Oracle and MSSQL do not support RELEASE SAVEPOINT.
 */
int kdbc_driver_supports_release_savepoint(kdbc_driver driver);

/* ========================================================================
 * Error handling
 * ======================================================================== */

/**
 * Get the last error message. Returns "" if no error.
 * The returned pointer is valid until the next KDBC call on the same
 * connection/statement, or until kdbc_close/kdbc_stmt_close.
 * For connection-level errors (including connect failures), pass the conn.
 * For global errors (e.g. driver not available), use kdbc_global_error().
 */
const char *kdbc_error(kdbc_conn *conn);
const char *kdbc_stmt_error(kdbc_stmt *stmt);
const char *kdbc_global_error(void);

/* ========================================================================
 * Connection management
 * ======================================================================== */

/**
 * Open a database connection.
 *
 * For SQLite:    url = file path (e.g. "/tmp/test.db" or ":memory:")
 * For Postgres:  url = "host:port/database"
 * For MariaDB:   url = "host:port/database"
 * For Oracle:    url = "host:port/service_name"
 * For FreeTDS:   url = "host:port/database"
 *
 * Returns NULL on failure. Call kdbc_global_error() for details.
 */
kdbc_conn *kdbc_connect(kdbc_driver driver, const char *url,
                        const char *user, const char *password);

/**
 * Close a connection and free all associated resources.
 * Safe to call with NULL.
 */
void kdbc_close(kdbc_conn *conn);

/**
 * Get the driver type of a connection.
 */
kdbc_driver kdbc_conn_driver(kdbc_conn *conn);

/**
 * Best-effort asynchronous cancellation of any statement currently executing
 * on this connection. Designed to be called from a thread OTHER than the one
 * blocked inside a kdbc call — this is the mechanism by which higher-level
 * code (e.g. a Kotlin coroutine cancellation handler) can interrupt a running
 * query without having to wait for it to finish naturally.
 *
 * When the cancel takes effect, the blocking call on the other thread will
 * return with an error (typically KDBC_ERROR and a driver-specific error
 * message). Cancel is a request, not a guarantee — the query may still
 * complete normally if it was already near completion.
 *
 * Thread safety: this function is safe to call concurrently with a blocking
 * kdbc call on the same connection, because each driver dispatches to the
 * underlying library's documented async-cancel primitive (libpq PQcancel,
 * sqlite3_interrupt, dpiConn_breakExecution, mariadb_cancel). However, it is
 * NOT safe to call concurrently with kdbc_close on the same connection — the
 * caller must serialize cancellation with connection teardown.
 *
 * Returns KDBC_OK if the cancel request was dispatched, KDBC_ERROR if the
 * connection is NULL, the driver does not support cancellation, or the
 * underlying primitive reported a failure.
 */
int kdbc_cancel(kdbc_conn *conn);

/* ========================================================================
 * Direct SQL execution (no prepare, no parameters)
 * ======================================================================== */

/**
 * Execute a non-parameterized DML/DDL statement directly (no prepare step).
 * Returns affected rows (0 for DDL), or KDBC_ERROR on failure.
 */
int kdbc_execute_update(kdbc_conn *conn, const char *sql);

/**
 * Execute a non-parameterized SELECT directly (no prepare step).
 * Returns a result set handle, or NULL on failure.
 * The result set owns its resources — kdbc_result_close frees everything.
 */
kdbc_result *kdbc_execute_query(kdbc_conn *conn, const char *sql);

/* ========================================================================
 * Transaction control
 * ======================================================================== */

int kdbc_set_autocommit(kdbc_conn *conn, int enabled);
int kdbc_commit(kdbc_conn *conn);
int kdbc_rollback(kdbc_conn *conn);
int kdbc_savepoint(kdbc_conn *conn, const char *name);
int kdbc_rollback_to(kdbc_conn *conn, const char *name);
int kdbc_release_savepoint(kdbc_conn *conn, const char *name);

/* ========================================================================
 * Database metadata
 * ======================================================================== */

const char *kdbc_product_name(kdbc_conn *conn);
const char *kdbc_product_version(kdbc_conn *conn);
int         kdbc_major_version(kdbc_conn *conn);
int         kdbc_minor_version(kdbc_conn *conn);

/* ========================================================================
 * Statement preparation and parameter binding
 * ======================================================================== */

/**
 * Prepare a SQL statement. Uses ? as parameter placeholders (JDBC-style).
 * The library translates to $1/$2 for PostgreSQL, :1/:2 for Oracle, etc.
 *
 * If col_names is non-NULL and n_cols > 0, the statement is prepared for
 * returning generated keys for the specified columns (used for INSERT with
 * RETURNING on PostgreSQL, or OUTPUT on MSSQL).
 *
 * Returns NULL on failure.
 */
kdbc_stmt *kdbc_prepare(kdbc_conn *conn, const char *sql);
kdbc_stmt *kdbc_prepare_returning(kdbc_conn *conn, const char *sql,
                                  const char **col_names, int n_cols);

/* Parameter binding - all 1-indexed */
int kdbc_bind_null(kdbc_stmt *stmt, int idx);
int kdbc_bind_int(kdbc_stmt *stmt, int idx, int val);
int kdbc_bind_long(kdbc_stmt *stmt, int idx, int64_t val);
int kdbc_bind_double(kdbc_stmt *stmt, int idx, double val);
int kdbc_bind_string(kdbc_stmt *stmt, int idx, const char *val);
int kdbc_bind_blob(kdbc_stmt *stmt, int idx, const void *data, size_t len);

/**
 * Date/time parameter binding.
 * Timestamp = date + time (no timezone - treated as local).
 * Each driver converts to its native format internally.
 */
int kdbc_bind_timestamp(kdbc_stmt *stmt, int idx,
                        int year, int month, int day,
                        int hour, int minute, int second, int usec);
int kdbc_bind_date(kdbc_stmt *stmt, int idx,
                   int year, int month, int day);
int kdbc_bind_time(kdbc_stmt *stmt, int idx,
                   int hour, int minute, int second, int usec);

/* ========================================================================
 * Statement execution
 * ======================================================================== */

/**
 * Execute a prepared DML statement (INSERT/UPDATE/DELETE).
 * Returns the number of affected rows, or KDBC_ERROR on failure.
 */
int kdbc_execute_update_stmt(kdbc_stmt *stmt);

/**
 * Execute a prepared query (SELECT). Returns a result set handle.
 * Returns NULL on failure.
 */
kdbc_result *kdbc_execute_query_stmt(kdbc_stmt *stmt);

/**
 * Get generated keys after an INSERT (e.g. auto-increment).
 * Returns a result set with one row per generated key.
 * Returns NULL if no keys were generated or not supported.
 */
kdbc_result *kdbc_generated_keys(kdbc_stmt *stmt);

/**
 * Close a prepared statement and free resources.
 * Safe to call with NULL.
 */
void kdbc_stmt_close(kdbc_stmt *stmt);

/**
 * Reset a prepared statement for re-execution with new parameters.
 * Clears all bound parameters.
 */
int kdbc_stmt_reset(kdbc_stmt *stmt);

/**
 * Set fetch size hint for query execution.
 * Suggests how many rows the driver should fetch at a time.
 * 0 = driver default. This is a hint - drivers may ignore it.
 */
int kdbc_set_fetch_size(kdbc_stmt *stmt, int rows);

/* ========================================================================
 * Batch execution
 * ======================================================================== */

/**
 * Add current parameters as a batch entry. After calling this,
 * bind new parameter values and call again for the next row.
 * Finally call kdbc_execute_batch() to execute all rows.
 */
int kdbc_add_batch(kdbc_stmt *stmt);

/**
 * Execute all batched parameter sets.
 * Returns total number of affected rows, or KDBC_ERROR on failure.
 */
int kdbc_execute_batch(kdbc_stmt *stmt);

/* ========================================================================
 * Callable statements (stored procedures)
 * ======================================================================== */

/**
 * Prepare a stored procedure call. Uses JDBC-style syntax: "{CALL proc(?, ?)}"
 * The library strips the braces and translates for each database.
 * Returns NULL on failure.
 */
kdbc_stmt *kdbc_prepare_call(kdbc_conn *conn, const char *sql);

/**
 * Register an OUT parameter at the given index.
 * Must be called before kdbc_call_execute().
 */
int kdbc_register_out(kdbc_stmt *stmt, int idx);

/**
 * Execute a callable statement. Returns 1 if it produced a result set, 0 if not.
 */
int kdbc_call_execute(kdbc_stmt *stmt);

/**
 * Retrieve an OUT parameter value after execution.
 * Returns the value as int64. For string OUT params, use kdbc_call_get_string.
 */
int64_t     kdbc_call_get_long(kdbc_stmt *stmt, int idx);
const char *kdbc_call_get_string(kdbc_stmt *stmt, int idx);

/* ========================================================================
 * Result set navigation and data retrieval
 * ======================================================================== */

/**
 * Advance to the next row. Returns 1 if a row is available, 0 if done,
 * KDBC_ERROR on failure.
 */
int kdbc_next(kdbc_result *rs);

/**
 * Get result set column count.
 */
int kdbc_col_count(kdbc_result *rs);

/**
 * Get column name (1-indexed). Returns NULL if invalid.
 */
const char *kdbc_col_name(kdbc_result *rs, int col);

/**
 * Get column label/alias (1-indexed). Falls back to column name.
 */
const char *kdbc_col_label(kdbc_result *rs, int col);

/**
 * Check if the last retrieved column value was NULL.
 * Returns 1 if NULL, 0 if not.
 */
int kdbc_is_null(kdbc_result *rs, int col);

/**
 * Retrieve column values (all 1-indexed).
 * For type mismatches, reasonable conversions are attempted.
 * Check kdbc_is_null() after retrieval to distinguish NULL from 0/"".
 */
int64_t     kdbc_get_long(kdbc_result *rs, int col);
double      kdbc_get_double(kdbc_result *rs, int col);

/**
 * Get string value. The returned pointer is valid until the next
 * kdbc_next() call or kdbc_result_close().
 */
const char *kdbc_get_string(kdbc_result *rs, int col);

/**
 * Get blob value. Sets *out_len to the blob size in bytes.
 * The returned pointer is valid until the next kdbc_next() call
 * or kdbc_result_close().
 */
const void *kdbc_get_blob(kdbc_result *rs, int col, size_t *out_len);

/**
 * Date/time value retrieval.
 * Returns KDBC_OK on success, KDBC_ERROR if NULL or conversion failed.
 * Check kdbc_is_null() first if NULL values are possible.
 */
int kdbc_get_timestamp(kdbc_result *rs, int col,
                       int *year, int *month, int *day,
                       int *hour, int *minute, int *second, int *usec);
int kdbc_get_date(kdbc_result *rs, int col,
                  int *year, int *month, int *day);
int kdbc_get_time(kdbc_result *rs, int col,
                  int *hour, int *minute, int *second, int *usec);

/**
 * Close a result set and free resources.
 * Safe to call with NULL.
 */
void kdbc_result_close(kdbc_result *rs);

#ifdef __cplusplus
}
#endif

#endif /* KDBC_H */
