/*
 * KDBC Native - SQLite driver
 *
 * Uses dlopen/dlsym to load libsqlite3 at runtime.
 * If the library is not available, the driver reports as unavailable
 * but doesn't prevent the rest of KDBC from working.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
#include "../include/kdbc_internal.h"
#include "../include/kdbc_dl.h"
#include <sqlite3.h>
#include <pthread.h>

/* ========================================================================
 * Function pointer types
 * ======================================================================== */

typedef int    (*fn_sqlite3_open_v2)(const char *, sqlite3 **, int, const char *);
typedef int    (*fn_sqlite3_close)(sqlite3 *);
typedef int    (*fn_sqlite3_prepare_v2)(sqlite3 *, const char *, int, sqlite3_stmt **, const char **);
typedef int    (*fn_sqlite3_step)(sqlite3_stmt *);
typedef int    (*fn_sqlite3_finalize)(sqlite3_stmt *);
typedef int    (*fn_sqlite3_reset)(sqlite3_stmt *);
typedef int    (*fn_sqlite3_clear_bindings)(sqlite3_stmt *);
typedef int    (*fn_sqlite3_column_count)(sqlite3_stmt *);
typedef const char *(*fn_sqlite3_column_name)(sqlite3_stmt *, int);
typedef int    (*fn_sqlite3_column_type)(sqlite3_stmt *, int);
typedef int    (*fn_sqlite3_column_int)(sqlite3_stmt *, int);
typedef long long (*fn_sqlite3_column_int64)(sqlite3_stmt *, int);
typedef double (*fn_sqlite3_column_double)(sqlite3_stmt *, int);
typedef const unsigned char *(*fn_sqlite3_column_text)(sqlite3_stmt *, int);
typedef const void *(*fn_sqlite3_column_blob)(sqlite3_stmt *, int);
typedef int    (*fn_sqlite3_column_bytes)(sqlite3_stmt *, int);
typedef int    (*fn_sqlite3_bind_null)(sqlite3_stmt *, int);
typedef int    (*fn_sqlite3_bind_int)(sqlite3_stmt *, int, int);
typedef int    (*fn_sqlite3_bind_int64)(sqlite3_stmt *, int, long long);
typedef int    (*fn_sqlite3_bind_double)(sqlite3_stmt *, int, double);
typedef int    (*fn_sqlite3_bind_text)(sqlite3_stmt *, int, const char *, int, void(*)(void*));
typedef int    (*fn_sqlite3_bind_blob)(sqlite3_stmt *, int, const void *, int, void(*)(void*));
typedef int    (*fn_sqlite3_changes)(sqlite3 *);
typedef long long (*fn_sqlite3_last_insert_rowid)(sqlite3 *);
typedef const char *(*fn_sqlite3_errmsg)(sqlite3 *);
typedef const char *(*fn_sqlite3_libversion)(void);
typedef int    (*fn_sqlite3_libversion_number)(void);
typedef int    (*fn_sqlite3_exec)(sqlite3 *, const char *, void *, void *, char **);
typedef void   (*fn_sqlite3_interrupt)(sqlite3 *);
typedef void   (*fn_sqlite3_free)(void *);
typedef int    (*fn_sqlite3_get_autocommit)(sqlite3 *);

/* ========================================================================
 * Loaded function pointers
 * ======================================================================== */

static kdbc_lib_handle lib_handle = NULL;

