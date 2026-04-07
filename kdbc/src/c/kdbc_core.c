/*
 * KDBC Native - Core dispatch layer
 *
 * Routes all public API calls to the appropriate driver vtable.
 * Manages connection/statement/result lifecycle.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
#include "include/kdbc_internal.h"
#include <stdarg.h>
#include <pthread.h>

/* ========================================================================
 * Driver registry
 * ======================================================================== */

const kdbc_driver_vtable *kdbc_drivers[KDBC_DRIVER_COUNT] = { NULL };
static pthread_once_t kdbc_init_once = PTHREAD_ONCE_INIT;

void kdbc_register_driver(kdbc_driver id, const kdbc_driver_vtable *vt) {
    if (id >= 0 && id < KDBC_DRIVER_COUNT)
        kdbc_drivers[id] = vt;
}

static void kdbc_init_impl(void) {
    kdbc_register_sqlite();
    kdbc_register_postgres();
    kdbc_register_mariadb();
    kdbc_register_oracle();
    kdbc_register_mssql();
}

void kdbc_init(void) {
    pthread_once(&kdbc_init_once, kdbc_init_impl);
}

static const kdbc_driver_vtable *get_vt(kdbc_driver d) {
    kdbc_init();
    if (d < 0 || d >= KDBC_DRIVER_COUNT) return NULL;
    return kdbc_drivers[d];
}

/* ========================================================================
 * Driver discovery
 * ======================================================================== */

int kdbc_driver_available(kdbc_driver driver) {
    const kdbc_driver_vtable *vt = get_vt(driver);
    if (!vt) return 0;
    if (vt->loaded()) return 1;
    return vt->load();
}

const char *kdbc_driver_name(kdbc_driver driver) {
    const kdbc_driver_vtable *vt = get_vt(driver);
    return vt ? vt->name : "Unknown";
}

kdbc_gk_strategy kdbc_driver_gk_strategy(kdbc_driver driver) {
    const kdbc_driver_vtable *vt = get_vt(driver);
    return vt ? vt->gk_strategy : KDBC_GK_NONE;
}

kdbc_gk_strategy kdbc_conn_gk_strategy(kdbc_conn *conn) {
    if (!conn) return KDBC_GK_NONE;
    /* Use runtime override if driver provides one */
    if (conn->vt->conn_gk_strategy)
        return conn->vt->conn_gk_strategy(conn->native);
    return conn->vt->gk_strategy;
}

int kdbc_driver_supports_release_savepoint(kdbc_driver driver) {
    const kdbc_driver_vtable *vt = get_vt(driver);
    return vt ? vt->supports_release_savepoint : 0;
}

/* ========================================================================
 * Error handling
 * ======================================================================== */

const char *kdbc_error(kdbc_conn *conn) {
    return conn ? conn->error : "";
}

const char *kdbc_stmt_error(kdbc_stmt *stmt) {
    return stmt ? stmt->error : "";
}

const char *kdbc_global_error(void) {
    return g_error;
}

/* ========================================================================
 * Connection management
 * ======================================================================== */

kdbc_conn *kdbc_connect(kdbc_driver driver, const char *url,
                        const char *user, const char *password) {
    const kdbc_driver_vtable *vt = get_vt(driver);
    if (!vt) {
        kdbc_set_global_error("Unknown driver: %d", driver);
        return NULL;
    }

    /* Ensure library is loaded */
    if (!vt->loaded() && !vt->load()) {
        kdbc_set_global_error("Driver '%s' not available: native library not found", vt->name);
        return NULL;
    }

    kdbc_conn *conn = (kdbc_conn *)calloc(1, sizeof(kdbc_conn));
    if (!conn) {
        kdbc_set_global_error("Out of memory");
        return NULL;
    }

    conn->driver = driver;
    conn->vt = vt;
    conn->autocommit = 1;

    conn->native = vt->connect(url, user, password, conn->error, KDBC_ERR_SIZE);
    if (!conn->native) {
        /* Error already set in conn->error by driver; copy to global */
        snprintf(g_error, KDBC_ERR_SIZE, "%s", conn->error);
        free(conn);
        return NULL;
    }

    return conn;
}

void kdbc_close(kdbc_conn *conn) {
    if (!conn) return;
    conn->vt->close(conn->native);
    free(conn);
}

kdbc_driver kdbc_conn_driver(kdbc_conn *conn) {
    return conn ? conn->driver : KDBC_SQLITE;
}

int kdbc_cancel(kdbc_conn *conn) {
    if (!conn || !conn->vt || !conn->vt->cancel) return KDBC_ERROR;
    return conn->vt->cancel(conn);
}

/* ========================================================================
 * Transaction control
 * ======================================================================== */

int kdbc_set_autocommit(kdbc_conn *conn, int enabled) {
    if (!conn) return KDBC_ERROR;
    conn->error[0] = '\0';
    int rc = conn->vt->set_autocommit(conn, enabled);
    if (rc == KDBC_OK) conn->autocommit = enabled;
    return rc;
}

