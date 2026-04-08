/*
 * KDBC Native - Test suite
 *
 * Parameterizable: runs against any database via command-line arguments.
 * Default: SQLite in-memory (no server required).
 *
 * Usage:
 *   ./test_kdbc                                         # SQLite :memory:
 *   ./test_kdbc sqlite /tmp/test.db
 *   ./test_kdbc postgresql localhost:15432/stormify_test stormify Stormify1!
 *   ./test_kdbc mariadb localhost:13307/stormify_test stormify Stormify1!
 *   ./test_kdbc oracle localhost:11521/XEPDB1 stormify Stormify1!
 *   ./test_kdbc mssql localhost:11433/stormify_test stormify Stormify1!
 *
 * SPDX-License-Identifier: Apache-2.0
 */
#include "../include/kdbc.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>

/* ========================================================================
 * Test framework (minimal)
 * ======================================================================== */

static int tests_run = 0;
static int tests_passed = 0;
static int tests_failed = 0;
static int tests_skipped = 0;

#define ASSERT(cond, msg) do { \
    if (!(cond)) { \
        printf("  FAIL: %s (line %d)\n", msg, __LINE__); \
        tests_failed++; \
        return; \
    } \
} while (0)

#define ASSERT_EQ_INT(a, b, msg) do { \
    int64_t _a = (a), _b = (b); \
    if (_a != _b) { \
        printf("  FAIL: %s: expected %lld, got %lld (line %d)\n", \
               msg, (long long)_b, (long long)_a, __LINE__); \
        tests_failed++; \
        return; \
    } \
} while (0)

#define ASSERT_EQ_DBL(a, b, msg) do { \
    double _a = (a), _b = (b); \
    if (fabs(_a - _b) > 1e-5) { \
        printf("  FAIL: %s: expected %f, got %f (line %d)\n", \
               msg, _b, _a, __LINE__); \
        tests_failed++; \
        return; \
    } \
} while (0)

#define ASSERT_EQ_STR(a, b, msg) do { \
    const char *_a = (a), *_b = (b); \
    if ((_a == NULL && _b != NULL) || (_a != NULL && _b == NULL) || \
        (_a && _b && strcmp(_a, _b) != 0)) { \
        printf("  FAIL: %s: expected \"%s\", got \"%s\" (line %d)\n", \
               msg, _b ? _b : "NULL", _a ? _a : "NULL", __LINE__); \
        tests_failed++; \
        return; \
    } \
} while (0)

#define SKIP(msg) do { \
    printf(" SKIP (%s)\n", msg); \
    tests_skipped++; \
    return; \
} while (0)

#define RUN_TEST(fn) do { \
    printf("  %s...", #fn); \
    tests_run++; \
    int _f_before = tests_failed; \
    int _s_before = tests_skipped; \
    fn(); \
    if (tests_skipped > _s_before) { /* already printed */ } \
    else if (tests_failed == _f_before) { \
        tests_passed++; \
        printf(" OK\n"); \
    } else { \
        printf("\n"); \
    } \
} while (0)

/* ========================================================================
 * Global test configuration
 * ======================================================================== */

static kdbc_driver  g_driver   = KDBC_SQLITE;
static const char  *g_url      = ":memory:";
static const char  *g_user     = NULL;
static const char  *g_password = NULL;

/* Is the test database SQLite? (some tests are SQLite-specific) */
static int is_sqlite(void)   { return g_driver == KDBC_SQLITE; }
static int is_oracle(void)   { return g_driver == KDBC_ORACLE; }
static int is_mssql(void)    { return g_driver == KDBC_MSSQL; }
static int is_postgres(void) { return g_driver == KDBC_POSTGRES; }

/* ========================================================================
 * Helper: open a connection using global config
 * ======================================================================== */

static kdbc_conn *open_db(void) {
    kdbc_conn *conn = kdbc_connect(g_driver, g_url, g_user, g_password);
    if (!conn) {
        printf("  Cannot connect (%s): %s\n",
               kdbc_driver_name(g_driver), kdbc_global_error());
    }
    return conn;
}

static int exec_sql(kdbc_conn *conn, const char *sql) {
    if (!conn) return -1;
    return kdbc_execute_update(conn, sql);
}

/* Drop table, ignoring errors (table may not exist) */
static void drop_table(kdbc_conn *conn, const char *name) {
    if (!conn) return;
    char sql[256];
    if (is_oracle())
        snprintf(sql, sizeof(sql),
                 "BEGIN EXECUTE IMMEDIATE 'DROP TABLE %s'; EXCEPTION WHEN OTHERS THEN NULL; END;", name);
    else
        snprintf(sql, sizeof(sql), "DROP TABLE IF EXISTS %s", name);
    exec_sql(conn, sql);
}

/* ========================================================================
 * Dialect-aware DDL helpers
 * ======================================================================== */

/* Auto-increment column syntax per database */
static const char *auto_inc_col(void) {
    switch (g_driver) {
        case KDBC_SQLITE:   return "id INTEGER PRIMARY KEY AUTOINCREMENT";
        case KDBC_POSTGRES:  return "id SERIAL PRIMARY KEY";
        case KDBC_MARIADB:   return "id INT AUTO_INCREMENT PRIMARY KEY";
        case KDBC_ORACLE:    return "id NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY";
        case KDBC_MSSQL:   return "id INT IDENTITY(1,1) PRIMARY KEY";
        default:             return "id INTEGER PRIMARY KEY AUTOINCREMENT";
    }
}

/* Integer type */
static const char *int_type(void) {
    return is_oracle() ? "NUMBER(10)" : "INTEGER";
}

/* Big integer type */
static const char *bigint_type(void) {
    return is_oracle() ? "NUMBER(19)" : "BIGINT";
}

/* Float type */
static const char *float_type(void) {
    switch (g_driver) {
        case KDBC_ORACLE: return "BINARY_DOUBLE";
        default:          return "DOUBLE PRECISION";
    }
}

/* Boolean type */
static const char *bool_type(void) {
    switch (g_driver) {
        case KDBC_ORACLE:  return "NUMBER(1)";
        case KDBC_SQLITE:  return "INTEGER";
        case KDBC_MSSQL:   return "BIT";
        default:           return "BOOLEAN";
    }
}

/* Text type */
static const char *text_type(void) {
    switch (g_driver) {
        case KDBC_ORACLE:  return "VARCHAR2(4000)";
        case KDBC_MSSQL: return "NVARCHAR(4000)";
        default:           return "TEXT";
    }
}

/* Timestamp type */
static const char *timestamp_type(void) {
    switch (g_driver) {
        case KDBC_ORACLE:   return "TIMESTAMP";
        case KDBC_MSSQL:  return "DATETIME";
        default:            return "TIMESTAMP";
    }
}

/* Date type */
static const char *date_type(void) {
    return "DATE";
}

/* Time type */
static const char *time_type(void) {
    switch (g_driver) {
        case KDBC_SQLITE:  return "TEXT";      /* SQLite stores as text */
        case KDBC_ORACLE:  return "TIMESTAMP"; /* Oracle has no TIME, use TIMESTAMP */
        default:           return "TIME";
    }
}