static fn_sqlite3_open_v2           p_open_v2;
static fn_sqlite3_close             p_close;
static fn_sqlite3_prepare_v2        p_prepare_v2;
static fn_sqlite3_step              p_step;
static fn_sqlite3_finalize          p_finalize;
static fn_sqlite3_reset             p_reset;
static fn_sqlite3_clear_bindings    p_clear_bindings;
static fn_sqlite3_column_count      p_column_count;
static fn_sqlite3_column_name       p_column_name;
static fn_sqlite3_column_type       p_column_type;
static fn_sqlite3_column_int        p_column_int;
static fn_sqlite3_column_int64      p_column_int64;
static fn_sqlite3_column_double     p_column_double;
static fn_sqlite3_column_text       p_column_text;
static fn_sqlite3_column_blob       p_column_blob;
static fn_sqlite3_column_bytes      p_column_bytes;
static fn_sqlite3_bind_null         p_bind_null;
static fn_sqlite3_bind_int          p_bind_int;
static fn_sqlite3_bind_int64        p_bind_int64;
static fn_sqlite3_bind_double       p_bind_double;
static fn_sqlite3_bind_text         p_bind_text;
static fn_sqlite3_bind_blob         p_bind_blob;
static fn_sqlite3_changes           p_changes;
static fn_sqlite3_last_insert_rowid p_last_insert_rowid;
static fn_sqlite3_errmsg            p_errmsg;
static fn_sqlite3_libversion        p_libversion;
static fn_sqlite3_libversion_number p_libversion_number;
static fn_sqlite3_exec              p_exec;
static fn_sqlite3_interrupt         p_interrupt;
static fn_sqlite3_free              p_sq_free;
static fn_sqlite3_get_autocommit   p_get_autocommit;

/* ========================================================================
 * Driver-specific result set structure
 * ======================================================================== */

typedef struct {
    sqlite3_stmt *stmt;
    sqlite3      *db;
    int           col_count;
    int           owns_stmt; /* should finalize on close? */
} sq_result;

/* ========================================================================
 * Library loading
 * ======================================================================== */

#define LOAD_SYM(name) do { \
    p_##name = (fn_sqlite3_##name)kdbc_dl_sym(lib_handle, "sqlite3_" #name); \
    if (!p_##name) { kdbc_dl_close(lib_handle); lib_handle = NULL; return; } \
} while (0)

static pthread_once_t sq_load_once = PTHREAD_ONCE_INIT;
static int            sq_load_ok   = 0;

static void sq_load_impl(void) {
    /* Try platform-specific library names */
    lib_handle = kdbc_dl_open(KDBC_LIBNAME("sqlite3", "0"), RTLD_LAZY);
    if (!lib_handle) lib_handle = kdbc_dl_open(KDBC_LIBNAME_NOVER("sqlite3"), RTLD_LAZY);
    if (!lib_handle) return;

    LOAD_SYM(open_v2);
    LOAD_SYM(close);
    LOAD_SYM(prepare_v2);
    LOAD_SYM(step);
    LOAD_SYM(finalize);
    LOAD_SYM(reset);
    LOAD_SYM(clear_bindings);
    LOAD_SYM(column_count);
    LOAD_SYM(column_name);
    LOAD_SYM(column_type);
    LOAD_SYM(column_int);
    LOAD_SYM(column_int64);
    LOAD_SYM(column_double);
    LOAD_SYM(column_text);
    LOAD_SYM(column_blob);
    LOAD_SYM(column_bytes);
    LOAD_SYM(bind_null);
    LOAD_SYM(bind_int);
    LOAD_SYM(bind_int64);
    LOAD_SYM(bind_double);
    LOAD_SYM(bind_text);
    LOAD_SYM(bind_blob);
    LOAD_SYM(changes);
    LOAD_SYM(last_insert_rowid);
    LOAD_SYM(errmsg);
    LOAD_SYM(libversion);
    LOAD_SYM(libversion_number);
    LOAD_SYM(exec);
    LOAD_SYM(interrupt);
    /* sqlite3_free has a different storage-class name in our macro scheme
     * (p_free would collide with POSIX free), so load it manually. */
    p_sq_free = (fn_sqlite3_free)kdbc_dl_sym(lib_handle, "sqlite3_free");
    if (!p_sq_free) { kdbc_dl_close(lib_handle); lib_handle = NULL; return; }
    p_get_autocommit = (fn_sqlite3_get_autocommit)kdbc_dl_sym(lib_handle, "sqlite3_get_autocommit");
    if (!p_get_autocommit) { kdbc_dl_close(lib_handle); lib_handle = NULL; return; }

    sq_load_ok = 1;
}

static int sq_load(void) {
    pthread_once(&sq_load_once, sq_load_impl);
    return sq_load_ok;
}