int kdbc_commit(kdbc_conn *conn) {
    if (!conn) return KDBC_ERROR;
    conn->error[0] = '\0';
    return conn->vt->commit(conn);
}

int kdbc_rollback(kdbc_conn *conn) {
    if (!conn) return KDBC_ERROR;
    conn->error[0] = '\0';
    return conn->vt->rollback(conn);
}

/* Execute administrative SQL (savepoints, DDL, etc.)
 * Uses exec_direct if driver supports it, falls back to prepare+execute. */
static int exec_simple_sql(kdbc_conn *conn, const char *sql) {
    if (conn->vt->exec_direct)
        return conn->vt->exec_direct(conn, sql);
    kdbc_stmt *stmt = kdbc_prepare(conn, sql);
    if (!stmt) return KDBC_ERROR;
    int rc = kdbc_execute_update_stmt(stmt);
    kdbc_stmt_close(stmt);
    return (rc >= 0) ? KDBC_OK : KDBC_ERROR;
}

/* ========================================================================
 * Direct SQL execution (public API)
 * ======================================================================== */

int kdbc_execute_update(kdbc_conn *conn, const char *sql) {
    if (!conn || !sql) return KDBC_ERROR;
    conn->error[0] = '\0';
    /* Use exec_direct if available, otherwise prepare+execute */
    if (conn->vt->exec_direct)
        return conn->vt->exec_direct(conn, sql);
    kdbc_stmt *stmt = kdbc_prepare(conn, sql);
    if (!stmt) return KDBC_ERROR;
    int rc = kdbc_execute_update_stmt(stmt);
    kdbc_stmt_close(stmt);
    return rc;
}

kdbc_result *kdbc_execute_query(kdbc_conn *conn, const char *sql) {
    if (!conn || !sql) return NULL;
    conn->error[0] = '\0';

    /* Use direct query if driver supports it */
    if (conn->vt->query_direct) {
        int col_count = 0;
        void *native_rs = conn->vt->query_direct(conn, sql, &col_count,
                                                   conn->error, KDBC_ERR_SIZE);
        if (!native_rs) return NULL;
        kdbc_result *rs = (kdbc_result *)calloc(1, sizeof(kdbc_result));
        if (!rs) { conn->vt->rs_close(native_rs); return NULL; }
        rs->conn = conn;
        rs->native = native_rs;
        rs->col_count = col_count;
        rs->current_row = -1;
        return rs;
    }

    /* Fallback: prepare + execute */
    kdbc_stmt *stmt = kdbc_prepare(conn, sql);
    if (!stmt) return NULL;
    kdbc_result *rs = kdbc_execute_query_stmt(stmt);
    if (!rs) { kdbc_stmt_close(stmt); return NULL; }
    rs->owned_stmt = stmt;
    return rs;
}

/**
 * Validate savepoint name: must start with letter, contain only
 * alphanumeric + underscore, max 30 chars. Prevents SQL injection.
 */
static int validate_savepoint_name(kdbc_conn *conn, const char *name) {
    if (!name || !name[0]) {
        CONN_ERR(conn, "Savepoint name cannot be empty");
        return KDBC_ERROR;
    }
    size_t len = strlen(name);
    if (len > 30) {
        CONN_ERR(conn, "Savepoint name too long (%zu chars, max 30)", len);
        return KDBC_ERROR;
    }
    if (!((name[0] >= 'a' && name[0] <= 'z') || (name[0] >= 'A' && name[0] <= 'Z'))) {
        CONN_ERR(conn, "Savepoint name must start with a letter");
        return KDBC_ERROR;
    }
    for (size_t i = 1; i < len; i++) {
        char c = name[i];
        if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
              (c >= '0' && c <= '9') || c == '_')) {
            CONN_ERR(conn, "Savepoint name contains invalid character: '%c'", c);
            return KDBC_ERROR;
        }
    }
    return KDBC_OK;
}

int kdbc_savepoint(kdbc_conn *conn, const char *name) {
    if (!conn || !name) return KDBC_ERROR;
    if (validate_savepoint_name(conn, name) != KDBC_OK) return KDBC_ERROR;
    char sql[256];
    if (conn->driver == KDBC_MSSQL) {
        snprintf(sql, sizeof(sql), "SAVE TRANSACTION %s", name);
    } else {
        snprintf(sql, sizeof(sql), "SAVEPOINT %s", name);
    }
    return exec_simple_sql(conn, sql);
}

int kdbc_rollback_to(kdbc_conn *conn, const char *name) {
    if (!conn || !name) return KDBC_ERROR;
    if (validate_savepoint_name(conn, name) != KDBC_OK) return KDBC_ERROR;
    char sql[256];
    if (conn->driver == KDBC_MSSQL) {
        snprintf(sql, sizeof(sql), "ROLLBACK TRANSACTION %s", name);
    } else {
        snprintf(sql, sizeof(sql), "ROLLBACK TO SAVEPOINT %s", name);
    }
    return exec_simple_sql(conn, sql);
}