/* Blob type */
static const char *blob_type(void) {
    switch (g_driver) {
        case KDBC_POSTGRES: return "BYTEA";
        case KDBC_ORACLE:   return "RAW(2000)";
        case KDBC_MSSQL:  return "VARBINARY(4000)";
        default:            return "BLOB";
    }
}

/* ========================================================================
 * Tests: Driver discovery (always run, don't need DB connection)
 * ======================================================================== */

static void test_driver_available(void) {
    ASSERT(kdbc_driver_available(g_driver), "test driver should be available");
    /* Names should always be set */
    ASSERT(strlen(kdbc_driver_name(KDBC_SQLITE)) > 0, "sqlite name");
    ASSERT(strlen(kdbc_driver_name(KDBC_POSTGRES)) > 0, "pg name");
    ASSERT(strlen(kdbc_driver_name(KDBC_MARIADB)) > 0, "mariadb name");
    ASSERT(strlen(kdbc_driver_name(KDBC_ORACLE)) > 0, "oracle name");
    ASSERT(strlen(kdbc_driver_name(KDBC_MSSQL)) > 0, "mssql name");
}

static void test_driver_capabilities(void) {
    /* Verify GK strategy makes sense */
    kdbc_gk_strategy gk = kdbc_driver_gk_strategy(g_driver);
    ASSERT(gk == KDBC_GK_NONE || gk == KDBC_GK_BY_INDEX || gk == KDBC_GK_BY_NAME,
           "valid gk strategy");

    /* SQLite/MariaDB/PG support release savepoint, Oracle/MSSQL don't */
    if (is_sqlite())
        ASSERT(kdbc_driver_supports_release_savepoint(KDBC_SQLITE), "sqlite release sp");
    if (is_oracle())
        ASSERT(!kdbc_driver_supports_release_savepoint(KDBC_ORACLE), "oracle no release sp");
    if (is_mssql())
        ASSERT(!kdbc_driver_supports_release_savepoint(KDBC_MSSQL), "mssql no release sp");
}

/* ========================================================================
 * Tests: Connection and metadata
 * ======================================================================== */

static void test_connect(void) {
    kdbc_conn *conn = open_db();
    ASSERT(conn != NULL, "connection should not be null");

    ASSERT(strlen(kdbc_product_name(conn)) > 0, "product name non-empty");
    ASSERT(strlen(kdbc_product_version(conn)) > 0, "version non-empty");

    /* Connection-level GK strategy */
    kdbc_gk_strategy gk = kdbc_conn_gk_strategy(conn);
    ASSERT(gk == KDBC_GK_NONE || gk == KDBC_GK_BY_INDEX || gk == KDBC_GK_BY_NAME,
           "valid conn gk strategy");

    kdbc_close(conn);
}

static void test_connect_failure(void) {
    kdbc_conn *conn = kdbc_connect(99, "dummy", NULL, NULL);
    ASSERT(conn == NULL, "should fail with invalid driver");
    ASSERT(strlen(kdbc_global_error()) > 0, "global error should be set");
}

/* ========================================================================
 * Tests: DDL and basic CRUD
 * ======================================================================== */

static void test_create_and_read(void) {
    kdbc_conn *conn = open_db();
    ASSERT(conn != NULL, "conn");

    drop_table(conn, "kdbc_crud");
    char ddl[512];
    snprintf(ddl, sizeof(ddl), "CREATE TABLE kdbc_crud (id %s, name %s, email %s)",
             int_type(), text_type(), text_type());
    exec_sql(conn, ddl);

    kdbc_stmt *stmt = kdbc_prepare(conn, "INSERT INTO kdbc_crud (id, name, email) VALUES (?, ?, ?)");
    ASSERT(stmt != NULL, "prepare insert");
    kdbc_bind_int(stmt, 1, 1);
    kdbc_bind_string(stmt, 2, "Alice");
    kdbc_bind_string(stmt, 3, "alice@example.com");
    int rows = kdbc_execute_update_stmt(stmt);
    ASSERT_EQ_INT(rows, 1, "1 row inserted");
    kdbc_stmt_close(stmt);

    stmt = kdbc_prepare(conn, "SELECT id, name, email FROM kdbc_crud WHERE id = ?");
    ASSERT(stmt != NULL, "prepare select");
    kdbc_bind_int(stmt, 1, 1);
    kdbc_result *rs = kdbc_execute_query_stmt(stmt);
    ASSERT(rs != NULL, "result set");
    ASSERT_EQ_INT(kdbc_col_count(rs), 3, "3 columns");
    ASSERT(kdbc_next(rs) == 1, "has row");
    ASSERT_EQ_INT(kdbc_get_long(rs, 1), 1, "id");
    ASSERT_EQ_STR(kdbc_get_string(rs, 2), "Alice", "name");
    ASSERT_EQ_STR(kdbc_get_string(rs, 3), "alice@example.com", "email");
    ASSERT(kdbc_next(rs) == 0, "no more rows");
    kdbc_result_close(rs);
    kdbc_stmt_close(stmt);

    drop_table(conn, "kdbc_crud");
    kdbc_close(conn);
}

static void test_update(void) {
    kdbc_conn *conn = open_db();
    ASSERT(conn != NULL, "conn");

    drop_table(conn, "kdbc_upd");
    char ddl[256];
    snprintf(ddl, sizeof(ddl), "CREATE TABLE kdbc_upd (id %s, val %s)", int_type(), text_type());
    exec_sql(conn, ddl);
    exec_sql(conn, "INSERT INTO kdbc_upd (id, val) VALUES (1, 'old')");

    kdbc_stmt *stmt = kdbc_prepare(conn, "UPDATE kdbc_upd SET val = ? WHERE id = ?");
    kdbc_bind_string(stmt, 1, "new");
    kdbc_bind_int(stmt, 2, 1);
    ASSERT_EQ_INT(kdbc_execute_update_stmt(stmt), 1, "updated 1");
    kdbc_stmt_close(stmt);

    stmt = kdbc_prepare(conn, "SELECT val FROM kdbc_upd WHERE id = 1");
    kdbc_result *rs = kdbc_execute_query_stmt(stmt);
    ASSERT(kdbc_next(rs), "has row");
    ASSERT_EQ_STR(kdbc_get_string(rs, 1), "new", "updated value");
    kdbc_result_close(rs);
    kdbc_stmt_close(stmt);

    drop_table(conn, "kdbc_upd");
    kdbc_close(conn);
}

static void test_delete(void) {
    kdbc_conn *conn = open_db();
    drop_table(conn, "kdbc_del");
    char ddl[128];
    snprintf(ddl, sizeof(ddl), "CREATE TABLE kdbc_del (id %s)", int_type());
    exec_sql(conn, ddl);
    exec_sql(conn, "INSERT INTO kdbc_del (id) VALUES (1)");
    exec_sql(conn, "INSERT INTO kdbc_del (id) VALUES (2)");
    exec_sql(conn, "INSERT INTO kdbc_del (id) VALUES (3)");

    kdbc_stmt *stmt = kdbc_prepare(conn, "DELETE FROM kdbc_del WHERE id > ?");
    kdbc_bind_int(stmt, 1, 1);
    ASSERT_EQ_INT(kdbc_execute_update_stmt(stmt), 2, "deleted 2");
    kdbc_stmt_close(stmt);

    drop_table(conn, "kdbc_del");
    kdbc_close(conn);
}