static int sq_loaded(void) {
    return lib_handle != NULL;
}

/* ========================================================================
 * Connection
 * ======================================================================== */

static void *sq_connect(const char *url, const char *user, const char *password,
                        char *err, size_t err_size) {
    (void)user; (void)password; /* SQLite doesn't use credentials */

    sqlite3 *db = NULL;
    int flags = SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_URI;
    int rc = p_open_v2(url, &db, flags, NULL);
    if (rc != SQLITE_OK) {
        if (db) {
            snprintf(err, err_size, "SQLite open failed: %s", p_errmsg(db));
            p_close(db);
        } else {
            snprintf(err, err_size, "SQLite open failed: error code %d", rc);
        }
        return NULL;
    }
    return db;
}

static void sq_close(void *native) {
    if (native) p_close((sqlite3 *)native);
}

/* sqlite3_interrupt is explicitly documented as safe to call from any
 * thread at any time; it sets a flag that sqlite3_step observes at its
 * next opportunity and returns SQLITE_INTERRUPT. */
static int sq_cancel(kdbc_conn *conn) {
    if (!conn || !conn->native) return KDBC_ERROR;
    p_interrupt((sqlite3 *)conn->native);
    return KDBC_OK;
}

/* ========================================================================
 * Transactions
 * ======================================================================== */

static int sq_exec_sql(kdbc_conn *conn, const char *sql) {
    sqlite3 *db = (sqlite3 *)conn->native;
    char *errmsg = NULL;
    int rc = p_exec(db, sql, NULL, NULL, &errmsg);
    if (rc != SQLITE_OK) {
        CONN_ERR(conn, "SQLite: %s", errmsg ? errmsg : "unknown error");
        if (errmsg) p_sq_free(errmsg);
        return KDBC_ERROR;
    }
    /* Success path also must free errmsg if sqlite3_exec allocated one
     * (it never should on OK, but the API contract is symmetric). */
    if (errmsg) p_sq_free(errmsg);
    return KDBC_OK;
}

static int sq_set_autocommit(kdbc_conn *conn, int enabled) {
    sqlite3 *db = (sqlite3 *)conn->native;
    if (enabled && !conn->autocommit) {
        /* Was in manual mode — only COMMIT if a transaction is actually open.
         * After commit()/rollback() SQLite is already in autocommit mode,
         * so the COMMIT here would be redundant (and harmless but wasteful). */
        if (!p_get_autocommit(db))
            return sq_exec_sql(conn, "COMMIT");
    } else if (!enabled && conn->autocommit) {
        /* Entering manual mode, begin transaction */
        return sq_exec_sql(conn, "BEGIN");
    }
    return KDBC_OK;
}

static int sq_commit(kdbc_conn *conn) {
    return sq_exec_sql(conn, "COMMIT");
}

static int sq_rollback(kdbc_conn *conn) {
    return sq_exec_sql(conn, "ROLLBACK");
}

/* ========================================================================
 * Metadata
 * ======================================================================== */

static void sq_get_product_name(void *native, char *buf, size_t sz) {
    (void)native;
    snprintf(buf, sz, "SQLite");
}

static void sq_get_product_version(void *native, char *buf, size_t sz) {
    (void)native;
    snprintf(buf, sz, "%s", p_libversion());
}

static int sq_get_major_version(void *native) {
    (void)native;
    return p_libversion_number() / 1000000;
}

static int sq_get_minor_version(void *native) {
    (void)native;
    return (p_libversion_number() % 1000000) / 1000;
}

/* ========================================================================
 * Statement preparation
 * ======================================================================== */

static void *sq_prepare(kdbc_conn *conn, const char *native_sql,
                        const char **ret_cols, int n_ret_cols,
                        int generated_keys_requested,
                        char *err, size_t err_size) {
    (void)ret_cols; (void)n_ret_cols; (void)generated_keys_requested;
    /* SQLite uses last_insert_rowid — no per-statement handling needed. */

    sqlite3 *db = (sqlite3 *)conn->native;
    sqlite3_stmt *stmt = NULL;
    int rc = p_prepare_v2(db, native_sql, -1, &stmt, NULL);
    if (rc != SQLITE_OK) {
        snprintf(err, err_size, "SQLite prepare: %s", p_errmsg(db));
        return NULL;
    }
    return stmt;
}