int kdbc_release_savepoint(kdbc_conn *conn, const char *name) {
    if (!conn || !name) return KDBC_ERROR;
    if (validate_savepoint_name(conn, name) != KDBC_OK) return KDBC_ERROR;
    /* Oracle and MSSQL don't support RELEASE SAVEPOINT */
    if (conn->driver == KDBC_ORACLE || conn->driver == KDBC_MSSQL)
        return KDBC_OK;
    char sql[256];
    snprintf(sql, sizeof(sql), "RELEASE SAVEPOINT %s", name);
    return exec_simple_sql(conn, sql);
}

/* ========================================================================
 * Database metadata
 * ======================================================================== */

const char *kdbc_product_name(kdbc_conn *conn) {
    if (!conn) return "";
    if (conn->product_name[0] == '\0')
        conn->vt->get_product_name(conn->native, conn->product_name,
                                   sizeof(conn->product_name));
    return conn->product_name;
}

const char *kdbc_product_version(kdbc_conn *conn) {
    if (!conn) return "";
    if (conn->product_version[0] == '\0')
        conn->vt->get_product_version(conn->native, conn->product_version,
                                      sizeof(conn->product_version));
    return conn->product_version;
}

int kdbc_major_version(kdbc_conn *conn) {
    return conn ? conn->vt->get_major_version(conn->native) : 0;
}

int kdbc_minor_version(kdbc_conn *conn) {
    return conn ? conn->vt->get_minor_version(conn->native) : 0;
}

/* ========================================================================
 * Statement helpers
 * ======================================================================== */

static void free_params(kdbc_stmt *stmt) {
    if (!stmt->params) return;
    for (int i = 0; i < stmt->param_count; i++) {
        free(stmt->params[i].owned);
        stmt->params[i].owned = NULL;
    }
}

static char *translate_sql(kdbc_driver d, const char *sql) {
    switch (d) {
        case KDBC_POSTGRES:  return kdbc_translate_params_dollar(sql);
        case KDBC_ORACLE:    return kdbc_translate_params_colon(sql);
        default:             return kdbc_translate_params_native(sql);
    }
}

/* ========================================================================
 * Statement preparation
 * ======================================================================== */

static kdbc_stmt *prepare_impl(kdbc_conn *conn, const char *sql,
                               const char **col_names, int n_cols,
                               int generated_keys_requested) {
    if (!conn || !sql) return NULL;
    conn->error[0] = '\0';

    int param_count = kdbc_count_params(sql);
    char *native_sql = translate_sql(conn->driver, sql);
    if (!native_sql) {
        CONN_ERR(conn, "Out of memory translating SQL");
        return NULL;
    }

    kdbc_stmt *stmt = (kdbc_stmt *)calloc(1, sizeof(kdbc_stmt));
    if (!stmt) {
        free(native_sql);
        CONN_ERR(conn, "Out of memory");
        return NULL;
    }

    stmt->conn = conn;
    stmt->sql = strdup(sql);
    stmt->native_sql = native_sql;
    stmt->param_count = param_count;
    stmt->has_generated_key = 0;
    stmt->generated_keys_requested = generated_keys_requested;

    if (param_count > 0) {
        stmt->params = (kdbc_param *)calloc(param_count, sizeof(kdbc_param));
        if (!stmt->params) {
            free(stmt->sql);
            free(stmt->native_sql);
            free(stmt);
            CONN_ERR(conn, "Out of memory allocating params");
            return NULL;
        }
    }

    /* Store returning columns */
    if (col_names && n_cols > 0) {
        stmt->ret_col_names = (char **)calloc(n_cols, sizeof(char *));
        stmt->ret_col_count = n_cols;
        for (int i = 0; i < n_cols; i++) {
            stmt->ret_col_names[i] = strdup(col_names[i]);
        }
    }

    /* Driver-level prepare */
    stmt->native = conn->vt->prepare(conn, native_sql, col_names, n_cols,
                                     generated_keys_requested,
                                     stmt->error, KDBC_ERR_SIZE);
    if (!stmt->native) {
        if (stmt->error[0])
            snprintf(conn->error, KDBC_ERR_SIZE, "%s", stmt->error);
        free_params(stmt);
        free(stmt->params);
        free(stmt->sql);
        free(stmt->native_sql);
        if (stmt->ret_col_names) {
            for (int i = 0; i < stmt->ret_col_count; i++)
                free(stmt->ret_col_names[i]);
            free(stmt->ret_col_names);
        }
        free(stmt);
        return NULL;
    }

    return stmt;
}

kdbc_stmt *kdbc_prepare(kdbc_conn *conn, const char *sql) {
    return prepare_impl(conn, sql, NULL, 0, 0);
}

kdbc_stmt *kdbc_prepare_returning(kdbc_conn *conn, const char *sql,
                                  const char **col_names, int n_cols) {
    return prepare_impl(conn, sql, col_names, n_cols, 1);
}