/* ========================================================================
 * Tests: Types
 * ======================================================================== */

static void test_integer_types(void) {
    kdbc_conn *conn = open_db();
    drop_table(conn, "kdbc_int");
    char ddl[256];
    snprintf(ddl, sizeof(ddl), "CREATE TABLE kdbc_int (a %s, b %s)", int_type(), bigint_type());
    exec_sql(conn, ddl);

    kdbc_stmt *stmt = kdbc_prepare(conn, "INSERT INTO kdbc_int (a, b) VALUES (?, ?)");
    kdbc_bind_int(stmt, 1, 42);
    kdbc_bind_long(stmt, 2, 9999999999LL);
    kdbc_execute_update_stmt(stmt);
    kdbc_stmt_close(stmt);

    stmt = kdbc_prepare(conn, "SELECT a, b FROM kdbc_int");
    kdbc_result *rs = kdbc_execute_query_stmt(stmt);
    ASSERT(kdbc_next(rs), "has row");
    ASSERT_EQ_INT(kdbc_get_long(rs, 1), 42, "int");
    ASSERT_EQ_INT(kdbc_get_long(rs, 2), 9999999999LL, "bigint");
    kdbc_result_close(rs);
    kdbc_stmt_close(stmt);

    drop_table(conn, "kdbc_int");
    kdbc_close(conn);
}

static void test_double_type(void) {
    kdbc_conn *conn = open_db();
    drop_table(conn, "kdbc_dbl");
    char ddl[128];
    snprintf(ddl, sizeof(ddl), "CREATE TABLE kdbc_dbl (val %s)", float_type());
    exec_sql(conn, ddl);

    kdbc_stmt *stmt = kdbc_prepare(conn, "INSERT INTO kdbc_dbl (val) VALUES (?)");
    kdbc_bind_double(stmt, 1, 3.14159265358979);
    kdbc_execute_update_stmt(stmt);
    kdbc_stmt_close(stmt);

    stmt = kdbc_prepare(conn, "SELECT val FROM kdbc_dbl");
    kdbc_result *rs = kdbc_execute_query_stmt(stmt);
    ASSERT(kdbc_next(rs), "has row");
    ASSERT_EQ_DBL(kdbc_get_double(rs, 1), 3.14159265358979, "double");
    kdbc_result_close(rs);
    kdbc_stmt_close(stmt);

    drop_table(conn, "kdbc_dbl");
    kdbc_close(conn);
}

static void test_bool_type(void) {
    kdbc_conn *conn = open_db();
    drop_table(conn, "kdbc_bool");
    char ddl[128];
    snprintf(ddl, sizeof(ddl), "CREATE TABLE kdbc_bool (a %s, b %s)", bool_type(), bool_type());
    exec_sql(conn, ddl);

    kdbc_stmt *stmt = kdbc_prepare(conn, "INSERT INTO kdbc_bool (a, b) VALUES (?, ?)");
    kdbc_bind_bool(stmt, 1, 1);
    kdbc_bind_bool(stmt, 2, 0);
    kdbc_execute_update_stmt(stmt);
    kdbc_stmt_close(stmt);

    stmt = kdbc_prepare(conn, "SELECT a, b FROM kdbc_bool");
    kdbc_result *rs = kdbc_execute_query_stmt(stmt);
    ASSERT(kdbc_next(rs), "has row");
    ASSERT_EQ_INT(kdbc_get_long(rs, 1), 1, "true");
    ASSERT_EQ_INT(kdbc_get_long(rs, 2), 0, "false");
    kdbc_result_close(rs);
    kdbc_stmt_close(stmt);

    drop_table(conn, "kdbc_bool");
    kdbc_close(conn);
}

static void test_string_type(void) {
    kdbc_conn *conn = open_db();
    drop_table(conn, "kdbc_str");
    char ddl[128];
    snprintf(ddl, sizeof(ddl), "CREATE TABLE kdbc_str (val %s)", text_type());
    exec_sql(conn, ddl);

    const char *text = "Hello, World!";
    kdbc_stmt *stmt = kdbc_prepare(conn, "INSERT INTO kdbc_str (val) VALUES (?)");
    kdbc_bind_string(stmt, 1, text);
    kdbc_execute_update_stmt(stmt);
    kdbc_stmt_close(stmt);

    stmt = kdbc_prepare(conn, "SELECT val FROM kdbc_str");
    kdbc_result *rs = kdbc_execute_query_stmt(stmt);
    ASSERT(kdbc_next(rs), "has row");
    ASSERT_EQ_STR(kdbc_get_string(rs, 1), text, "string");
    kdbc_result_close(rs);
    kdbc_stmt_close(stmt);

    drop_table(conn, "kdbc_str");
    kdbc_close(conn);
}

static void test_blob_type(void) {
    kdbc_conn *conn = open_db();
    drop_table(conn, "kdbc_blob");
    char ddl[128];
    snprintf(ddl, sizeof(ddl), "CREATE TABLE kdbc_blob (val %s)", blob_type());
    exec_sql(conn, ddl);

    unsigned char data[] = {0x00, 0x01, 0xFF, 0xFE, 0x42, 0x00, 0xAB};
    kdbc_stmt *stmt = kdbc_prepare(conn, "INSERT INTO kdbc_blob (val) VALUES (?)");
    if (!stmt) {
        /* MSSQL: sp_prepare rejects VARBINARY params (known FreeTDS limitation).
         * Verify error message is set, then pass — Kotlin uses direct execution. */
        ASSERT(is_mssql(), "only MSSQL should fail blob prepare");
        ASSERT(strlen(kdbc_error(conn)) > 0, "blob prepare error message set");
        drop_table(conn, "kdbc_blob");
        kdbc_close(conn);
        return;
    }
    kdbc_bind_blob(stmt, 1, data, sizeof(data));
    kdbc_execute_update_stmt(stmt);
    kdbc_stmt_close(stmt);

    kdbc_result *rs = kdbc_execute_query(conn, "SELECT val FROM kdbc_blob");
    ASSERT(rs != NULL, "query");
    ASSERT(kdbc_next(rs), "has row");
    size_t len = 0;
    const void *blob = kdbc_get_blob(rs, 1, &len);
    ASSERT(blob != NULL, "blob not null");
    ASSERT_EQ_INT((int)len, (int)sizeof(data), "blob length");
    ASSERT(memcmp(blob, data, len) == 0, "blob content");
    kdbc_result_close(rs);

    drop_table(conn, "kdbc_blob");
    kdbc_close(conn);
}