static void sq_stmt_close(void *native_stmt, void *native_conn) {
    (void)native_conn;
    if (native_stmt) p_finalize((sqlite3_stmt *)native_stmt);
}

/* ========================================================================
 * Parameter binding
 * ======================================================================== */

static int sq_bind_null(kdbc_stmt *stmt, int idx) {
    int rc = p_bind_null((sqlite3_stmt *)stmt->native, idx);
    if (rc != SQLITE_OK) {
        STMT_ERR(stmt, "SQLite bind_null: %s", p_errmsg((sqlite3 *)stmt->conn->native));
        return KDBC_ERROR;
    }
    return KDBC_OK;
}

static int sq_bind_int(kdbc_stmt *stmt, int idx, int val) {
    int rc = p_bind_int((sqlite3_stmt *)stmt->native, idx, val);
    if (rc != SQLITE_OK) {
        STMT_ERR(stmt, "SQLite bind_int: %s", p_errmsg((sqlite3 *)stmt->conn->native));
        return KDBC_ERROR;
    }
    return KDBC_OK;
}

static int sq_bind_long(kdbc_stmt *stmt, int idx, int64_t val) {
    int rc = p_bind_int64((sqlite3_stmt *)stmt->native, idx, val);
    if (rc != SQLITE_OK) {
        STMT_ERR(stmt, "SQLite bind_long: %s", p_errmsg((sqlite3 *)stmt->conn->native));
        return KDBC_ERROR;
    }
    return KDBC_OK;
}

static int sq_bind_double(kdbc_stmt *stmt, int idx, double val) {
    int rc = p_bind_double((sqlite3_stmt *)stmt->native, idx, val);
    if (rc != SQLITE_OK) {
        STMT_ERR(stmt, "SQLite bind_double: %s", p_errmsg((sqlite3 *)stmt->conn->native));
        return KDBC_ERROR;
    }
    return KDBC_OK;
}

static int sq_bind_string(kdbc_stmt *stmt, int idx, const char *val) {
    int rc = p_bind_text((sqlite3_stmt *)stmt->native, idx, val, -1, SQLITE_TRANSIENT);
    if (rc != SQLITE_OK) {
        STMT_ERR(stmt, "SQLite bind_string: %s", p_errmsg((sqlite3 *)stmt->conn->native));
        return KDBC_ERROR;
    }
    return KDBC_OK;
}

static int sq_bind_blob(kdbc_stmt *stmt, int idx, const void *data, size_t len) {
    int rc = p_bind_blob((sqlite3_stmt *)stmt->native, idx, data, (int)len, SQLITE_TRANSIENT);
    if (rc != SQLITE_OK) {
        STMT_ERR(stmt, "SQLite bind_blob: %s", p_errmsg((sqlite3 *)stmt->conn->native));
        return KDBC_ERROR;
    }
    return KDBC_OK;
}

/* Date/time binding: SQLite stores as ISO-8601 text */
static int sq_bind_timestamp(kdbc_stmt *stmt, int idx,
                             int year, int month, int day,
                             int hour, int minute, int second, int usec) {
    char buf[32];
    if (usec > 0)
        snprintf(buf, sizeof(buf), "%04d-%02d-%02d %02d:%02d:%02d.%06d",
                 year, month, day, hour, minute, second, usec);
    else
        snprintf(buf, sizeof(buf), "%04d-%02d-%02d %02d:%02d:%02d",
                 year, month, day, hour, minute, second);
    return sq_bind_string(stmt, idx, buf);
}

static int sq_bind_date(kdbc_stmt *stmt, int idx, int year, int month, int day) {
    char buf[16];
    snprintf(buf, sizeof(buf), "%04d-%02d-%02d", year, month, day);
    return sq_bind_string(stmt, idx, buf);
}