static void free_batches(kdbc_stmt *stmt) {
    if (!stmt->batches) return;
    for (int b = 0; b < stmt->batch_count; b++) {
        for (int i = 0; i < stmt->param_count; i++)
            free(stmt->batches[b][i].owned);
        free(stmt->batches[b]);
    }
    free(stmt->batches);
    stmt->batches = NULL;
    stmt->batch_count = 0;
    stmt->batch_cap = 0;
}

static void clear_out_values(kdbc_stmt *stmt) {
    if (stmt->out_values) {
        for (int i = 0; i < stmt->param_count; i++) {
            free(stmt->out_values[i]);
            stmt->out_values[i] = NULL;
        }
    }
}

static void free_callable(kdbc_stmt *stmt) {
    free(stmt->out_params);
    if (stmt->out_values) {
        for (int i = 0; i < stmt->param_count; i++)
            free(stmt->out_values[i]);
        free(stmt->out_values);
    }
}

void kdbc_stmt_close(kdbc_stmt *stmt) {
    if (!stmt) return;
    if (stmt->conn && stmt->conn->vt && stmt->native)
        stmt->conn->vt->stmt_close(stmt->native, stmt->conn->native);
    free_params(stmt);
    free(stmt->params);
    free_batches(stmt);
    free_callable(stmt);
    free(stmt->sql);
    free(stmt->native_sql);
    free(stmt->generated_key_str);
    if (stmt->ret_col_names) {
        for (int i = 0; i < stmt->ret_col_count; i++)
            free(stmt->ret_col_names[i]);
        free(stmt->ret_col_names);
    }
    free(stmt);
}

int kdbc_stmt_reset(kdbc_stmt *stmt) {
    if (!stmt || !stmt->conn) return KDBC_ERROR;
    free_params(stmt);
    memset(stmt->params, 0, stmt->param_count * sizeof(kdbc_param));
    free_batches(stmt);
    clear_out_values(stmt);
    stmt->has_generated_key = 0;
    stmt->generated_key = 0;
    free(stmt->generated_key_str);
    stmt->generated_key_str = NULL;
    stmt->error[0] = '\0';
    /* Driver-level reset if available (e.g. sqlite3_reset + sqlite3_clear_bindings).
     * Drivers that rebind params before each execute do not need a native
     * reset and leave this NULL. */
    if (stmt->conn->vt->stmt_reset)
        return stmt->conn->vt->stmt_reset(stmt->native);
    return KDBC_OK;
}

int kdbc_set_fetch_size(kdbc_stmt *stmt, int rows) {
    if (!stmt) return KDBC_ERROR;
    stmt->fetch_size = rows > 0 ? rows : 0;
    return KDBC_OK;
}

/* ========================================================================
 * Parameter binding
 * ======================================================================== */

#define CHECK_BIND(stmt, idx) \
    do { \
        if (!(stmt) || (idx) < 1 || (idx) > (stmt)->param_count) { \
            if (stmt) STMT_ERR(stmt, "Invalid parameter index: %d (count=%d)", idx, (stmt)->param_count); \
            return KDBC_ERROR; \
        } \
    } while (0)

int kdbc_bind_null(kdbc_stmt *stmt, int idx) {
    CHECK_BIND(stmt, idx);
    stmt->params[idx - 1].type = KDBC_TYPE_NULL;
    free(stmt->params[idx - 1].owned);
    stmt->params[idx - 1].owned = NULL;
    return stmt->conn->vt->bind_null(stmt, idx);
}

int kdbc_bind_int(kdbc_stmt *stmt, int idx, int val) {
    CHECK_BIND(stmt, idx);
    stmt->params[idx - 1].type = KDBC_TYPE_INT;
    stmt->params[idx - 1].val.i64 = val;
    return stmt->conn->vt->bind_int(stmt, idx, val);
}

int kdbc_bind_long(kdbc_stmt *stmt, int idx, int64_t val) {
    CHECK_BIND(stmt, idx);
    stmt->params[idx - 1].type = KDBC_TYPE_LONG;
    stmt->params[idx - 1].val.i64 = val;
    return stmt->conn->vt->bind_long(stmt, idx, val);
}

int kdbc_bind_double(kdbc_stmt *stmt, int idx, double val) {
    CHECK_BIND(stmt, idx);
    stmt->params[idx - 1].type = KDBC_TYPE_DOUBLE;
    stmt->params[idx - 1].val.dbl = val;
    return stmt->conn->vt->bind_double(stmt, idx, val);
}

int kdbc_bind_string(kdbc_stmt *stmt, int idx, const char *val) {
    CHECK_BIND(stmt, idx);
    if (!val) return kdbc_bind_null(stmt, idx);

    /* Take ownership of a copy */
    free(stmt->params[idx - 1].owned);
    char *copy = strdup(val);
    if (!copy) { STMT_ERR(stmt, "Out of memory"); return KDBC_ERROR; }
    stmt->params[idx - 1].owned = copy;
    stmt->params[idx - 1].type = KDBC_TYPE_STRING;
    stmt->params[idx - 1].val.str.ptr = copy;
    stmt->params[idx - 1].val.str.len = strlen(val);
    return stmt->conn->vt->bind_string(stmt, idx, copy);
}