static void test_null_handling(void) {
    kdbc_conn *conn = open_db();
    drop_table(conn, "kdbc_null");
    char ddl[256];
    snprintf(ddl, sizeof(ddl), "CREATE TABLE kdbc_null (a %s, b %s)", int_type(), text_type());
    exec_sql(conn, ddl);

    kdbc_stmt *stmt = kdbc_prepare(conn, "INSERT INTO kdbc_null (a, b) VALUES (?, ?)");
    kdbc_bind_null(stmt, 1);
    kdbc_bind_null(stmt, 2);
    kdbc_execute_update_stmt(stmt);
    kdbc_stmt_close(stmt);

    stmt = kdbc_prepare(conn, "SELECT a, b FROM kdbc_null");
    kdbc_result *rs = kdbc_execute_query_stmt(stmt);
    ASSERT(kdbc_next(rs), "has row");
    ASSERT(kdbc_is_null(rs, 1), "a is null");
    ASSERT(kdbc_is_null(rs, 2), "b is null");
    kdbc_result_close(rs);
    kdbc_stmt_close(stmt);

    drop_table(conn, "kdbc_null");
    kdbc_close(conn);
}

/* ========================================================================
 * Tests: Date/Time types
 * ======================================================================== */

static void test_timestamp(void) {
    kdbc_conn *conn = open_db();
    drop_table(conn, "kdbc_ts");
    char ddl[128];
    snprintf(ddl, sizeof(ddl), "CREATE TABLE kdbc_ts (val %s)", timestamp_type());
    exec_sql(conn, ddl);

    kdbc_stmt *stmt = kdbc_prepare(conn, "INSERT INTO kdbc_ts (val) VALUES (?)");
    ASSERT(stmt != NULL, "prepare");
    kdbc_bind_timestamp(stmt, 1, 2024, 6, 15, 14, 30, 45, 123456);
    ASSERT(kdbc_execute_update_stmt(stmt) >= 0, "insert");
    kdbc_stmt_close(stmt);

    kdbc_result *rs = kdbc_execute_query(conn, "SELECT val FROM kdbc_ts");
    ASSERT(kdbc_next(rs), "has row");
    int y, mo, d, h, mi, s, us;
    int rc = kdbc_get_timestamp(rs, 1, &y, &mo, &d, &h, &mi, &s, &us);
    ASSERT(rc == KDBC_OK, "get_timestamp ok");
    ASSERT_EQ_INT(y, 2024, "year");
    ASSERT_EQ_INT(mo, 6, "month");
    ASSERT_EQ_INT(d, 15, "day");
    ASSERT_EQ_INT(h, 14, "hour");
    ASSERT_EQ_INT(mi, 30, "minute");
    ASSERT_EQ_INT(s, 45, "second");
    /* usec precision depends on DB - just check non-negative */
    ASSERT(us >= 0, "usec >= 0");
    kdbc_result_close(rs);

    drop_table(conn, "kdbc_ts");
    kdbc_close(conn);
}

static void test_date(void) {

    kdbc_conn *conn = open_db();
    drop_table(conn, "kdbc_dt");
    char ddl[128];
    snprintf(ddl, sizeof(ddl), "CREATE TABLE kdbc_dt (val %s)", date_type());
    exec_sql(conn, ddl);

    kdbc_stmt *stmt = kdbc_prepare(conn, "INSERT INTO kdbc_dt (val) VALUES (?)");
    ASSERT(stmt != NULL, "prepare");
    kdbc_bind_date(stmt, 1, 2024, 12, 25);
    ASSERT(kdbc_execute_update_stmt(stmt) >= 0, "insert");
    kdbc_stmt_close(stmt);

    kdbc_result *rs = kdbc_execute_query(conn, "SELECT val FROM kdbc_dt");
    ASSERT(kdbc_next(rs), "has row");
    int y, mo, d;
    int rc = kdbc_get_date(rs, 1, &y, &mo, &d);
    ASSERT(rc == KDBC_OK, "get_date ok");
    ASSERT_EQ_INT(y, 2024, "year");
    ASSERT_EQ_INT(mo, 12, "month");
    ASSERT_EQ_INT(d, 25, "day");
    kdbc_result_close(rs);

    drop_table(conn, "kdbc_dt");
    kdbc_close(conn);
}

static void test_time(void) {

    kdbc_conn *conn = open_db();
    drop_table(conn, "kdbc_tm");
    char ddl[128];
    snprintf(ddl, sizeof(ddl), "CREATE TABLE kdbc_tm (val %s)", time_type());
    exec_sql(conn, ddl);

    kdbc_stmt *stmt = kdbc_prepare(conn, "INSERT INTO kdbc_tm (val) VALUES (?)");
    ASSERT(stmt != NULL, "prepare");
    kdbc_bind_time(stmt, 1, 23, 59, 58, 0);
    ASSERT(kdbc_execute_update_stmt(stmt) >= 0, "insert");
    kdbc_stmt_close(stmt);

    kdbc_result *rs = kdbc_execute_query(conn, "SELECT val FROM kdbc_tm");
    ASSERT(kdbc_next(rs), "has row");
    int h, mi, s, us;
    int rc = kdbc_get_time(rs, 1, &h, &mi, &s, &us);
    ASSERT(rc == KDBC_OK, "get_time ok");
    ASSERT_EQ_INT(h, 23, "hour");
    ASSERT_EQ_INT(mi, 59, "minute");
    ASSERT_EQ_INT(s, 58, "second");
    kdbc_result_close(rs);

    drop_table(conn, "kdbc_tm");
    kdbc_close(conn);
}

/* ========================================================================
 * Tests: Transactions
 * ======================================================================== */

static void test_transaction_commit(void) {
    kdbc_conn *conn = open_db();
    drop_table(conn, "kdbc_tx");
    char ddl[128];
    snprintf(ddl, sizeof(ddl), "CREATE TABLE kdbc_tx (id %s)", int_type());
    exec_sql(conn, ddl);

    kdbc_set_autocommit(conn, 0);
    exec_sql(conn, "INSERT INTO kdbc_tx (id) VALUES (1)");
    exec_sql(conn, "INSERT INTO kdbc_tx (id) VALUES (2)");
    kdbc_commit(conn);

    kdbc_stmt *stmt = kdbc_prepare(conn, "SELECT COUNT(*) FROM kdbc_tx");
    kdbc_result *rs = kdbc_execute_query_stmt(stmt);
    ASSERT(kdbc_next(rs), "has row");
    ASSERT_EQ_INT(kdbc_get_long(rs, 1), 2, "2 committed");
    kdbc_result_close(rs);
    kdbc_stmt_close(stmt);

    kdbc_set_autocommit(conn, 1);
    drop_table(conn, "kdbc_tx");
    kdbc_close(conn);
}