static int sq_bind_time(kdbc_stmt *stmt, int idx,
                        int hour, int minute, int second, int usec) {
    char buf[20];
    if (usec > 0)
        snprintf(buf, sizeof(buf), "%02d:%02d:%02d.%06d", hour, minute, second, usec);
    else
        snprintf(buf, sizeof(buf), "%02d:%02d:%02d", hour, minute, second);
    return sq_bind_string(stmt, idx, buf);
}

/* ========================================================================
 * Execution
 * ======================================================================== */

static int sq_execute_update(kdbc_stmt *stmt) {
    sqlite3_stmt *s = (sqlite3_stmt *)stmt->native;
    sqlite3 *db = (sqlite3 *)stmt->conn->native;

    /* Snapshot the current rowid BEFORE stepping so we can tell whether this
     * statement actually inserted a row. sqlite3_last_insert_rowid is a
     * connection-level counter that only changes on a successful INSERT, so
     * comparing before/after lets us distinguish INSERT from UPDATE/DELETE
     * without parsing the SQL. */
    long long rowid_before =
        (stmt->generated_keys_requested || stmt->ret_col_names)
            ? p_last_insert_rowid(db) : 0;

    int rc = p_step(s);
    if (rc != SQLITE_DONE && rc != SQLITE_ROW) {
        STMT_ERR(stmt, "SQLite execute: %s", p_errmsg(db));
        p_reset(s);
        return KDBC_ERROR;
    }

    int changes = p_changes(db);

    /* Capture generated key only if the rowid actually advanced (i.e., this
     * was an INSERT). Prevents returning stale rowids after UPDATE/DELETE. */
    if (stmt->generated_keys_requested || stmt->ret_col_names) {
        long long rowid_after = p_last_insert_rowid(db);
        if (rowid_after > rowid_before) {
            stmt->generated_key = rowid_after;
            stmt->has_generated_key = 1;
        }
    }

    p_reset(s);
    return changes;
}

static void *sq_execute_query(kdbc_stmt *stmt, int *out_col_count,
                              char *err, size_t err_size) {
    sqlite3_stmt *s = (sqlite3_stmt *)stmt->native;

    int cc = p_column_count(s);
    *out_col_count = cc;

    sq_result *sr = (sq_result *)calloc(1, sizeof(sq_result));
    if (!sr) {
        snprintf(err, err_size, "Out of memory");
        return NULL;
    }

    sr->stmt = s;
    sr->db = (sqlite3 *)stmt->conn->native;
    sr->col_count = cc;
    sr->owns_stmt = 0; /* stmt lifecycle managed by kdbc_stmt */

    return sr;
}

static int sq_get_generated_key(kdbc_stmt *stmt, int64_t *out_key) {
    sqlite3 *db = (sqlite3 *)stmt->conn->native;
    long long rowid = p_last_insert_rowid(db);
    if (rowid > 0) {
        *out_key = rowid;
        return KDBC_OK;
    }
    return KDBC_ERROR;
}

/* ========================================================================
 * Result set
 * ======================================================================== */

static int sq_rs_next(kdbc_result *rs) {
    sq_result *sr = (sq_result *)rs->native;
    int rc = p_step(sr->stmt);
    if (rc == SQLITE_ROW) return 1;
    if (rc == SQLITE_DONE) return 0;
    RS_ERR(rs, "SQLite fetch: %s", p_errmsg(sr->db));
    return KDBC_ERROR;
}

static const char *sq_rs_col_name(void *native_rs, int col) {
    sq_result *sr = (sq_result *)native_rs;
    return p_column_name(sr->stmt, col - 1); /* convert to 0-based */
}

static int sq_rs_is_null(kdbc_result *rs, int col) {
    sq_result *sr = (sq_result *)rs->native;
    int type = p_column_type(sr->stmt, col - 1);
    rs->last_null = (type == SQLITE_NULL);
    return rs->last_null;
}