int kdbc_bind_blob(kdbc_stmt *stmt, int idx, const void *data, size_t len) {
    CHECK_BIND(stmt, idx);
    if (!data) return kdbc_bind_null(stmt, idx);

    /* Take ownership of a copy */
    free(stmt->params[idx - 1].owned);
    void *copy = malloc(len);
    if (!copy) { STMT_ERR(stmt, "Out of memory"); return KDBC_ERROR; }
    memcpy(copy, data, len);
    stmt->params[idx - 1].owned = copy;
    stmt->params[idx - 1].type = KDBC_TYPE_BLOB;
    stmt->params[idx - 1].val.blob.ptr = copy;
    stmt->params[idx - 1].val.blob.len = len;
    return stmt->conn->vt->bind_blob(stmt, idx, copy, len);
}

int kdbc_bind_timestamp(kdbc_stmt *stmt, int idx,
                        int year, int month, int day,
                        int hour, int minute, int second, int usec) {
    CHECK_BIND(stmt, idx);
    if (stmt->conn->vt->bind_timestamp)
        return stmt->conn->vt->bind_timestamp(stmt, idx, year, month, day,
                                              hour, minute, second, usec);
    /* Fallback: send as ISO-8601 string */
    char buf[32];
    snprintf(buf, sizeof(buf), "%04d-%02d-%02d %02d:%02d:%02d.%06d",
             year, month, day, hour, minute, second, usec);
    return kdbc_bind_string(stmt, idx, buf);
}

int kdbc_bind_date(kdbc_stmt *stmt, int idx, int year, int month, int day) {
    CHECK_BIND(stmt, idx);
    if (stmt->conn->vt->bind_date)
        return stmt->conn->vt->bind_date(stmt, idx, year, month, day);
    char buf[16];
    snprintf(buf, sizeof(buf), "%04d-%02d-%02d", year, month, day);
    return kdbc_bind_string(stmt, idx, buf);
}

int kdbc_bind_time(kdbc_stmt *stmt, int idx,
                   int hour, int minute, int second, int usec) {
    CHECK_BIND(stmt, idx);
    if (stmt->conn->vt->bind_time)
        return stmt->conn->vt->bind_time(stmt, idx, hour, minute, second, usec);
    char buf[20];
    snprintf(buf, sizeof(buf), "%02d:%02d:%02d.%06d", hour, minute, second, usec);
    return kdbc_bind_string(stmt, idx, buf);
}

/* ========================================================================
 * Statement execution
 * ======================================================================== */

int kdbc_execute_update_stmt(kdbc_stmt *stmt) {
    if (!stmt) return KDBC_ERROR;
    stmt->error[0] = '\0';
    return stmt->conn->vt->execute_update(stmt);
}

kdbc_result *kdbc_execute_query_stmt(kdbc_stmt *stmt) {
    if (!stmt) return NULL;
    stmt->error[0] = '\0';

    int col_count = 0;
    void *native_rs = stmt->conn->vt->execute_query(
        stmt, &col_count, stmt->error, KDBC_ERR_SIZE);
    if (!native_rs) return NULL;

    kdbc_result *rs = (kdbc_result *)calloc(1, sizeof(kdbc_result));
    if (!rs) {
        stmt->conn->vt->rs_close(native_rs);
        STMT_ERR(stmt, "Out of memory");
        return NULL;
    }

    rs->conn = stmt->conn;
    rs->native = native_rs;
    rs->col_count = col_count;
    rs->current_row = -1;
    rs->last_null = 0;

    return rs;
}

kdbc_result *kdbc_generated_keys(kdbc_stmt *stmt) {
    if (!stmt) return NULL;
    if (!stmt->has_generated_key && !stmt->generated_key_str) {
        /* Try to get from driver */
        int64_t key;
        if (stmt->conn->vt->get_generated_key &&
            stmt->conn->vt->get_generated_key(stmt, &key) == KDBC_OK) {
            stmt->generated_key = key;
            stmt->has_generated_key = 1;
        } else {
            return NULL;
        }
    }

    /* Create a synthetic result set with one row, one column */
    kdbc_result *rs = (kdbc_result *)calloc(1, sizeof(kdbc_result));
    if (!rs) return NULL;
    rs->conn = stmt->conn;
    rs->native = NULL; /* synthetic result - no native handle */
    rs->col_count = 1;
    rs->current_row = -2; /* special marker: generated key result */
    /* Store the key in str_buf for retrieval. Prefer the verbatim string form
     * (UUID, ROWID, VARCHAR2 PKs) when the driver supplied one — otherwise
     * format the numeric key as text. */
    if (stmt->generated_key_str) {
        size_t cap = strlen(stmt->generated_key_str) + 1;
        rs->str_buf = (char *)malloc(cap);
        if (rs->str_buf) {
            memcpy(rs->str_buf, stmt->generated_key_str, cap);
            rs->str_buf_cap = cap;
        }
    } else {
        rs->str_buf = (char *)malloc(32);
        if (rs->str_buf) {
            snprintf(rs->str_buf, 32, "%lld", (long long)stmt->generated_key);
            rs->str_buf_cap = 32;
        }
    }
    /* Synthetic column name: use the first requested returning column if any,
     * otherwise default to "id" — this is how stormify's populate() path finds
     * the primary-key field on the target entity. */
    const char *col_name = (stmt->ret_col_names && stmt->ret_col_count > 0 && stmt->ret_col_names[0])
        ? stmt->ret_col_names[0]
        : "id";
    rs->synthetic_col_name = strdup(col_name);
    return rs;
}