static void test_transaction_rollback(void) {
    kdbc_conn *conn = open_db();
    drop_table(conn, "kdbc_rb");
    char ddl[128];
    snprintf(ddl, sizeof(ddl), "CREATE TABLE kdbc_rb (id %s)", int_type());
    exec_sql(conn, ddl);
    exec_sql(conn, "INSERT INTO kdbc_rb (id) VALUES (1)");

    kdbc_set_autocommit(conn, 0);
    exec_sql(conn, "INSERT INTO kdbc_rb (id) VALUES (2)");
    exec_sql(conn, "INSERT INTO kdbc_rb (id) VALUES (3)");
    kdbc_rollback(conn);

    kdbc_stmt *stmt = kdbc_prepare(conn, "SELECT COUNT(*) FROM kdbc_rb");
    kdbc_result *rs = kdbc_execute_query_stmt(stmt);
    ASSERT(kdbc_next(rs), "has row");
    ASSERT_EQ_INT(kdbc_get_long(rs, 1), 1, "1 after rollback");
    kdbc_result_close(rs);
    kdbc_stmt_close(stmt);

    kdbc_set_autocommit(conn, 1);
    drop_table(conn, "kdbc_rb");
    kdbc_close(conn);
}

static void test_savepoint(void) {
    kdbc_conn *conn = open_db();
    drop_table(conn, "kdbc_sp");
    char ddl[128];
    snprintf(ddl, sizeof(ddl), "CREATE TABLE kdbc_sp (id %s)", int_type());
    exec_sql(conn, ddl);

    kdbc_set_autocommit(conn, 0);
    exec_sql(conn, "INSERT INTO kdbc_sp (id) VALUES (1)");
    kdbc_savepoint(conn, "sp1");
    exec_sql(conn, "INSERT INTO kdbc_sp (id) VALUES (2)");
    kdbc_rollback_to(conn, "sp1");
    kdbc_commit(conn);

    kdbc_stmt *stmt = kdbc_prepare(conn, "SELECT COUNT(*) FROM kdbc_sp");
    kdbc_result *rs = kdbc_execute_query_stmt(stmt);
    ASSERT(kdbc_next(rs), "has row");
    ASSERT_EQ_INT(kdbc_get_long(rs, 1), 1, "1 after savepoint rollback");
    kdbc_result_close(rs);
    kdbc_stmt_close(stmt);

    kdbc_set_autocommit(conn, 1);
    drop_table(conn, "kdbc_sp");
    kdbc_close(conn);
}

/* ========================================================================
 * Tests: Generated keys
 * ======================================================================== */

static void test_generated_keys(void) {
    kdbc_gk_strategy gk = kdbc_driver_gk_strategy(g_driver);
    if (gk == KDBC_GK_NONE) SKIP("driver doesn't support generated keys");

    kdbc_conn *conn = open_db();

    /* Oracle IDENTITY columns require 12c+; skip on 11g and earlier. */
    if (is_oracle() && kdbc_major_version(conn) < 12) {
        kdbc_close(conn);
        SKIP("Oracle < 12c has no IDENTITY columns");
    }

    drop_table(conn, "kdbc_gk");
    char ddl[256];
    snprintf(ddl, sizeof(ddl), "CREATE TABLE kdbc_gk (%s, val %s)", auto_inc_col(), text_type());
    exec_sql(conn, ddl);

    const char *cols[] = { "id" };
    kdbc_stmt *stmt = kdbc_prepare_returning(conn, "INSERT INTO kdbc_gk (val) VALUES (?)", cols, 1);
    ASSERT(stmt != NULL, "prepare returning");

    kdbc_bind_string(stmt, 1, "first");
    ASSERT_EQ_INT(kdbc_execute_update_stmt(stmt), 1, "1 row");

    kdbc_result *keys = kdbc_generated_keys(stmt);
    ASSERT(keys != NULL, "has keys");
    ASSERT(kdbc_next(keys) == 1, "has key row");
    int64_t key1 = kdbc_get_long(keys, 1);
    ASSERT(key1 > 0, "key > 0");
    kdbc_result_close(keys);
    kdbc_stmt_close(stmt);

    /* Second insert should get next key */
    stmt = kdbc_prepare_returning(conn, "INSERT INTO kdbc_gk (val) VALUES (?)", cols, 1);
    kdbc_bind_string(stmt, 1, "second");
    kdbc_execute_update_stmt(stmt);
    keys = kdbc_generated_keys(stmt);
    ASSERT(keys != NULL, "has keys 2");
    ASSERT(kdbc_next(keys), "has key row 2");
    int64_t key2 = kdbc_get_long(keys, 1);
    ASSERT(key2 > key1, "key2 > key1");
    kdbc_result_close(keys);
    kdbc_stmt_close(stmt);

    drop_table(conn, "kdbc_gk");
    kdbc_close(conn);
}

/* Server-generated non-numeric (UUID/GUID) primary keys via the "RETURNING
 * clause" family of drivers (Group A): Oracle (SYS_GUID + RETURNING INTO),
 * PostgreSQL (gen_random_uuid + RETURNING), and MSSQL (NEWID + OUTPUT
 * INSERTED). All three drivers must correctly surface a string value
 * through the generated-keys path.
 *
 * Skipped on Group B drivers (MariaDB/MySQL/SQLite): their generated-keys
 * path goes through mysql_stmt_insert_id / sqlite3_last_insert_rowid, which
 * can only return an AUTO_INCREMENT integer / internal rowid. A non-numeric
 * server-side default cannot be retrieved through those APIs — client-side
 * UUID generation is the only portable alternative there, covered by the
 * common-level testStringPkRoundTrip. */