static int64_t sq_rs_get_long(kdbc_result *rs, int col) {
    sq_result *sr = (sq_result *)rs->native;
    int idx = col - 1;
    int type = p_column_type(sr->stmt, idx);
    rs->last_null = (type == SQLITE_NULL);
    if (rs->last_null) return 0;

    if (type == SQLITE_INTEGER) {
        return p_column_int64(sr->stmt, idx);
    } else if (type == SQLITE_FLOAT) {
        return (int64_t)p_column_double(sr->stmt, idx);
    } else {
        /* Text - try to parse */
        const char *txt = (const char *)p_column_text(sr->stmt, idx);
        if (txt) {
            long long v = 0;
            sscanf(txt, "%lld", &v);
            return v;
        }
        return 0;
    }
}

static double sq_rs_get_double(kdbc_result *rs, int col) {
    sq_result *sr = (sq_result *)rs->native;
    int idx = col - 1;
    int type = p_column_type(sr->stmt, idx);
    rs->last_null = (type == SQLITE_NULL);
    if (rs->last_null) return 0.0;

    if (type == SQLITE_FLOAT) {
        return p_column_double(sr->stmt, idx);
    } else if (type == SQLITE_INTEGER) {
        return (double)p_column_int64(sr->stmt, idx);
    } else {
        const char *txt = (const char *)p_column_text(sr->stmt, idx);
        return txt ? strtod(txt, NULL) : 0.0;
    }
}

static const char *sq_rs_get_string(kdbc_result *rs, int col) {
    sq_result *sr = (sq_result *)rs->native;
    int idx = col - 1;
    int type = p_column_type(sr->stmt, idx);
    rs->last_null = (type == SQLITE_NULL);
    if (rs->last_null) return NULL;

    if (type == SQLITE_TEXT || type == SQLITE_INTEGER || type == SQLITE_FLOAT) {
        return (const char *)p_column_text(sr->stmt, idx);
    } else if (type == SQLITE_BLOB) {
        /* Hex-encode the raw bytes into the per-result string buffer.
         * Calling sqlite3_column_text on a BLOB would interpret bytes as
         * UTF-8 and truncate at the first NUL — lossy for binary data.
         * Hex is the only lossless textual representation. Callers that
         * actually want the raw bytes should use rs_get_blob. */
        const unsigned char *blob = (const unsigned char *)p_column_blob(sr->stmt, idx);
        int len = p_column_bytes(sr->stmt, idx);
        if (!blob || len <= 0) return "";
        if (kdbc_ensure_strbuf(rs, (size_t)len * 2 + 1) != 0) return NULL;
        static const char hexd[] = "0123456789abcdef";
        for (int i = 0; i < len; i++) {
            rs->str_buf[i * 2]     = hexd[blob[i] >> 4];
            rs->str_buf[i * 2 + 1] = hexd[blob[i] & 0x0F];
        }
        rs->str_buf[len * 2] = '\0';
        return rs->str_buf;
    }
    return NULL;
}

static const void *sq_rs_get_blob(kdbc_result *rs, int col, size_t *out_len) {
    sq_result *sr = (sq_result *)rs->native;
    int idx = col - 1;
    int type = p_column_type(sr->stmt, idx);
    rs->last_null = (type == SQLITE_NULL);
    if (rs->last_null) { *out_len = 0; return NULL; }

    int bytes = p_column_bytes(sr->stmt, idx);
    const void *blob = p_column_blob(sr->stmt, idx);
    *out_len = (size_t)bytes;
    return blob;
}

/* Date/time retrieval: SQLite stores as text, parse ISO-8601 */
static int sq_rs_get_timestamp(kdbc_result *rs, int col,
                               int *year, int *month, int *day,
                               int *hour, int *minute, int *second, int *usec) {
    const char *txt = sq_rs_get_string(rs, col);
    if (!txt) { RS_ERR(rs, "SQLite: NULL timestamp value"); return KDBC_ERROR; }
    *usec = 0;
    int n = sscanf(txt, "%d-%d-%d %d:%d:%d.%d",
                   year, month, day, hour, minute, second, usec);
    if (n < 6)
        n = sscanf(txt, "%d-%d-%dT%d:%d:%d.%d",
                   year, month, day, hour, minute, second, usec);
    if (n >= 6) return KDBC_OK;
    RS_ERR(rs, "SQLite: cannot parse timestamp '%s'", txt);
    return KDBC_ERROR;
}