/* ========================================================================
 * Batch execution
 * ======================================================================== */

/**
 * Snapshot current parameters into the batch list.
 */
int kdbc_add_batch(kdbc_stmt *stmt) {
    if (!stmt || stmt->param_count == 0) return KDBC_ERROR;

    /* Grow batch array if needed */
    if (stmt->batch_count >= stmt->batch_cap) {
        int new_cap = stmt->batch_cap == 0 ? 16 : stmt->batch_cap * 2;
        kdbc_param **new_arr = (kdbc_param **)realloc(
            stmt->batches, new_cap * sizeof(kdbc_param *));
        if (!new_arr) { STMT_ERR(stmt, "Out of memory"); return KDBC_ERROR; }
        stmt->batches = new_arr;
        stmt->batch_cap = new_cap;
    }

    /* Deep copy current params */
    kdbc_param *snap = (kdbc_param *)calloc(stmt->param_count, sizeof(kdbc_param));
    if (!snap) { STMT_ERR(stmt, "Out of memory"); return KDBC_ERROR; }

    for (int i = 0; i < stmt->param_count; i++) {
        snap[i] = stmt->params[i];
        snap[i].owned = NULL;
        /* Deep copy owned data */
        if (stmt->params[i].owned) {
            size_t sz = 0;
            if (stmt->params[i].type == KDBC_TYPE_STRING)
                sz = stmt->params[i].val.str.len + 1;
            else if (stmt->params[i].type == KDBC_TYPE_BLOB)
                sz = stmt->params[i].val.blob.len;
            if (sz > 0) {
                snap[i].owned = malloc(sz);
                if (snap[i].owned) {
                    memcpy(snap[i].owned, stmt->params[i].owned, sz);
                    if (stmt->params[i].type == KDBC_TYPE_STRING)
                        snap[i].val.str.ptr = (const char *)snap[i].owned;
                    else
                        snap[i].val.blob.ptr = snap[i].owned;
                }
            }
        }
    }

    stmt->batches[stmt->batch_count++] = snap;
    return KDBC_OK;
}

/**
 * Execute all batched parameter sets sequentially.
 * Rebinds parameters for each batch entry and calls execute_update.
 */
int kdbc_execute_batch(kdbc_stmt *stmt) {
    if (!stmt || !stmt->conn) return KDBC_ERROR;
    if (stmt->batch_count == 0) return 0;

    int total = 0;
    for (int b = 0; b < stmt->batch_count; b++) {
        /* Restore parameters from snapshot */
        kdbc_param *snap = stmt->batches[b];
        for (int i = 0; i < stmt->param_count; i++) {
            int idx = i + 1;
            kdbc_param *p = &snap[i];
            switch (p->type) {
                case KDBC_TYPE_NULL:
                    stmt->conn->vt->bind_null(stmt, idx);
                    break;
                case KDBC_TYPE_INT:
                    stmt->conn->vt->bind_int(stmt, idx, (int)p->val.i64);
                    break;
                case KDBC_TYPE_LONG:
                    stmt->conn->vt->bind_long(stmt, idx, p->val.i64);
                    break;
                case KDBC_TYPE_DOUBLE:
                    stmt->conn->vt->bind_double(stmt, idx, p->val.dbl);
                    break;
                case KDBC_TYPE_STRING:
                    stmt->conn->vt->bind_string(stmt, idx, p->val.str.ptr);
                    break;
                case KDBC_TYPE_BLOB:
                    stmt->conn->vt->bind_blob(stmt, idx, p->val.blob.ptr, p->val.blob.len);
                    break;
                default:
                    break;
            }
        }
        int rc = stmt->conn->vt->execute_update(stmt);
        if (rc < 0) {
            free_batches(stmt);
            return KDBC_ERROR;
        }
        total += rc;
    }

    free_batches(stmt);
    return total;
}

/* ========================================================================
 * Callable statements (stored procedures)
 * ======================================================================== */

/**
 * Parse JDBC-style "{CALL proc(?, ?)}" into clean SQL for each driver.
 * Returns allocated string. Caller must free.
 */