static void test_generated_keys_string_pk(void) {
    if (!is_oracle() && !is_postgres() && !is_mssql())
        SKIP("Group A only: Oracle/PG/MSSQL RETURNING-family drivers");

    kdbc_conn *conn = open_db();
    drop_table(conn, "kdbc_gk_uuid");

    /* Dialect-specific DDL: VARCHAR PK with a server-generated UUID default. */
    if (is_oracle()) {
        exec_sql(conn,
            "CREATE TABLE kdbc_gk_uuid ("
            "  id VARCHAR2(36) DEFAULT SYS_GUID() PRIMARY KEY, "
            "  val VARCHAR2(100)"
            ")");
    } else if (is_postgres()) {
        /* pgcrypto provides gen_random_uuid on older PG; PG 13+ has it built-in.
         * The UUID type round-trips as text through libpq's text protocol. */
        exec_sql(conn, "CREATE EXTENSION IF NOT EXISTS pgcrypto");
        exec_sql(conn,
            "CREATE TABLE kdbc_gk_uuid ("
            "  id UUID DEFAULT gen_random_uuid() PRIMARY KEY, "
            "  val VARCHAR(100)"
            ")");
    } else { /* MSSQL */
        exec_sql(conn,
            "CREATE TABLE kdbc_gk_uuid ("
            "  id UNIQUEIDENTIFIER DEFAULT NEWID() PRIMARY KEY, "
            "  val VARCHAR(100)"
            ")");
    }

    const char *cols[] = { "id" };
    kdbc_stmt *stmt = kdbc_prepare_returning(conn,
        "INSERT INTO kdbc_gk_uuid (val) VALUES (?)", cols, 1);
    ASSERT(stmt != NULL, "prepare returning uuid");

    kdbc_bind_string(stmt, 1, "alpha");
    ASSERT_EQ_INT(kdbc_execute_update_stmt(stmt), 1, "1 row alpha");

    kdbc_result *keys = kdbc_generated_keys(stmt);
    ASSERT(keys != NULL, "has keys uuid");
    ASSERT(kdbc_next(keys) == 1, "has key row uuid");
    const char *uuid1 = kdbc_get_string(keys, 1);
    ASSERT(uuid1 != NULL, "uuid1 non-null");
    /* Length varies: Oracle SYS_GUID → 32 hex chars, PG gen_random_uuid and
     * MSSQL NEWID → 36-char canonical UUID with hyphens. */
    size_t len1 = uuid1 ? strlen(uuid1) : 0;
    ASSERT(len1 >= 32 && len1 <= 36, "uuid1 length reasonable");
    /* Copy before closing the result (pointer tied to RS lifetime). */
    char uuid1_copy[64];
    snprintf(uuid1_copy, sizeof(uuid1_copy), "%s", uuid1 ? uuid1 : "");
    kdbc_result_close(keys);
    kdbc_stmt_close(stmt);

    /* Second insert — must produce a DIFFERENT UUID (all three generators
     * are random: SYS_GUID/gen_random_uuid/NEWID). */
    stmt = kdbc_prepare_returning(conn,
        "INSERT INTO kdbc_gk_uuid (val) VALUES (?)", cols, 1);
    kdbc_bind_string(stmt, 1, "beta");
    kdbc_execute_update_stmt(stmt);
    keys = kdbc_generated_keys(stmt);
    ASSERT(keys != NULL, "has keys uuid 2");
    ASSERT(kdbc_next(keys), "has key row uuid 2");
    const char *uuid2 = kdbc_get_string(keys, 1);
    ASSERT(uuid2 != NULL && strlen(uuid2) > 0, "uuid2 non-empty");
    ASSERT(strcmp(uuid1_copy, uuid2) != 0, "uuid2 differs from uuid1");
    kdbc_result_close(keys);
    kdbc_stmt_close(stmt);

    /* Verify both rows exist by SELECT-ing back by PK. */
    stmt = kdbc_prepare(conn, "SELECT val FROM kdbc_gk_uuid WHERE id = ?");
    kdbc_bind_string(stmt, 1, uuid1_copy);
    kdbc_result *rs = kdbc_execute_query_stmt(stmt);
    ASSERT(rs != NULL, "select by uuid1");
    ASSERT(kdbc_next(rs) == 1, "row found by uuid1");
    const char *val = kdbc_get_string(rs, 1);
    ASSERT(val && strcmp(val, "alpha") == 0, "val == alpha");
    kdbc_result_close(rs);
    kdbc_stmt_close(stmt);

    drop_table(conn, "kdbc_gk_uuid");
    kdbc_close(conn);
}

/* ========================================================================
 * Tests: Column metadata
 * ======================================================================== */

static void test_column_names(void) {
    kdbc_conn *conn = open_db();
    drop_table(conn, "kdbc_meta");
    char ddl[256];
    snprintf(ddl, sizeof(ddl), "CREATE TABLE kdbc_meta (id %s, user_name %s, email_addr %s)",
             int_type(), text_type(), text_type());
    exec_sql(conn, ddl);
    exec_sql(conn, "INSERT INTO kdbc_meta (id, user_name, email_addr) VALUES (1, 'Bob', 'bob@test.com')");

    kdbc_stmt *stmt = kdbc_prepare(conn, "SELECT id, user_name, email_addr FROM kdbc_meta");
    kdbc_result *rs = kdbc_execute_query_stmt(stmt);
    ASSERT_EQ_INT(kdbc_col_count(rs), 3, "3 cols");

    /* Oracle returns UPPERCASE column names */
    const char *c1 = kdbc_col_name(rs, 1);
    const char *c2 = kdbc_col_name(rs, 2);
    const char *c3 = kdbc_col_name(rs, 3);
    ASSERT(c1 != NULL && c2 != NULL && c3 != NULL, "names not null");

    kdbc_result_close(rs);
    kdbc_stmt_close(stmt);
    drop_table(conn, "kdbc_meta");
    kdbc_close(conn);
}

/* ========================================================================
 * Tests: Multiple rows
 * ======================================================================== */

static void test_multiple_rows(void) {
    kdbc_conn *conn = open_db();
    drop_table(conn, "kdbc_multi");
    char ddl[256];
    snprintf(ddl, sizeof(ddl), "CREATE TABLE kdbc_multi (id %s, val %s)", int_type(), text_type());
    exec_sql(conn, ddl);

    for (int i = 1; i <= 100; i++) {
        kdbc_stmt *stmt = kdbc_prepare(conn, "INSERT INTO kdbc_multi (id, val) VALUES (?, ?)");
        kdbc_bind_int(stmt, 1, i);
        char buf[32];
        snprintf(buf, sizeof(buf), "row_%d", i);
        kdbc_bind_string(stmt, 2, buf);
        kdbc_execute_update_stmt(stmt);
        kdbc_stmt_close(stmt);
    }

    kdbc_stmt *stmt = kdbc_prepare(conn, "SELECT id FROM kdbc_multi ORDER BY id");
    kdbc_result *rs = kdbc_execute_query_stmt(stmt);
    int count = 0;
    while (kdbc_next(rs) == 1) count++;
    ASSERT_EQ_INT(count, 100, "100 rows");
    kdbc_result_close(rs);
    kdbc_stmt_close(stmt);

    drop_table(conn, "kdbc_multi");
    kdbc_close(conn);
}

/* ========================================================================
 * Tests: Batch execution
 * ======================================================================== */

static void test_batch_insert(void) {
    kdbc_conn *conn = open_db();
    drop_table(conn, "kdbc_batch");
    char ddl[256];
    snprintf(ddl, sizeof(ddl), "CREATE TABLE kdbc_batch (id %s, name %s)", int_type(), text_type());
    exec_sql(conn, ddl);

    kdbc_stmt *stmt = kdbc_prepare(conn, "INSERT INTO kdbc_batch (id, name) VALUES (?, ?)");
    ASSERT(stmt != NULL, "prepare");

    for (int i = 1; i <= 5; i++) {
        kdbc_bind_int(stmt, 1, i);
        char name[32];
        snprintf(name, sizeof(name), "row_%d", i);
        kdbc_bind_string(stmt, 2, name);
        kdbc_add_batch(stmt);
    }

    int total = kdbc_execute_batch(stmt);
    ASSERT_EQ_INT(total, 5, "batch 5 rows");
    kdbc_stmt_close(stmt);

    stmt = kdbc_prepare(conn, "SELECT COUNT(*) FROM kdbc_batch");
    kdbc_result *rs = kdbc_execute_query_stmt(stmt);
    ASSERT(kdbc_next(rs), "has row");
    ASSERT_EQ_INT(kdbc_get_long(rs, 1), 5, "5 rows in table");
    kdbc_result_close(rs);
    kdbc_stmt_close(stmt);

    drop_table(conn, "kdbc_batch");
    kdbc_close(conn);
}

/* ========================================================================
 * Tests: Error handling
 * ======================================================================== */