static int sq_rs_get_date(kdbc_result *rs, int col,
                          int *year, int *month, int *day) {
    const char *txt = sq_rs_get_string(rs, col);
    if (!txt) { RS_ERR(rs, "SQLite: NULL date value"); return KDBC_ERROR; }
    if (sscanf(txt, "%d-%d-%d", year, month, day) >= 3) return KDBC_OK;
    RS_ERR(rs, "SQLite: cannot parse date '%s'", txt);
    return KDBC_ERROR;
}

static int sq_rs_get_time(kdbc_result *rs, int col,
                          int *hour, int *minute, int *second, int *usec) {
    const char *txt = sq_rs_get_string(rs, col);
    if (!txt) { RS_ERR(rs, "SQLite: NULL time value"); return KDBC_ERROR; }
    *usec = 0;
    int n = sscanf(txt, "%d:%d:%d.%d", hour, minute, second, usec);
    if (n >= 3) return KDBC_OK;
    RS_ERR(rs, "SQLite: cannot parse time '%s'", txt);
    return KDBC_ERROR;
}

static void sq_rs_close(void *native_rs) {
    sq_result *sr = (sq_result *)native_rs;
    if (sr) {
        /* Reset the statement (don't finalize - that's done by stmt_close) */
        if (sr->stmt) p_reset(sr->stmt);
        free(sr);
    }
}

static int sq_stmt_reset(void *native_stmt) {
    sqlite3_stmt *s = (sqlite3_stmt *)native_stmt;
    p_reset(s);
    p_clear_bindings(s);
    return KDBC_OK;
}

/* ========================================================================
 * Driver vtable
 * ======================================================================== */

static const kdbc_driver_vtable sqlite_vtable = {
    .name               = "SQLite",
    .gk_strategy        = KDBC_GK_BY_INDEX,
    .supports_release_savepoint = 1,
    .load               = sq_load,
    .loaded             = sq_loaded,
    .connect            = sq_connect,
    .close              = sq_close,
    .cancel             = sq_cancel,
    .exec_direct        = NULL,
    .query_direct       = NULL,
    .set_autocommit     = sq_set_autocommit,
    .commit             = sq_commit,
    .rollback           = sq_rollback,
    .get_product_name   = sq_get_product_name,
    .get_product_version = sq_get_product_version,
    .get_major_version  = sq_get_major_version,
    .get_minor_version  = sq_get_minor_version,
    .prepare            = sq_prepare,
    .stmt_close         = sq_stmt_close,
    .bind_null          = sq_bind_null,
    .bind_int           = sq_bind_int,
    .bind_long          = sq_bind_long,
    .bind_double        = sq_bind_double,
    .bind_string        = sq_bind_string,
    .bind_blob          = sq_bind_blob,
    .bind_timestamp     = sq_bind_timestamp,
    .bind_date          = sq_bind_date,
    .bind_time          = sq_bind_time,
    .execute_update     = sq_execute_update,
    .execute_query      = sq_execute_query,
    .get_generated_key  = sq_get_generated_key,
    .rs_next            = sq_rs_next,
    .rs_col_name        = sq_rs_col_name,
    .rs_col_label       = NULL, /* SQLite: col_name == col_label */
    .rs_is_null         = sq_rs_is_null,
    .rs_get_long        = sq_rs_get_long,
    .rs_get_double      = sq_rs_get_double,
    .rs_get_string      = sq_rs_get_string,
    .rs_get_blob        = sq_rs_get_blob,
    .rs_get_timestamp   = sq_rs_get_timestamp,
    .rs_get_date        = sq_rs_get_date,
    .rs_get_time        = sq_rs_get_time,
    .rs_close           = sq_rs_close,
    .stmt_reset         = sq_stmt_reset,
    .conn_gk_strategy   = NULL,
    .prepare_call       = NULL,
    .call_execute       = NULL,
    .call_get_out       = NULL,
};

void kdbc_register_sqlite(void) {
    kdbc_register_driver(KDBC_SQLITE, &sqlite_vtable);
}