static char *parse_call_sql(const char *sql) {
    const char *p = sql;
    /* Skip leading whitespace and { */
    while (*p == ' ' || *p == '{') p++;
    /* Skip CALL keyword */
    if (strncasecmp(p, "call ", 5) == 0) p += 5;
    else if (strncasecmp(p, "call\t", 5) == 0) p += 5;
    /* Find end (strip trailing } and whitespace) */
    size_t len = strlen(p);
    while (len > 0 && (p[len - 1] == '}' || p[len - 1] == ' ')) len--;
    char *out = (char *)malloc(len + 1);
    if (out) { memcpy(out, p, len); out[len] = '\0'; }
    return out;
}

kdbc_stmt *kdbc_prepare_call(kdbc_conn *conn, const char *sql) {
    if (!conn || !sql) return NULL;
    conn->error[0] = '\0';

    char *clean = parse_call_sql(sql);
    if (!clean) { CONN_ERR(conn, "Out of memory"); return NULL; }

    /* Build the call SQL based on driver */
    char *call_sql = NULL;
    switch (conn->driver) {
        case KDBC_POSTGRES:
            /* PostgreSQL: SELECT * FROM proc(?, ?) or CALL proc(?, ?) for procedures */
            call_sql = (char *)malloc(strlen(clean) + 8);
            if (call_sql) sprintf(call_sql, "CALL %s", clean);
            break;
        case KDBC_ORACLE:
            /* Oracle: BEGIN proc(?, ?); END; */
            call_sql = (char *)malloc(strlen(clean) + 16);
            if (call_sql) sprintf(call_sql, "BEGIN %s; END;", clean);
            break;
        case KDBC_MSSQL:
            /* MSSQL: EXEC proc ?, ? */
            call_sql = (char *)malloc(strlen(clean) + 8);
            if (call_sql) sprintf(call_sql, "EXEC %s", clean);
            break;
        default:
            /* MySQL/SQLite: CALL proc(?, ?) */
            call_sql = (char *)malloc(strlen(clean) + 8);
            if (call_sql) sprintf(call_sql, "CALL %s", clean);
            break;
    }
    free(clean);

    if (!call_sql) { CONN_ERR(conn, "Out of memory"); return NULL; }

    /* Prepare as a regular statement */
    kdbc_stmt *stmt = prepare_impl(conn, call_sql, NULL, 0, 0);
    free(call_sql);

    if (stmt) {
        stmt->is_callable = 1;
        stmt->out_params = (int *)calloc(stmt->param_count, sizeof(int));
        stmt->out_values = (char **)calloc(stmt->param_count, sizeof(char *));
    }

    return stmt;
}

int kdbc_register_out(kdbc_stmt *stmt, int idx) {
    if (!stmt || idx < 1 || idx > stmt->param_count) return KDBC_ERROR;
    if (!stmt->out_params) return KDBC_ERROR;
    stmt->out_params[idx - 1] = 1;
    return KDBC_OK;
}

int kdbc_call_execute(kdbc_stmt *stmt) {
    if (!stmt || !stmt->conn) return KDBC_ERROR;
    stmt->error[0] = '\0';

    /* If driver has native callable support, use it */
    if (stmt->conn->vt->call_execute) {
        return stmt->conn->vt->call_execute(stmt);
    }

    /* Fallback: execute as regular statement */
    int rc = stmt->conn->vt->execute_update(stmt);
    return rc >= 0 ? 1 : KDBC_ERROR;
}

int64_t kdbc_call_get_long(kdbc_stmt *stmt, int idx) {
    if (!stmt || idx < 1 || idx > stmt->param_count) return 0;

    /* If driver has native OUT support */
    if (stmt->conn->vt->call_get_out) {
        const char *val = stmt->conn->vt->call_get_out(stmt, idx);
        if (val) {
            long long v = 0;
            sscanf(val, "%lld", &v);
            return v;
        }
    }

    /* Fallback: check cached out_values */
    if (stmt->out_values && stmt->out_values[idx - 1]) {
        long long v = 0;
        sscanf(stmt->out_values[idx - 1], "%lld", &v);
        return v;
    }
    return 0;
}

const char *kdbc_call_get_string(kdbc_stmt *stmt, int idx) {
    if (!stmt || idx < 1 || idx > stmt->param_count) return NULL;

    if (stmt->conn->vt->call_get_out)
        return stmt->conn->vt->call_get_out(stmt, idx);

    if (stmt->out_values)
        return stmt->out_values[idx - 1];
    return NULL;
}

/* ========================================================================
 * Result set
 * ======================================================================== */

int kdbc_next(kdbc_result *rs) {
    if (!rs) return KDBC_ERROR;

    /* Synthetic generated-key result set */
    if (rs->current_row == -2) {
        rs->current_row = 0;
        return 1;
    }
    if (rs->current_row >= 0 && rs->native == NULL) {
        return 0; /* synthetic result exhausted */
    }

    rs->current_row++;
    return rs->conn->vt->rs_next(rs);
}

int kdbc_col_count(kdbc_result *rs) {
    return rs ? rs->col_count : 0;
}