static void test_invalid_sql(void) {
    kdbc_conn *conn = open_db();
    ASSERT(conn != NULL, "conn");
    kdbc_stmt *stmt = kdbc_prepare(conn, "THIS IS NOT SQL");
    if (stmt == NULL) {
        /* Driver caught error at prepare time (SQLite, PostgreSQL, MariaDB) */
        ASSERT(strlen(kdbc_error(conn)) > 0, "error message set");
    } else {
        /* Driver deferred error to execute time (Oracle, MSSQL ct_dynamic) */
        int rc = kdbc_execute_update_stmt(stmt);
        ASSERT(rc < 0, "execute should fail");
        kdbc_stmt_close(stmt);
    }
    kdbc_close(conn);
}

static void test_bind_out_of_range(void) {
    kdbc_conn *conn = open_db();
    drop_table(conn, "kdbc_bind");
    char ddl[128];
    snprintf(ddl, sizeof(ddl), "CREATE TABLE kdbc_bind (a %s)", int_type());
    exec_sql(conn, ddl);

    kdbc_stmt *stmt = kdbc_prepare(conn, "INSERT INTO kdbc_bind (a) VALUES (?)");
    ASSERT(stmt != NULL, "prepare");
    ASSERT(kdbc_bind_int(stmt, 0, 1) == KDBC_ERROR, "idx 0");
    ASSERT(kdbc_bind_int(stmt, 2, 1) == KDBC_ERROR, "idx 2");
    kdbc_stmt_close(stmt);

    drop_table(conn, "kdbc_bind");
    kdbc_close(conn);
}

/* ========================================================================
 * Tests: Error messages are set on failure
 * ======================================================================== */

static void test_error_messages(void) {
    kdbc_conn *conn = open_db();
    ASSERT(conn != NULL, "conn");

    /* Invalid SQL should set error on conn */
    kdbc_stmt *stmt = kdbc_prepare(conn, "THIS IS NOT VALID SQL AT ALL");
    if (!stmt) {
        /* Prepare failed (expected for most DBs) — error should be non-empty */
        ASSERT(strlen(kdbc_error(conn)) > 0, "prepare error message set");
    } else {
        /* Some DBs defer error to execute — execute should fail */
        int rc = kdbc_execute_update_stmt(stmt);
        if (rc < 0) {
            ASSERT(strlen(kdbc_stmt_error(stmt)) > 0, "execute error message set");
        }
        kdbc_stmt_close(stmt);
    }

    /* Bind out of range should set error on stmt */
    drop_table(conn, "kdbc_errt");
    char ddl[128];
    snprintf(ddl, sizeof(ddl), "CREATE TABLE kdbc_errt (a %s)", int_type());
    exec_sql(conn, ddl);
    stmt = kdbc_prepare(conn, "INSERT INTO kdbc_errt (a) VALUES (?)");
    if (stmt) {
        int rc = kdbc_bind_int(stmt, 99, 1);
        ASSERT(rc == KDBC_ERROR, "bind out of range");
        ASSERT(strlen(kdbc_stmt_error(stmt)) > 0, "bind error message set");
        kdbc_stmt_close(stmt);
    }

    drop_table(conn, "kdbc_errt");
    kdbc_close(conn);
}

/* ========================================================================
 * Tests: Savepoint validation (always runs, no DB needed)
 * ======================================================================== */

static void test_savepoint_validation(void) {
    kdbc_conn *conn = open_db();
    ASSERT(conn != NULL, "conn");
    kdbc_set_autocommit(conn, 0);

    ASSERT(kdbc_savepoint(conn, "sp1") == KDBC_OK, "valid sp1");
    if (kdbc_driver_supports_release_savepoint(g_driver))
        ASSERT(kdbc_release_savepoint(conn, "sp1") == KDBC_OK, "release sp1");

    /* Invalid names */
    ASSERT(kdbc_savepoint(conn, "") == KDBC_ERROR, "empty");
    ASSERT(kdbc_savepoint(conn, "1bad") == KDBC_ERROR, "digit start");
    ASSERT(kdbc_savepoint(conn, "sp; DROP TABLE t") == KDBC_ERROR, "injection");
    ASSERT(kdbc_savepoint(conn, "abcdefghijklmnopqrstuvwxyz12345") == KDBC_ERROR, "too long");

    kdbc_set_autocommit(conn, 1);
    kdbc_close(conn);
}

/* ========================================================================
 * Tests: SQL comment handling (SQLite only - uses string literal)
 * ======================================================================== */

static void test_param_in_string_literal(void) {
    kdbc_conn *conn = open_db();
    drop_table(conn, "kdbc_lit");
    char ddl[128];
    snprintf(ddl, sizeof(ddl), "CREATE TABLE kdbc_lit (val %s)", text_type());
    exec_sql(conn, ddl);

    /* ? inside string literal should NOT be a parameter */
    kdbc_stmt *stmt = kdbc_prepare(conn, "INSERT INTO kdbc_lit (val) VALUES ('is this a ?')");
    ASSERT(stmt != NULL, "prepare with ? in string");
    int rc = kdbc_execute_update_stmt(stmt);
    ASSERT(rc >= 0, "execute");
    kdbc_stmt_close(stmt);

    stmt = kdbc_prepare(conn, "SELECT val FROM kdbc_lit");
    kdbc_result *rs = kdbc_execute_query_stmt(stmt);
    ASSERT(kdbc_next(rs), "has row");
    ASSERT_EQ_STR(kdbc_get_string(rs, 1), "is this a ?", "literal ?");
    kdbc_result_close(rs);
    kdbc_stmt_close(stmt);

    drop_table(conn, "kdbc_lit");
    kdbc_close(conn);
}

/* ========================================================================
 * Tests: Direct vs Prepared execution (all 4 combinations)
 * ======================================================================== */

static void test_direct_vs_prepared(void) {
    kdbc_conn *conn = open_db();
    ASSERT(conn != NULL, "conn");

    /* Setup via direct DDL */
    drop_table(conn, "kdbc_dp");
    char ddl[256];
    snprintf(ddl, sizeof(ddl), "CREATE TABLE kdbc_dp (id %s, name %s, val %s)",
             int_type(), text_type(), float_type());
    ASSERT(kdbc_execute_update(conn, ddl) >= 0, "direct DDL create");

    /* 1. Direct UPDATE (no params) */
    char ins[256];
    snprintf(ins, sizeof(ins), "INSERT INTO kdbc_dp (id, name, val) VALUES (1, 'Alice', 3.14)");
    int rc = kdbc_execute_update(conn, ins);
    ASSERT(rc >= 0, "direct insert");

    /* 2. Direct SELECT (no params) */
    kdbc_result *rs = kdbc_execute_query(conn, "SELECT id, name, val FROM kdbc_dp");
    ASSERT(rs != NULL, "direct query");
    ASSERT(kdbc_next(rs) == 1, "direct query has row");
    ASSERT_EQ_INT(kdbc_get_long(rs, 1), 1, "direct query id");
    ASSERT_EQ_STR(kdbc_get_string(rs, 2), "Alice", "direct query name");
    ASSERT(kdbc_next(rs) == 0, "direct query no more");
    kdbc_result_close(rs); /* auto-closes owned stmt */

    /* 3. Prepared UPDATE (with params) */
    kdbc_stmt *stmt = kdbc_prepare(conn, "INSERT INTO kdbc_dp (id, name, val) VALUES (?, ?, ?)");
    ASSERT(stmt != NULL, "prepared insert");
    kdbc_bind_int(stmt, 1, 2);
    kdbc_bind_string(stmt, 2, "Bob");
    kdbc_bind_double(stmt, 3, 2.72);
    rc = kdbc_execute_update_stmt(stmt);
    ASSERT_EQ_INT(rc, 1, "prepared insert rows");
    kdbc_stmt_close(stmt);

    /* 4. Prepared SELECT (with params) */
    stmt = kdbc_prepare(conn, "SELECT name, val FROM kdbc_dp WHERE id = ?");
    ASSERT(stmt != NULL, "prepared select");
    kdbc_bind_int(stmt, 1, 2);
    rs = kdbc_execute_query_stmt(stmt);
    ASSERT(rs != NULL, "prepared query");
    ASSERT(kdbc_next(rs) == 1, "prepared query has row");
    ASSERT_EQ_STR(kdbc_get_string(rs, 1), "Bob", "prepared query name");
    ASSERT(kdbc_next(rs) == 0, "prepared query no more");
    kdbc_result_close(rs);
    kdbc_stmt_close(stmt);

    /* Verify both rows exist via direct query */
    rs = kdbc_execute_query(conn, "SELECT COUNT(*) FROM kdbc_dp");
    ASSERT(rs != NULL, "count query");
    ASSERT(kdbc_next(rs), "count has row");
    ASSERT_EQ_INT(kdbc_get_long(rs, 1), 2, "2 rows total");
    kdbc_result_close(rs);

    /* Direct DELETE (no params) */
    ASSERT(kdbc_execute_update(conn, "DELETE FROM kdbc_dp WHERE id = 1") >= 0, "direct delete");

    /* Prepared DELETE (with params) */
    stmt = kdbc_prepare(conn, "DELETE FROM kdbc_dp WHERE id = ?");
    kdbc_bind_int(stmt, 1, 2);
    ASSERT_EQ_INT(kdbc_execute_update_stmt(stmt), 1, "prepared delete");
    kdbc_stmt_close(stmt);

    /* Verify empty */
    rs = kdbc_execute_query(conn, "SELECT COUNT(*) FROM kdbc_dp");
    ASSERT(rs != NULL, "final count");
    ASSERT(kdbc_next(rs), "final count row");
    ASSERT_EQ_INT(kdbc_get_long(rs, 1), 0, "0 rows after delete");
    kdbc_result_close(rs);

    drop_table(conn, "kdbc_dp");
    kdbc_close(conn);
}

/* ========================================================================
 * Tests: NULL pointer safety (no DB needed)
 * ======================================================================== */

static void test_null_safety(void) {
    kdbc_close(NULL);
    kdbc_stmt_close(NULL);
    kdbc_result_close(NULL);
    ASSERT_EQ_INT(kdbc_col_count(NULL), 0, "null rs");
    ASSERT(kdbc_col_name(NULL, 1) == NULL, "null rs name");
    ASSERT(kdbc_error(NULL) != NULL, "null conn err");
    ASSERT(kdbc_stmt_error(NULL) != NULL, "null stmt err");
}

/* ========================================================================
 * Main
 * ======================================================================== */

static kdbc_driver parse_driver(const char *name) {
    if (strcmp(name, "sqlite") == 0)      return KDBC_SQLITE;
    if (strcmp(name, "postgresql") == 0)   return KDBC_POSTGRES;
    if (strcmp(name, "postgres") == 0)     return KDBC_POSTGRES;
    if (strcmp(name, "mariadb") == 0)      return KDBC_MARIADB;
    if (strcmp(name, "mysql") == 0)        return KDBC_MARIADB;
    if (strcmp(name, "oracle") == 0)       return KDBC_ORACLE;
    if (strcmp(name, "mssql") == 0)        return KDBC_MSSQL;
    if (strcmp(name, "sqlserver") == 0)    return KDBC_MSSQL;
    fprintf(stderr, "Unknown driver: %s\n", name);
    exit(1);
}

int main(int argc, char **argv) {
    /* Disable stdout buffering for reliable crash output */
    setvbuf(stdout, NULL, _IONBF, 0);

    /* Parse arguments */
    if (argc >= 2) g_driver   = parse_driver(argv[1]);
    if (argc >= 3) g_url      = argv[2];
    if (argc >= 4) g_user     = argv[3];
    if (argc >= 5) g_password = argv[4];

    printf("KDBC Native Test Suite\n");
    printf("======================\n");
    printf("Driver:   %s\n", kdbc_driver_name(g_driver));
    printf("URL:      %s\n", g_url);
    printf("User:     %s\n", g_user ? g_user : "(none)");
    printf("\n");

    printf("Driver Discovery:\n");
    RUN_TEST(test_driver_available);
    RUN_TEST(test_driver_capabilities);

    printf("\nConnection & Metadata:\n");
    RUN_TEST(test_connect);
    RUN_TEST(test_connect_failure);

    printf("\nDDL & CRUD:\n");
    RUN_TEST(test_create_and_read);
    RUN_TEST(test_update);
    RUN_TEST(test_delete);

    printf("\nTypes:\n");
    RUN_TEST(test_integer_types);
    RUN_TEST(test_double_type);
    RUN_TEST(test_bool_type);
    RUN_TEST(test_string_type);
    RUN_TEST(test_blob_type);
    RUN_TEST(test_null_handling);

    printf("\nDate/Time:\n");
    RUN_TEST(test_timestamp);
    RUN_TEST(test_date);
    RUN_TEST(test_time);

    printf("\nTransactions:\n");
    RUN_TEST(test_transaction_commit);
    RUN_TEST(test_transaction_rollback);
    RUN_TEST(test_savepoint);

    printf("\nGenerated Keys:\n");
    RUN_TEST(test_generated_keys);
    RUN_TEST(test_generated_keys_string_pk);

    printf("\nColumn Metadata:\n");
    RUN_TEST(test_column_names);

    printf("\nMultiple Rows:\n");
    RUN_TEST(test_multiple_rows);

    printf("\nBatch Execution:\n");
    RUN_TEST(test_batch_insert);

    printf("\nError Handling:\n");
    RUN_TEST(test_invalid_sql);
    RUN_TEST(test_bind_out_of_range);
    RUN_TEST(test_error_messages);

    printf("\nSavepoint Validation:\n");
    RUN_TEST(test_savepoint_validation);

    printf("\nSQL Parsing:\n");
    RUN_TEST(test_param_in_string_literal);

    printf("\nNull Safety:\n");
    printf("\nDirect vs Prepared:\n");
    RUN_TEST(test_direct_vs_prepared);

    printf("\nNull Safety:\n");
    RUN_TEST(test_null_safety);

    printf("\n======================\n");
    printf("Results: %d/%d passed", tests_passed, tests_run);
    if (tests_skipped > 0) printf(", %d skipped", tests_skipped);
    if (tests_failed > 0)  printf(", %d FAILED", tests_failed);
    printf("\n");

    return tests_failed > 0 ? 1 : 0;
}