const char *kdbc_col_name(kdbc_result *rs, int col) {
    if (!rs || col < 1 || col > rs->col_count) return NULL;
    if (!rs->native) return rs->synthetic_col_name; /* synthetic generated-key result */
    return rs->conn->vt->rs_col_name(rs->native, col);
}

const char *kdbc_col_label(kdbc_result *rs, int col) {
    if (!rs || col < 1 || col > rs->col_count) return NULL;
    if (!rs->native) return rs->synthetic_col_name; /* synthetic generated-key result */
    if (rs->conn->vt->rs_col_label)
        return rs->conn->vt->rs_col_label(rs->native, col);
    return rs->conn->vt->rs_col_name(rs->native, col);
}

int kdbc_is_null(kdbc_result *rs, int col) {
    if (!rs) return 1;
    /* Synthetic generated-key result */
    if (!rs->native) return 0;
    return rs->conn->vt->rs_is_null(rs, col);
}

int64_t kdbc_get_long(kdbc_result *rs, int col) {
    if (!rs) return 0;
    /* Synthetic generated-key result */
    if (!rs->native && rs->str_buf) {
        rs->last_null = 0;
        long long v = 0;
        sscanf(rs->str_buf, "%lld", &v);
        return (int64_t)v;
    }
    return rs->conn->vt->rs_get_long(rs, col);
}

double kdbc_get_double(kdbc_result *rs, int col) {
    if (!rs) return 0.0;
    if (!rs->native && rs->str_buf) {
        rs->last_null = 0;
        return strtod(rs->str_buf, NULL);
    }
    return rs->conn->vt->rs_get_double(rs, col);
}

const char *kdbc_get_string(kdbc_result *rs, int col) {
    if (!rs) return NULL;
    /* Synthetic generated-key result */
    if (!rs->native) {
        rs->last_null = 0;
        return rs->str_buf;
    }
    return rs->conn->vt->rs_get_string(rs, col);
}

const void *kdbc_get_blob(kdbc_result *rs, int col, size_t *out_len) {
    if (!rs || !rs->native) { if (out_len) *out_len = 0; return NULL; }
    return rs->conn->vt->rs_get_blob(rs, col, out_len);
}

int kdbc_get_timestamp(kdbc_result *rs, int col,
                       int *year, int *month, int *day,
                       int *hour, int *minute, int *second, int *usec) {
    if (!rs || !rs->native) return KDBC_ERROR;
    if (rs->conn->vt->rs_get_timestamp)
        return rs->conn->vt->rs_get_timestamp(rs, col, year, month, day,
                                              hour, minute, second, usec);
    /* Fallback: parse ISO-8601 string */
    const char *txt = rs->conn->vt->rs_get_string(rs, col);
    if (!txt) { RS_ERR(rs, "NULL timestamp value"); return KDBC_ERROR; }
    *usec = 0;
    int n = sscanf(txt, "%d-%d-%d %d:%d:%d.%d", year, month, day, hour, minute, second, usec);
    if (n < 6)
        n = sscanf(txt, "%d-%d-%dT%d:%d:%d.%d", year, month, day, hour, minute, second, usec);
    if (n >= 6) return KDBC_OK;
    RS_ERR(rs, "Cannot parse timestamp '%s'", txt);
    return KDBC_ERROR;
}

int kdbc_get_date(kdbc_result *rs, int col,
                  int *year, int *month, int *day) {
    if (!rs || !rs->native) return KDBC_ERROR;
    if (rs->conn->vt->rs_get_date)
        return rs->conn->vt->rs_get_date(rs, col, year, month, day);
    const char *txt = rs->conn->vt->rs_get_string(rs, col);
    if (!txt) { RS_ERR(rs, "NULL date value"); return KDBC_ERROR; }
    if (sscanf(txt, "%d-%d-%d", year, month, day) >= 3) return KDBC_OK;
    RS_ERR(rs, "Cannot parse date '%s'", txt);
    return KDBC_ERROR;
}

int kdbc_get_time(kdbc_result *rs, int col,
                  int *hour, int *minute, int *second, int *usec) {
    if (!rs || !rs->native) return KDBC_ERROR;
    if (rs->conn->vt->rs_get_time)
        return rs->conn->vt->rs_get_time(rs, col, hour, minute, second, usec);
    const char *txt = rs->conn->vt->rs_get_string(rs, col);
    if (!txt) { RS_ERR(rs, "NULL time value"); return KDBC_ERROR; }
    *usec = 0;
    if (sscanf(txt, "%d:%d:%d.%d", hour, minute, second, usec) >= 3) return KDBC_OK;
    RS_ERR(rs, "Cannot parse time '%s'", txt);
    return KDBC_ERROR;
}

void kdbc_result_close(kdbc_result *rs) {
    if (!rs) return;
    if (rs->native)
        rs->conn->vt->rs_close(rs->native);
    /* Auto-close owned statement (from kdbc_execute_query) */
    if (rs->owned_stmt)
        kdbc_stmt_close(rs->owned_stmt);
    free(rs->str_buf);
    free(rs->synthetic_col_name);
    free(rs);
}
