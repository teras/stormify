/*
 * KDBC Native - Oracle driver (ODPI-C)
 *
 * Uses dlopen/dlsym to load libodpic at runtime.
 * ODPI-C provides a clean C API over OCI with automatic resource management.
 * All values are transferred via dpiData structures (typed unions).
 *
 * SPDX-License-Identifier: Apache-2.0
 */
#include "../include/kdbc_internal.h"
#include "../include/kdbc_dl.h"
#include <dpi.h>
#include <pthread.h>

/* ========================================================================
 * Function pointer types
 * ======================================================================== */

typedef int (*fn_dpiContext_createWithParams)(unsigned int, unsigned int, void *,
                                              dpiContext **, dpiErrorInfo *);
typedef int (*fn_dpiContext_initCommonCreateParams)(const dpiContext *,
                                                    dpiCommonCreateParams *);
typedef void (*fn_dpiContext_getError)(const dpiContext *, dpiErrorInfo *);
typedef int (*fn_dpiConn_create)(const dpiContext *,
                                 const char *, unsigned int,
                                 const char *, unsigned int,
                                 const char *, unsigned int,
                                 void *, void *, dpiConn **);
typedef int (*fn_dpiConn_close)(dpiConn *, unsigned int, const char *, unsigned int);
typedef int (*fn_dpiConn_breakExecution)(dpiConn *);
typedef int (*fn_dpiConn_commit)(dpiConn *);
typedef int (*fn_dpiConn_rollback)(dpiConn *);
typedef int (*fn_dpiConn_prepareStmt)(dpiConn *, int,
                                      const char *, unsigned int,
                                      const char *, unsigned int,
                                      dpiStmt **);
typedef int (*fn_dpiConn_newVar)(dpiConn *, unsigned int, unsigned int,
                                 unsigned int, unsigned int, int, int,
                                 void *, dpiVar **, dpiData **);
typedef int (*fn_dpiConn_getServerVersion)(dpiConn *, const char **,
                                           unsigned int *, dpiVersionInfo *);
typedef int (*fn_dpiStmt_execute)(dpiStmt *, unsigned int, unsigned int *);
typedef int (*fn_dpiStmt_getRowCount)(dpiStmt *, unsigned long long *);
typedef int (*fn_dpiStmt_getNumQueryColumns)(dpiStmt *, unsigned int *);
typedef int (*fn_dpiStmt_getQueryInfo)(dpiStmt *, unsigned int, dpiQueryInfo *);
typedef int (*fn_dpiStmt_getQueryValue)(dpiStmt *, unsigned int, unsigned int *, dpiData **);
typedef int (*fn_dpiStmt_fetch)(dpiStmt *, int *, unsigned int *);
typedef int (*fn_dpiStmt_bindByPos)(dpiStmt *, unsigned int, dpiVar *);
typedef int (*fn_dpiStmt_getBindCount)(dpiStmt *, uint32_t *);
typedef int (*fn_dpiStmt_define)(dpiStmt *, uint32_t, dpiVar *);
typedef int (*fn_dpiStmt_getFetchArraySize)(dpiStmt *, uint32_t *);
typedef int (*fn_dpiStmt_close)(dpiStmt *, const char *, unsigned int);
typedef int (*fn_dpiStmt_release)(dpiStmt *);
typedef int (*fn_dpiVar_release)(dpiVar *);
typedef int (*fn_dpiVar_setFromBytes)(dpiVar *, unsigned int, const char *, unsigned int);
typedef int (*fn_dpiVar_setFromLob)(dpiVar *, unsigned int, dpiLob *);
typedef int (*fn_dpiVar_getReturnedData)(dpiVar *, unsigned int, unsigned int *, dpiData **);
typedef int (*fn_dpiConn_newTempLob)(dpiConn *, unsigned int, dpiLob **);
typedef int (*fn_dpiLob_setFromBytes)(dpiLob *, const char *, uint64_t);
typedef int (*fn_dpiLob_readBytes)(dpiLob *, uint64_t, uint64_t, char *, uint64_t *);
typedef int (*fn_dpiLob_getSize)(dpiLob *, uint64_t *);
typedef int (*fn_dpiLob_release)(dpiLob *);

/* ========================================================================
 * Loaded function pointers
 * ======================================================================== */

static kdbc_lib_handle lib_handle = NULL;

static fn_dpiContext_createWithParams    p_ctx_create;
static fn_dpiContext_initCommonCreateParams p_ctx_init_common_params;
static fn_dpiContext_getError            p_ctx_getError;
static fn_dpiConn_create                 p_conn_create;
static fn_dpiConn_close                  p_conn_close;
static fn_dpiConn_breakExecution         p_conn_breakExecution;
static fn_dpiConn_commit                 p_conn_commit;
static fn_dpiConn_rollback               p_conn_rollback;
static fn_dpiConn_prepareStmt            p_conn_prepareStmt;
static fn_dpiConn_newVar                 p_conn_newVar;
static fn_dpiConn_getServerVersion       p_conn_getServerVersion;
static fn_dpiStmt_execute                p_stmt_execute;
static fn_dpiStmt_getRowCount            p_stmt_getRowCount;
static fn_dpiStmt_getNumQueryColumns     p_stmt_getNumQueryColumns;
static fn_dpiStmt_getQueryInfo           p_stmt_getQueryInfo;
static fn_dpiStmt_getQueryValue          p_stmt_getQueryValue;
static fn_dpiStmt_fetch                  p_stmt_fetch;
static fn_dpiStmt_bindByPos              p_stmt_bindByPos;
static fn_dpiStmt_getBindCount           p_stmt_getBindCount;
static fn_dpiStmt_define                 p_stmt_define;
static fn_dpiStmt_getFetchArraySize      p_stmt_getFetchArraySize;
static fn_dpiStmt_close                  p_stmt_close;
static fn_dpiStmt_release                p_stmt_release;
static fn_dpiVar_release                 p_var_release;
static fn_dpiVar_setFromBytes            p_var_setFromBytes;
static fn_dpiVar_setFromLob              p_var_setFromLob;
static fn_dpiVar_getReturnedData         p_var_getReturnedData;
static fn_dpiConn_newTempLob             p_conn_newTempLob;
static fn_dpiLob_setFromBytes            p_lob_setFromBytes;
static fn_dpiLob_readBytes               p_lob_readBytes;
static fn_dpiLob_getSize                 p_lob_getSize;
static fn_dpiLob_release                 p_lob_release;

static dpiContext *g_ora_ctx = NULL;

/* ========================================================================
 * Library loading
 * ======================================================================== */

#define ORA_LOAD(var, name) do { \
    var = kdbc_dl_sym(lib_handle, name); \
    if (!var) { kdbc_dl_close(lib_handle); lib_handle = NULL; return; } \
} while (0)

static pthread_once_t ora_load_once = PTHREAD_ONCE_INIT;
static int            ora_load_ok   = 0;

static void ora_load_impl(void) {
    lib_handle = kdbc_dl_open(KDBC_LIBNAME_NOVER("odpic"), RTLD_LAZY);
    if (!lib_handle) lib_handle = kdbc_dl_open(KDBC_LIBNAME("odpic", "5"), RTLD_LAZY);
    if (!lib_handle) return;

    ORA_LOAD(p_ctx_create,              "dpiContext_createWithParams");
    ORA_LOAD(p_ctx_init_common_params,  "dpiContext_initCommonCreateParams");
    ORA_LOAD(p_ctx_getError,            "dpiContext_getError");
    ORA_LOAD(p_conn_create,             "dpiConn_create");
    ORA_LOAD(p_conn_close,              "dpiConn_close");
    ORA_LOAD(p_conn_breakExecution,     "dpiConn_breakExecution");
    ORA_LOAD(p_conn_commit,             "dpiConn_commit");
    ORA_LOAD(p_conn_rollback,           "dpiConn_rollback");
    ORA_LOAD(p_conn_prepareStmt,        "dpiConn_prepareStmt");
    ORA_LOAD(p_conn_newVar,             "dpiConn_newVar");
    ORA_LOAD(p_conn_getServerVersion,   "dpiConn_getServerVersion");
    ORA_LOAD(p_stmt_execute,            "dpiStmt_execute");
    ORA_LOAD(p_stmt_getRowCount,        "dpiStmt_getRowCount");
    ORA_LOAD(p_stmt_getNumQueryColumns, "dpiStmt_getNumQueryColumns");
    ORA_LOAD(p_stmt_getQueryInfo,       "dpiStmt_getQueryInfo");
    ORA_LOAD(p_stmt_getQueryValue,      "dpiStmt_getQueryValue");
    ORA_LOAD(p_stmt_fetch,              "dpiStmt_fetch");
    ORA_LOAD(p_stmt_bindByPos,          "dpiStmt_bindByPos");
    ORA_LOAD(p_stmt_getBindCount,       "dpiStmt_getBindCount");
    ORA_LOAD(p_stmt_define,             "dpiStmt_define");
    ORA_LOAD(p_stmt_getFetchArraySize,  "dpiStmt_getFetchArraySize");
    ORA_LOAD(p_stmt_close,              "dpiStmt_close");
    ORA_LOAD(p_stmt_release,            "dpiStmt_release");
    ORA_LOAD(p_var_release,             "dpiVar_release");
    ORA_LOAD(p_var_setFromBytes,        "dpiVar_setFromBytes");
    ORA_LOAD(p_var_setFromLob,          "dpiVar_setFromLob");
    ORA_LOAD(p_var_getReturnedData,  "dpiVar_getReturnedData");
    ORA_LOAD(p_conn_newTempLob,         "dpiConn_newTempLob");
    ORA_LOAD(p_lob_setFromBytes,        "dpiLob_setFromBytes");
    ORA_LOAD(p_lob_readBytes,           "dpiLob_readBytes");
    ORA_LOAD(p_lob_getSize,             "dpiLob_getSize");
    ORA_LOAD(p_lob_release,             "dpiLob_release");

    /* Initialize global ODPI-C context */
    dpiErrorInfo errInfo;
    if (p_ctx_create(DPI_MAJOR_VERSION, DPI_MINOR_VERSION, NULL,
                     &g_ora_ctx, &errInfo) != DPI_SUCCESS) {
        kdbc_dl_close(lib_handle);
        lib_handle = NULL;
        g_ora_ctx = NULL;
        return;
    }

    ora_load_ok = 1;
}

static int ora_load(void) {
    pthread_once(&ora_load_once, ora_load_impl);
    return ora_load_ok;
}

static int ora_loaded(void) { return lib_handle != NULL && g_ora_ctx != NULL; }

/* ========================================================================
 * Helper: get ODPI-C error message
 * ======================================================================== */

static const char *ora_get_error(void) {
    if (!g_ora_ctx) return "ODPI-C context not initialized";
    dpiErrorInfo ei;
    p_ctx_getError(g_ora_ctx, &ei);
    return ei.message ? ei.message : "Unknown Oracle error";
}

/* ========================================================================
 * Connection
 * ======================================================================== */

typedef struct {
    dpiConn *conn;
    int      autocommit;
} ora_conn;

static void *ora_connect(const char *url, const char *user, const char *password,
                         char *err, size_t err_size) {
    ora_conn *oc = (ora_conn *)calloc(1, sizeof(ora_conn));
    if (!oc) { snprintf(err, err_size, "Out of memory"); return NULL; }
    oc->autocommit = 1;

    /* Pin the session to UTF-8 for both CHAR (encoding) and NCHAR (nencoding)
     * data so that VARCHAR2/NVARCHAR2/CLOB round-trip Kotlin's UTF-8 strings
     * losslessly, regardless of the process's NLS_LANG environment variable
     * (which ODPI-C otherwise falls back to and is often non-UTF-8 in the wild). */
    dpiCommonCreateParams common;
    if (p_ctx_init_common_params(g_ora_ctx, &common) != DPI_SUCCESS) {
        snprintf(err, err_size, "Oracle: %s", ora_get_error());
        free(oc);
        return NULL;
    }
    common.encoding  = "UTF-8";
    common.nencoding = "UTF-8";

    if (p_conn_create(g_ora_ctx,
                      user, user ? (unsigned int)strlen(user) : 0,
                      password, password ? (unsigned int)strlen(password) : 0,
                      url, (unsigned int)strlen(url),
                      &common, NULL, &oc->conn) != DPI_SUCCESS) {
        snprintf(err, err_size, "Oracle: %s", ora_get_error());
        free(oc);
        return NULL;
    }
    return oc;
}

static void ora_close(void *native) {
    ora_conn *oc = (ora_conn *)native;
    if (!oc) return;
    p_conn_close(oc->conn, DPI_MODE_CONN_CLOSE_DEFAULT, NULL, 0);
    free(oc);
}

/* Async cancel of the currently-executing statement on this connection.
 * dpiConn_breakExecution is explicitly documented as thread-safe: it uses
 * OCIBreak to signal the server to interrupt the current operation,
 * causing the blocking dpiStmt_execute on the other thread to return
 * with an error. */
static int ora_cancel(kdbc_conn *conn) {
    if (!conn || !conn->native) return KDBC_ERROR;
    ora_conn *oc = (ora_conn *)conn->native;
    if (p_conn_breakExecution(oc->conn) != DPI_SUCCESS) {
        CONN_ERR(conn, "Oracle cancel failed: %s", ora_get_error());
        return KDBC_ERROR;
    }
    return KDBC_OK;
}

/* ========================================================================
 * Transactions
 * ======================================================================== */

static int ora_set_autocommit(kdbc_conn *conn, int enabled) {
    ora_conn *oc = (ora_conn *)conn->native;
    oc->autocommit = enabled;
    return KDBC_OK;
}

static int ora_commit(kdbc_conn *conn) {
    ora_conn *oc = (ora_conn *)conn->native;
    if (p_conn_commit(oc->conn) != DPI_SUCCESS) {
        CONN_ERR(conn, "Oracle commit: %s", ora_get_error());
        return KDBC_ERROR;
    }
    return KDBC_OK;
}

static int ora_rollback(kdbc_conn *conn) {
    ora_conn *oc = (ora_conn *)conn->native;
    if (p_conn_rollback(oc->conn) != DPI_SUCCESS) {
        CONN_ERR(conn, "Oracle rollback: %s", ora_get_error());
        return KDBC_ERROR;
    }
    return KDBC_OK;
}

/* ========================================================================
 * Runtime GK strategy based on server version
 * ======================================================================== */

static kdbc_gk_strategy ora_conn_gk_strategy(void *native) {
    ora_conn *oc = (ora_conn *)native;
    const char *rel; unsigned int relLen;
    dpiVersionInfo vi;
    if (p_conn_getServerVersion(oc->conn, &rel, &relLen, &vi) == DPI_SUCCESS) {
        /* Oracle 12c+ (version 12.x) supports RETURNING INTO for identity columns */
        if (vi.versionNum >= 12)
            return KDBC_GK_BY_NAME;
    }
    /* Oracle 11g and older: use sequences (no auto generated keys) */
    return KDBC_GK_NONE;
}

/* ========================================================================
 * Metadata
 * ======================================================================== */

static void ora_get_product_name(void *native, char *buf, size_t sz) {
    (void)native;
    snprintf(buf, sz, "Oracle Database");
}

static void ora_get_product_version(void *native, char *buf, size_t sz) {
    ora_conn *oc = (ora_conn *)native;
    const char *rel = NULL;
    unsigned int relLen = 0;
    dpiVersionInfo vi;
    if (p_conn_getServerVersion(oc->conn, &rel, &relLen, &vi) == DPI_SUCCESS && rel) {
        size_t n = relLen < sz - 1 ? relLen : sz - 1;
        memcpy(buf, rel, n);
        buf[n] = '\0';
    } else {
        snprintf(buf, sz, "unknown");
    }
}

static int ora_get_major_version(void *native) {
    ora_conn *oc = (ora_conn *)native;
    const char *rel; unsigned int relLen;
    dpiVersionInfo vi;
    if (p_conn_getServerVersion(oc->conn, &rel, &relLen, &vi) == DPI_SUCCESS)
        return vi.versionNum;
    return 0;
}

static int ora_get_minor_version(void *native) {
    ora_conn *oc = (ora_conn *)native;
    const char *rel; unsigned int relLen;
    dpiVersionInfo vi;
    if (p_conn_getServerVersion(oc->conn, &rel, &relLen, &vi) == DPI_SUCCESS)
        return vi.releaseNum;
    return 0;
}

/* ========================================================================
 * Statement preparation
 * ======================================================================== */

typedef struct {
    ora_conn  *oc;
    dpiStmt   *stmt;
    int        param_count;
    dpiVar   **vars;                /* bound variables (one per param) */
    dpiData  **var_data;            /* dpiData pointer into each var's internal buffer */
    unsigned int *var_native_types; /* DPI_NATIVE_TYPE_* per slot, for OUT readback */
    /* RETURNING INTO support (Oracle 12c+) */
    dpiVar    *ret_var;    /* OUT variable for RETURNING INTO :ret */
    int        has_returning;
} ora_stmt_data;

static void *ora_prepare(kdbc_conn *conn, const char *native_sql,
                         const char **ret_cols, int n_ret_cols,
                         int generated_keys_requested,
                         char *err, size_t err_size) {
    (void)generated_keys_requested; /* Oracle uses BY_NAME with explicit RETURNING INTO */
    ora_conn *oc = (ora_conn *)conn->native;

    ora_stmt_data *sd = (ora_stmt_data *)calloc(1, sizeof(ora_stmt_data));
    if (!sd) { snprintf(err, err_size, "Out of memory"); return NULL; }
    sd->oc = oc;

    /* If generated keys requested, append RETURNING col INTO :kdbc_ret
     * This works on Oracle 12c+. On 11g the caller should not request
     * generated keys (SqlDialect returns NONE for ORACLE_OLD). */
    char *final_sql = NULL;
    if (ret_cols && n_ret_cols > 0) {
        size_t len = strlen(native_sql) + 64;
        for (int i = 0; i < n_ret_cols; i++)
            len += strlen(ret_cols[i]) + 2;
        final_sql = (char *)malloc(len);
        if (!final_sql) {
            snprintf(err, err_size, "Out of memory");
            free(sd);
            return NULL;
        }
        strcpy(final_sql, native_sql);
        strcat(final_sql, " RETURNING ");
        for (int i = 0; i < n_ret_cols; i++) {
            if (i > 0) strcat(final_sql, ", ");
            strcat(final_sql, ret_cols[i]);
        }
        strcat(final_sql, " INTO :kdbc_ret");
        sd->has_returning = 1;
    }

    const char *sql_to_prepare = final_sql ? final_sql : native_sql;
    if (p_conn_prepareStmt(oc->conn, 0, sql_to_prepare,
                           (unsigned int)strlen(sql_to_prepare),
                           NULL, 0, &sd->stmt) != DPI_SUCCESS) {
        snprintf(err, err_size, "Oracle prepare: %s", ora_get_error());
        free(final_sql);
        free(sd);
        return NULL;
    }
    /* Ask ODPI-C for the authoritative bind count — it parses the statement
     * the same way the server will, so it handles multi-digit :NN, quoted
     * strings, and comments correctly. The old handrolled `:1..:9` scan
     * silently miscounted statements with 10+ parameters. */
    {
        uint32_t bind_count = 0;
        if (p_stmt_getBindCount(sd->stmt, &bind_count) == DPI_SUCCESS) {
            /* When RETURNING INTO :kdbc_ret was injected, ODPI-C will report
             * N+1 binds — subtract the synthetic OUT binding. */
            int n = (int)bind_count;
            if (sd->has_returning && n > 0) n--;
            sd->param_count = n;
        } else {
            sd->param_count = 0;
        }
    }
    free(final_sql);
    if (sd->param_count > 0) {
        sd->vars = (dpiVar **)calloc(sd->param_count, sizeof(dpiVar *));
        sd->var_data = (dpiData **)calloc(sd->param_count, sizeof(dpiData *));
        sd->var_native_types = (unsigned int *)calloc(sd->param_count, sizeof(unsigned int));
    }

    /* Create OUT variable for RETURNING INTO.
     *
     * Bound as VARCHAR2(4000)/BYTES rather than NUMBER/INT64 so it handles
     * any PK type the caller may RETURNING — NUMBER (auto-increment ID),
     * VARCHAR2 (UUID / natural key / user-generated string), ROWID, CHAR,
     * DATE (formatted by Oracle using NLS_DATE_FORMAT). Oracle implicitly
     * converts the underlying column value to its canonical text form on
     * the way out, so the receive buffer just has to be wide enough.
     *
     * 4000 is Oracle's VARCHAR2 max without MAX_STRING_SIZE=EXTENDED — long
     * enough for any realistic PK representation (36-char UUID, 18-byte
     * ROWID, 38-digit NUMBER, etc.). */
    if (sd->has_returning) {
        dpiData *ret_data = NULL;
        if (p_conn_newVar(oc->conn, DPI_ORACLE_TYPE_VARCHAR, DPI_NATIVE_TYPE_BYTES,
                          1, 4000, 1 /* sizeIsBytes */, 0, NULL,
                          &sd->ret_var, &ret_data) != DPI_SUCCESS) {
            snprintf(err, err_size, "Oracle RETURNING var: %s", ora_get_error());
            /* Non-fatal: just disable RETURNING */
            sd->has_returning = 0;
            sd->ret_var = NULL;
        } else {
            /* Bind the OUT variable at position param_count + 1 */
            if (p_stmt_bindByPos(sd->stmt, (unsigned int)(sd->param_count + 1),
                                 sd->ret_var) != DPI_SUCCESS) {
                snprintf(err, err_size, "Oracle bind RETURNING: %s", ora_get_error());
                p_var_release(sd->ret_var);
                sd->ret_var = NULL;
                sd->has_returning = 0;
            }
        }
    }

    return sd;
}

static void ora_stmt_close_fn(void *native_stmt, void *native_conn) {
    (void)native_conn;
    ora_stmt_data *sd = (ora_stmt_data *)native_stmt;
    if (!sd) return;
    if (sd->vars) {
        for (int i = 0; i < sd->param_count; i++)
            if (sd->vars[i]) p_var_release(sd->vars[i]);
        free(sd->vars);
    }
    free(sd->var_data);
    free(sd->var_native_types);
    if (sd->ret_var) p_var_release(sd->ret_var);
    p_stmt_close(sd->stmt, NULL, 0);
    p_stmt_release(sd->stmt);
    free(sd);
}

/* ========================================================================
 * Parameter binding via dpiVar
 * ======================================================================== */

/**
 * Create a dpiVar, bind it to the statement, and return the dpiData pointer
 * for direct value assignment. ODPI-C doesn't have setFromInt64/Double/Float;
 * values are written directly to the dpiData struct returned by dpiConn_newVar.
 */
static dpiData *ora_create_and_bind_var(kdbc_stmt *stmt, int idx,
                                        unsigned int oraType, unsigned int natType,
                                        unsigned int size) {
    ora_stmt_data *sd = (ora_stmt_data *)stmt->native;
    int i = idx - 1;

    /* Release old var if any */
    if (sd->vars[i]) { p_var_release(sd->vars[i]); sd->vars[i] = NULL; }

    dpiData *data = NULL;
    if (p_conn_newVar(sd->oc->conn, oraType, natType, 1, size, 0, 0,
                      NULL, &sd->vars[i], &data) != DPI_SUCCESS) {
        STMT_ERR(stmt, "Oracle newVar: %s", ora_get_error());
        return NULL;
    }

    if (p_stmt_bindByPos(sd->stmt, (unsigned int)idx, sd->vars[i]) != DPI_SUCCESS) {
        STMT_ERR(stmt, "Oracle bind: %s", ora_get_error());
        p_var_release(sd->vars[i]);
        sd->vars[i] = NULL;
        return NULL;
    }

    if (sd->var_native_types) sd->var_native_types[i] = natType;
    if (sd->var_data) sd->var_data[i] = data;
    return data;
}

static int ora_bind_null(kdbc_stmt *stmt, int idx) {
    dpiData *data = ora_create_and_bind_var(stmt, idx, DPI_ORACLE_TYPE_VARCHAR,
                                            DPI_NATIVE_TYPE_BYTES, 1);
    if (!data) return KDBC_ERROR;
    data->isNull = 1;
    return KDBC_OK;
}

static int ora_bind_int(kdbc_stmt *stmt, int idx, int val) {
    /* Native int32 → NUMBER via DPI_NATIVE_TYPE_INT64 is exact and server-side
     * parsing cost-free. The previous text-based workaround was a misdiagnosis
     * of a read-side precision problem (NUMBER columns were being returned as
     * DOUBLE, losing low bits); that is now fixed by defining NUMBER result
     * columns as BYTES in ora_execute_query. */
    dpiData *data = ora_create_and_bind_var(stmt, idx, DPI_ORACLE_TYPE_NUMBER,
                                            DPI_NATIVE_TYPE_INT64, 0);
    if (!data) return KDBC_ERROR;
    data->isNull = 0;
    data->value.asInt64 = (int64_t)val;
    return KDBC_OK;
}

static int ora_bind_long(kdbc_stmt *stmt, int idx, int64_t val) {
    dpiData *data = ora_create_and_bind_var(stmt, idx, DPI_ORACLE_TYPE_NUMBER,
                                            DPI_NATIVE_TYPE_INT64, 0);
    if (!data) return KDBC_ERROR;
    data->isNull = 0;
    data->value.asInt64 = val;
    return KDBC_OK;
}

static int ora_bind_double(kdbc_stmt *stmt, int idx, double val) {
    dpiData *data = ora_create_and_bind_var(stmt, idx, DPI_ORACLE_TYPE_NATIVE_DOUBLE,
                                            DPI_NATIVE_TYPE_DOUBLE, 0);
    if (!data) return KDBC_ERROR;
    data->isNull = 0;
    data->value.asDouble = val;
    return KDBC_OK;
}

/* Oracle VARCHAR2 bind parameters cap at 4000 bytes (32767 with MAX_STRING_SIZE=EXTENDED).
 * For larger strings we must bind via a temporary CLOB. */
#define ORA_VARCHAR_MAX 4000u

static int ora_bind_clob(kdbc_stmt *stmt, int idx, const char *val, unsigned int len) {
    ora_stmt_data *sd = (ora_stmt_data *)stmt->native;
    dpiLob *lob = NULL;
    if (p_conn_newTempLob(sd->oc->conn, DPI_ORACLE_TYPE_CLOB, &lob) != DPI_SUCCESS) {
        STMT_ERR(stmt, "Oracle newTempLob(CLOB): %s", ora_get_error());
        return KDBC_ERROR;
    }
    if (p_lob_setFromBytes(lob, val, (uint64_t)len) != DPI_SUCCESS) {
        STMT_ERR(stmt, "Oracle LOB setFromBytes: %s", ora_get_error());
        p_lob_release(lob);
        return KDBC_ERROR;
    }
    dpiData *data = ora_create_and_bind_var(stmt, idx, DPI_ORACLE_TYPE_CLOB,
                                            DPI_NATIVE_TYPE_LOB, 0);
    if (!data) { p_lob_release(lob); return KDBC_ERROR; }
    if (p_var_setFromLob(sd->vars[idx - 1], 0, lob) != DPI_SUCCESS) {
        STMT_ERR(stmt, "Oracle setFromLob(CLOB): %s", ora_get_error());
        p_lob_release(lob);
        return KDBC_ERROR;
    }
    /* The var retains its own reference — we can release ours immediately. */
    p_lob_release(lob);
    return KDBC_OK;
}

static int ora_bind_string(kdbc_stmt *stmt, int idx, const char *val) {
    ora_stmt_data *sd = (ora_stmt_data *)stmt->native;
    unsigned int len = (unsigned int)strlen(val);
    if (len > ORA_VARCHAR_MAX) return ora_bind_clob(stmt, idx, val, len);
    /* Bind as NVARCHAR (NCHAR charform) rather than VARCHAR. This matters on
     * instances whose NLS_CHARACTERSET is a legacy single-byte codepage
     * (e.g. EL8ISO8859P7 on a classic Greek 11g deployment): VARCHAR binds
     * would make the server transcode our UTF-8 bytes through the DB
     * charset first, silently replacing any code point outside that
     * codepage with '?' BEFORE storage — even when the target column is
     * NVARCHAR2 (which is backed by NLS_NCHAR_CHARACTERSET = AL16UTF16 and
     * can hold the full Unicode repertoire).
     *
     * Binding as NVARCHAR tells ODPI-C to pair the parameter with the NCHAR
     * charform, whose client-side encoding we already pinned to UTF-8 at
     * context creation (common.nencoding = "UTF-8"). That gives lossless
     * UTF-8 ↔ AL16UTF16 transcoding on the wire and preserves the full
     * Unicode repertoire when the target column is NVARCHAR2. For VARCHAR2
     * targets Oracle performs an implicit NCHAR → CHAR conversion at
     * insert time: characters representable in the DB charset are stored
     * as-is, characters outside it are replaced with '?' — which is
     * exactly the behaviour that was already in effect before this change,
     * so no VARCHAR2 regression. */
    dpiData *data = ora_create_and_bind_var(stmt, idx, DPI_ORACLE_TYPE_NVARCHAR,
                                            DPI_NATIVE_TYPE_BYTES, len + 1);
    if (!data) return KDBC_ERROR;
    if (p_var_setFromBytes(sd->vars[idx - 1], 0, val, len) != DPI_SUCCESS) {
        STMT_ERR(stmt, "Oracle setBytes: %s", ora_get_error());
        return KDBC_ERROR;
    }
    return KDBC_OK;
}

/* Oracle RAW bind caps at 2000 bytes. Larger blobs bind via a temporary BLOB. */
#define ORA_RAW_MAX 2000u

static int ora_bind_blob_lob(kdbc_stmt *stmt, int idx, const void *data, size_t len) {
    ora_stmt_data *sd = (ora_stmt_data *)stmt->native;
    dpiLob *lob = NULL;
    if (p_conn_newTempLob(sd->oc->conn, DPI_ORACLE_TYPE_BLOB, &lob) != DPI_SUCCESS) {
        STMT_ERR(stmt, "Oracle newTempLob(BLOB): %s", ora_get_error());
        return KDBC_ERROR;
    }
    if (p_lob_setFromBytes(lob, (const char *)data, (uint64_t)len) != DPI_SUCCESS) {
        STMT_ERR(stmt, "Oracle LOB setFromBytes(BLOB): %s", ora_get_error());
        p_lob_release(lob);
        return KDBC_ERROR;
    }
    dpiData *dd = ora_create_and_bind_var(stmt, idx, DPI_ORACLE_TYPE_BLOB,
                                          DPI_NATIVE_TYPE_LOB, 0);
    if (!dd) { p_lob_release(lob); return KDBC_ERROR; }
    if (p_var_setFromLob(sd->vars[idx - 1], 0, lob) != DPI_SUCCESS) {
        STMT_ERR(stmt, "Oracle setFromLob(BLOB): %s", ora_get_error());
        p_lob_release(lob);
        return KDBC_ERROR;
    }
    p_lob_release(lob);
    return KDBC_OK;
}

static int ora_bind_blob(kdbc_stmt *stmt, int idx, const void *data, size_t len) {
    if (len > ORA_RAW_MAX) return ora_bind_blob_lob(stmt, idx, data, len);
    ora_stmt_data *sd = (ora_stmt_data *)stmt->native;
    dpiData *dd = ora_create_and_bind_var(stmt, idx, DPI_ORACLE_TYPE_RAW,
                                          DPI_NATIVE_TYPE_BYTES, (unsigned int)len);
    if (!dd) return KDBC_ERROR;
    if (p_var_setFromBytes(sd->vars[idx - 1], 0, (const char *)data,
                           (unsigned int)len) != DPI_SUCCESS) {
        STMT_ERR(stmt, "Oracle setBlob: %s", ora_get_error());
        return KDBC_ERROR;
    }
    return KDBC_OK;
}

/* Date/time binding via native dpiTimestamp */
static int ora_bind_timestamp(kdbc_stmt *stmt, int idx,
                              int year, int month, int day,
                              int hour, int minute, int second, int usec) {
    dpiData *data = ora_create_and_bind_var(stmt, idx, DPI_ORACLE_TYPE_TIMESTAMP,
                                            DPI_NATIVE_TYPE_TIMESTAMP, 0);
    if (!data) return KDBC_ERROR;
    data->isNull = 0;
    data->value.asTimestamp.year = (int16_t)year;
    data->value.asTimestamp.month = (uint8_t)month;
    data->value.asTimestamp.day = (uint8_t)day;
    data->value.asTimestamp.hour = (uint8_t)hour;
    data->value.asTimestamp.minute = (uint8_t)minute;
    data->value.asTimestamp.second = (uint8_t)second;
    data->value.asTimestamp.fsecond = (uint32_t)(usec * 1000); /* usec → nanosec */
    data->value.asTimestamp.tzHourOffset = 0;
    data->value.asTimestamp.tzMinuteOffset = 0;
    return KDBC_OK;
}

static int ora_bind_date(kdbc_stmt *stmt, int idx, int year, int month, int day) {
    return ora_bind_timestamp(stmt, idx, year, month, day, 0, 0, 0, 0);
}

static int ora_bind_time(kdbc_stmt *stmt, int idx,
                         int hour, int minute, int second, int usec) {
    /* Oracle doesn't have TIME type, use TIMESTAMP with epoch date */
    return ora_bind_timestamp(stmt, idx, 1970, 1, 1, hour, minute, second, usec);
}

/* Oracle result timestamp retrieval: forward-declared, defined after result set */
static int ora_rs_get_timestamp(kdbc_result *rs, int col,
                                int *year, int *month, int *day,
                                int *hour, int *minute, int *second, int *usec);
static int ora_rs_get_date(kdbc_result *rs, int col, int *year, int *month, int *day);
static int ora_rs_get_time(kdbc_result *rs, int col,
                           int *hour, int *minute, int *second, int *usec);

/* ========================================================================
 * Execution
 * ======================================================================== */

static int ora_execute_update(kdbc_stmt *stmt) {
    ora_stmt_data *sd = (ora_stmt_data *)stmt->native;
    unsigned int mode = sd->oc->autocommit ?
        DPI_MODE_EXEC_COMMIT_ON_SUCCESS : DPI_MODE_EXEC_DEFAULT;

    unsigned int numQueryCols = 0;
    if (p_stmt_execute(sd->stmt, mode, &numQueryCols) != DPI_SUCCESS) {
        STMT_ERR(stmt, "Oracle execute: %s", ora_get_error());
        return KDBC_ERROR;
    }

    unsigned long long rowCount = 0;
    p_stmt_getRowCount(sd->stmt, &rowCount);

    /* Retrieve RETURNING INTO value if available. The var is bound as
     * VARCHAR2/BYTES so the returned data carries Oracle's canonical text
     * representation of whatever type the column actually is. */
    if (sd->has_returning && sd->ret_var && rowCount > 0) {
        unsigned int numElements = 0;
        dpiData *retData = NULL;
        if (p_var_getReturnedData(sd->ret_var, 0, &numElements, &retData) == DPI_SUCCESS
            && numElements > 0 && retData && !retData->isNull) {
            const char *bytes = retData->value.asBytes.ptr;
            unsigned int blen = retData->value.asBytes.length;

            /* Keep the verbatim text form for non-numeric PKs (UUID, VARCHAR2,
             * ROWID, formatted DATE). kdbc_generated_keys prefers this over
             * the int64 form when it's set. */
            free(stmt->generated_key_str);
            stmt->generated_key_str = (char *)malloc((size_t)blen + 1);
            if (stmt->generated_key_str) {
                memcpy(stmt->generated_key_str, bytes, blen);
                stmt->generated_key_str[blen] = '\0';
            }

            /* Also parse as int64 for the common numeric-PK case so callers
             * that go through get_generated_key (vs kdbc_generated_keys) still
             * get a useful value. sscanf silently returns 0 for non-numeric
             * input — acceptable because string-PK callers should use the
             * string path anyway. */
            char buf[64];
            unsigned int n = blen < 63 ? blen : 63;
            memcpy(buf, bytes, n);
            buf[n] = '\0';
            long long v = 0;
            sscanf(buf, "%lld", &v);
            stmt->generated_key = v;
            stmt->has_generated_key = 1;
        }
    }

    return (int)rowCount;
}

typedef struct {
    ora_stmt_data *sd;
    int            col_count;
    char         **col_names;
    unsigned int  *native_types;
    dpiVar       **fetch_vars;  /* dpiStmt_define overrides per column, may contain NULL */
    char           conv_buf[64];
} ora_result_set;

static void *ora_execute_query(kdbc_stmt *stmt, int *out_col_count,
                               char *err, size_t err_size) {
    ora_stmt_data *sd = (ora_stmt_data *)stmt->native;
    unsigned int mode = sd->oc->autocommit ?
        DPI_MODE_EXEC_COMMIT_ON_SUCCESS : DPI_MODE_EXEC_DEFAULT;

    unsigned int numQueryCols = 0;
    if (p_stmt_execute(sd->stmt, mode, &numQueryCols) != DPI_SUCCESS) {
        snprintf(err, err_size, "Oracle execute: %s", ora_get_error());
        return NULL;
    }

    if (numQueryCols == 0) {
        p_stmt_getNumQueryColumns(sd->stmt, &numQueryCols);
    }

    ora_result_set *ors = (ora_result_set *)calloc(1, sizeof(ora_result_set));
    if (!ors) { snprintf(err, err_size, "Out of memory"); return NULL; }

    ors->sd = sd;
    ors->col_count = (int)numQueryCols;
    ors->col_names = (char **)calloc(numQueryCols, sizeof(char *));
    ors->native_types = (unsigned int *)calloc(numQueryCols, sizeof(unsigned int));
    ors->fetch_vars = (dpiVar **)calloc(numQueryCols, sizeof(dpiVar *));

    /* Cache column info and override NUMBER columns to fetch as BYTES so that
     * high-precision values (including Long.MAX_VALUE, BigInteger, arbitrary
     * DECIMAL) round-trip losslessly. ODPI-C otherwise defaults generic NUMBER
     * columns (precision ≥ 0 with scale == 0 and precision > 18, or precision
     * == 0 i.e. "NUMBER") to DPI_NATIVE_TYPE_DOUBLE, which silently loses low
     * bits near 2^53. BYTES fetch returns the canonical NUMBER text exactly
     * as Oracle stored it — callers then parse via int64/double/BigDecimal as
     * needed. */
    for (unsigned int i = 1; i <= numQueryCols; i++) {
        dpiQueryInfo qi;
        memset(&qi, 0, sizeof(qi));
        if (p_stmt_getQueryInfo(sd->stmt, i, &qi) == DPI_SUCCESS) {
            /* Copy name since qi.name might not be null-terminated */
            char *name = (char *)malloc(qi.nameLength + 1);
            if (name) {
                memcpy(name, qi.name, qi.nameLength);
                name[qi.nameLength] = '\0';
            }
            ors->col_names[i - 1] = name;
            ors->native_types[i - 1] = qi.typeInfo.defaultNativeTypeNum;

            if (qi.typeInfo.oracleTypeNum == DPI_ORACLE_TYPE_NUMBER) {
                /* maxArraySize must match dpiStmt_getFetchArraySize (default
                 * DPI_DEFAULT_FETCH_ARRAY_SIZE = 100 — ODPI-C batch-fetches
                 * that many rows per round-trip). Using 1 here causes the
                 * next fetch to fail with ORA-24335 or an internal mismatch.
                 * Generous size: NUMBER(38) worst case is ~42 bytes. */
                uint32_t fetch_array_size = 100;
                p_stmt_getFetchArraySize(sd->stmt, &fetch_array_size);
                dpiVar *var = NULL;
                dpiData *var_data = NULL;
                if (p_conn_newVar(sd->oc->conn,
                                  DPI_ORACLE_TYPE_NUMBER,
                                  DPI_NATIVE_TYPE_BYTES,
                                  fetch_array_size,
                                  64 /* size in bytes */, 1 /* sizeIsBytes */,
                                  0 /* isArray */, NULL /* objType */,
                                  &var, &var_data) == DPI_SUCCESS) {
                    if (p_stmt_define(sd->stmt, i, var) == DPI_SUCCESS) {
                        ors->fetch_vars[i - 1] = var;
                        ors->native_types[i - 1] = DPI_NATIVE_TYPE_BYTES;
                    } else {
                        p_var_release(var);
                    }
                }
            }
        }
    }

    *out_col_count = (int)numQueryCols;
    return ors;
}

static int ora_get_gen_key(kdbc_stmt *stmt, int64_t *out_key) {
    (void)stmt; (void)out_key;
    STMT_ERR(stmt, "No generated key available (Oracle may use sequences)");
    return KDBC_ERROR;
}

/* ========================================================================
 * Result set
 * ======================================================================== */

static int ora_rs_next(kdbc_result *rs) {
    ora_result_set *ors = (ora_result_set *)rs->native;
    int found = 0;
    unsigned int bufIdx = 0;
    if (p_stmt_fetch(ors->sd->stmt, &found, &bufIdx) != DPI_SUCCESS) {
        RS_ERR(rs, "Oracle fetch: %s", ora_get_error());
        return KDBC_ERROR;
    }
    return found ? 1 : 0;
}

static const char *ora_rs_col_name(void *native_rs, int col) {
    ora_result_set *ors = (ora_result_set *)native_rs;
    if (col < 1 || col > ors->col_count) return NULL;
    return ors->col_names[col - 1];
}

/* Helper to get dpiData for current row */
static dpiData *ora_get_data(ora_result_set *ors, int col, unsigned int *natType) {
    dpiData *data = NULL;
    if (p_stmt_getQueryValue(ors->sd->stmt, (unsigned int)col, natType, &data) != DPI_SUCCESS)
        return NULL;
    return data;
}

static int ora_rs_is_null(kdbc_result *rs, int col) {
    ora_result_set *ors = (ora_result_set *)rs->native;
    unsigned int nt;
    dpiData *data = ora_get_data(ors, col, &nt);
    rs->last_null = (!data || data->isNull);
    return rs->last_null;
}

static int64_t ora_rs_get_long(kdbc_result *rs, int col) {
    ora_result_set *ors = (ora_result_set *)rs->native;
    unsigned int nt;
    dpiData *data = ora_get_data(ors, col, &nt);
    if (!data || data->isNull) { rs->last_null = 1; return 0; }
    rs->last_null = 0;

    switch (nt) {
        case DPI_NATIVE_TYPE_INT64:    return data->value.asInt64;
        case DPI_NATIVE_TYPE_UINT64:   return (int64_t)data->value.asUint64;
        case DPI_NATIVE_TYPE_DOUBLE:   return (int64_t)data->value.asDouble;
        case DPI_NATIVE_TYPE_FLOAT:    return (int64_t)data->value.asFloat;
        case DPI_NATIVE_TYPE_BYTES: {
            /* NUMBER columns are fetched as BYTES (see ora_execute_query).
             * Parse the canonical text representation — lossless for any
             * value that fits in int64, including Long.MAX/MIN_VALUE. */
            char buf[64];
            unsigned int n = data->value.asBytes.length;
            if (n > 63) n = 63;
            memcpy(buf, data->value.asBytes.ptr, n);
            buf[n] = '\0';
            long long v = 0;
            sscanf(buf, "%lld", &v);
            return v;
        }
        default: return 0;
    }
}

static double ora_rs_get_double(kdbc_result *rs, int col) {
    ora_result_set *ors = (ora_result_set *)rs->native;
    unsigned int nt;
    dpiData *data = ora_get_data(ors, col, &nt);
    if (!data || data->isNull) { rs->last_null = 1; return 0.0; }
    rs->last_null = 0;

    switch (nt) {
        case DPI_NATIVE_TYPE_DOUBLE: return data->value.asDouble;
        case DPI_NATIVE_TYPE_FLOAT:  return (double)data->value.asFloat;
        case DPI_NATIVE_TYPE_INT64:  return (double)data->value.asInt64;
        case DPI_NATIVE_TYPE_BYTES: {
            char buf[64];
            unsigned int n = data->value.asBytes.length;
            if (n > 63) n = 63;
            memcpy(buf, data->value.asBytes.ptr, n);
            buf[n] = '\0';
            return strtod(buf, NULL);
        }
        default: return 0.0;
    }
}

/* Read the entire contents of a dpiLob into rs->str_buf. Returns number of bytes
 * written (not including null terminator) or -1 on error.
 *
 * For CLOBs, dpiLob_getSize returns the size in characters and dpiLob_readBytes
 * takes offset/amount in characters. The output buffer must accommodate the UTF-8
 * encoding of those characters — worst case 4 bytes per character. BLOBs are
 * byte-oriented so size == byte count directly. */
static long ora_read_lob_into_strbuf(kdbc_result *rs, dpiLob *lob) {
    uint64_t size = 0;
    if (p_lob_getSize(lob, &size) != DPI_SUCCESS) {
        RS_ERR(rs, "Oracle LOB getSize: %s", ora_get_error());
        return -1;
    }
    /* Allocate enough for CLOB worst case (4 bytes per char, UTF-8 max) plus NUL. */
    size_t buf_size = (size_t)size * 4 + 1;
    if (kdbc_ensure_strbuf(rs, buf_size) != 0) return -1;
    if (size == 0) {
        rs->str_buf[0] = '\0';
        return 0;
    }
    uint64_t len = buf_size - 1;  /* bytes available in buffer */
    if (p_lob_readBytes(lob, 1, size, rs->str_buf, &len) != DPI_SUCCESS) {
        RS_ERR(rs, "Oracle LOB readBytes: %s", ora_get_error());
        return -1;
    }
    rs->str_buf[len] = '\0';
    return (long)len;
}

/* Format a scalar dpiData value into `buf` as UTF-8 text, for the narrow
 * subset of native types we bind via ora_create_and_bind_var. Returns a
 * pointer to the text (either `buf` for numeric/boolean, or the dpiData's
 * asBytes.ptr for BYTES) and writes the length into *out_len. NULL on
 * unsupported types (caller should treat as "cannot stringify"). */
static const char *ora_format_scalar(const dpiData *data, unsigned int nt,
                                     char *buf, size_t buf_size, int *out_len) {
    switch (nt) {
        case DPI_NATIVE_TYPE_BYTES:
            *out_len = (int)data->value.asBytes.length;
            return data->value.asBytes.ptr;
        case DPI_NATIVE_TYPE_INT64:
            *out_len = snprintf(buf, buf_size, "%lld", (long long)data->value.asInt64);
            return buf;
        case DPI_NATIVE_TYPE_UINT64:
            *out_len = snprintf(buf, buf_size, "%llu",
                                (unsigned long long)data->value.asUint64);
            return buf;
        case DPI_NATIVE_TYPE_DOUBLE:
            *out_len = snprintf(buf, buf_size, "%.17g", data->value.asDouble);
            return buf;
        case DPI_NATIVE_TYPE_FLOAT:
            *out_len = snprintf(buf, buf_size, "%.7g", (double)data->value.asFloat);
            return buf;
        case DPI_NATIVE_TYPE_BOOLEAN:
            if (data->value.asBoolean) { *out_len = 4; return "true"; }
            else                       { *out_len = 5; return "false"; }
        default:
            *out_len = 0;
            return NULL;
    }
}

static const char *ora_rs_get_string(kdbc_result *rs, int col) {
    ora_result_set *ors = (ora_result_set *)rs->native;
    unsigned int nt;
    dpiData *data = ora_get_data(ors, col, &nt);
    if (!data || data->isNull) { rs->last_null = 1; return NULL; }
    rs->last_null = 0;

    /* BYTES is large-arbitrary (CLOB-ish) and needs the per-result str_buf
     * so the returned pointer stays valid across subsequent column getters.
     * LOB needs the same buffer for its streamed read. Everything else is
     * small enough to land in the per-result conv_buf via ora_format_scalar. */
    if (nt == DPI_NATIVE_TYPE_BYTES) {
        unsigned int n = data->value.asBytes.length;
        if (kdbc_ensure_strbuf(rs, n + 1) != 0) return NULL;
        memcpy(rs->str_buf, data->value.asBytes.ptr, n);
        rs->str_buf[n] = '\0';
        return rs->str_buf;
    }
    if (nt == DPI_NATIVE_TYPE_LOB) {
        if (ora_read_lob_into_strbuf(rs, data->value.asLOB) < 0) return NULL;
        return rs->str_buf;
    }
    int out_len = 0;
    return ora_format_scalar(data, nt, ors->conv_buf, sizeof(ors->conv_buf), &out_len);
}

static const void *ora_rs_get_blob(kdbc_result *rs, int col, size_t *out_len) {
    ora_result_set *ors = (ora_result_set *)rs->native;
    unsigned int nt;
    dpiData *data = ora_get_data(ors, col, &nt);
    if (!data || data->isNull) { rs->last_null = 1; *out_len = 0; return NULL; }
    rs->last_null = 0;
    if (nt == DPI_NATIVE_TYPE_BYTES) {
        *out_len = data->value.asBytes.length;
        return data->value.asBytes.ptr;
    }
    if (nt == DPI_NATIVE_TYPE_LOB) {
        long n = ora_read_lob_into_strbuf(rs, data->value.asLOB);
        if (n < 0) { *out_len = 0; return NULL; }
        *out_len = (size_t)n;
        return rs->str_buf;
    }
    *out_len = 0;
    return NULL;
}

static void ora_rs_close(void *native_rs) {
    ora_result_set *ors = (ora_result_set *)native_rs;
    if (!ors) return;
    if (ors->col_names) {
        for (int i = 0; i < ors->col_count; i++)
            free(ors->col_names[i]);
        free(ors->col_names);
    }
    if (ors->fetch_vars) {
        for (int i = 0; i < ors->col_count; i++)
            if (ors->fetch_vars[i]) p_var_release(ors->fetch_vars[i]);
        free(ors->fetch_vars);
    }
    free(ors->native_types);
    free(ors);
}

/* ========================================================================
 * Date/time result retrieval implementations
 * ======================================================================== */

static int ora_rs_get_timestamp(kdbc_result *rs, int col,
                                int *year, int *month, int *day,
                                int *hour, int *minute, int *second, int *usec) {
    ora_result_set *ors = (ora_result_set *)rs->native;
    unsigned int nt;
    dpiData *data = ora_get_data(ors, col, &nt);
    if (!data || data->isNull) { rs->last_null = 1; RS_ERR(rs, "Oracle: NULL timestamp"); return KDBC_ERROR; }
    rs->last_null = 0;
    if (nt == DPI_NATIVE_TYPE_TIMESTAMP) {
        *year = data->value.asTimestamp.year;
        *month = data->value.asTimestamp.month;
        *day = data->value.asTimestamp.day;
        *hour = data->value.asTimestamp.hour;
        *minute = data->value.asTimestamp.minute;
        *second = data->value.asTimestamp.second;
        *usec = (int)(data->value.asTimestamp.fsecond / 1000);
        return KDBC_OK;
    }
    if (nt == DPI_NATIVE_TYPE_BYTES) {
        unsigned int n = data->value.asBytes.length;
        char buf[64];
        if (n > 63) n = 63;
        memcpy(buf, data->value.asBytes.ptr, n);
        buf[n] = '\0';
        *usec = 0;
        if (sscanf(buf, "%d-%d-%d %d:%d:%d", year, month, day, hour, minute, second) >= 6)
            return KDBC_OK;
    }
    RS_ERR(rs, "Oracle: cannot parse timestamp (native type %u)", nt);
    return KDBC_ERROR;
}

static int ora_rs_get_date(kdbc_result *rs, int col, int *year, int *month, int *day) {
    int h, m, s, us;
    return ora_rs_get_timestamp(rs, col, year, month, day, &h, &m, &s, &us);
}

static int ora_rs_get_time(kdbc_result *rs, int col,
                           int *hour, int *minute, int *second, int *usec) {
    int y, mo, d;
    return ora_rs_get_timestamp(rs, col, &y, &mo, &d, hour, minute, second, usec);
}

/* ========================================================================
 * Callable statements
 *
 * The core rewrites stored procedure calls to `BEGIN proc(:1, :2, :3, :4); END;`
 * and then through the normal prepare/bind path. ODPI-C's dpiStmt_execute fills
 * OUT and INOUT dpiVars automatically during execute — the driver does not need
 * an explicit OUT direction flag; ODPI-C figures it out from the PL/SQL itself.
 *
 * Flow:
 *  1. At bind time, IN / INOUT params already have a dpiVar bound via
 *     ora_create_and_bind_var (via the normal bind_int / bind_string path).
 *  2. Pure OUT params have `out_params[i] == 1` but no bound var yet (the
 *     Kotlin-side `registerOutParameter` only flips the flag). We detect
 *     these and bind a default VARCHAR2(4000)/BYTES var — Oracle implicitly
 *     converts any OUT column value to text for us.
 *  3. Execute the PL/SQL block via dpiStmt_execute.
 *  4. For each OUT/INOUT slot, read the resulting value via dpiData and
 *     cache it as a string in stmt->out_values[] so the core's
 *     kdbc_call_get_long / kdbc_call_get_string helpers can parse it.
 *
 * Format buffer is shared with a stack scratch area — values large enough to
 * overflow (CLOB-sized outputs) would need special handling, but the current
 * 4000-byte VARCHAR2 buffer covers every realistic scalar OUT param.
 * ======================================================================== */

static int ora_call_execute(kdbc_stmt *stmt) {
    ora_stmt_data *sd = (ora_stmt_data *)stmt->native;

    /* Ensure every OUT slot has a bound dpiVar. Pure OUT params were flagged
     * via out_params[i]=1 but never went through a bind_X call, so sd->vars[i]
     * is still NULL. Create a default VARCHAR2(4000)/BYTES receive buffer —
     * Oracle implicitly converts any scalar OUT column value to text for us.
     * INOUT params already have a typed var from the IN bind and we leave
     * them alone; ODPI-C populates the same var during execute. */
    if (stmt->out_params) {
        for (int i = 0; i < sd->param_count; i++) {
            if (stmt->out_params[i] && sd->vars[i] == NULL) {
                dpiData *data = ora_create_and_bind_var(
                    stmt, i + 1, DPI_ORACLE_TYPE_VARCHAR, DPI_NATIVE_TYPE_BYTES, 4000);
                if (!data) return KDBC_ERROR;
                data->isNull = 1;
            }
        }
    }

    unsigned int mode = sd->oc->autocommit ?
        DPI_MODE_EXEC_COMMIT_ON_SUCCESS : DPI_MODE_EXEC_DEFAULT;
    unsigned int numQueryCols = 0;
    if (p_stmt_execute(sd->stmt, mode, &numQueryCols) != DPI_SUCCESS) {
        STMT_ERR(stmt, "Oracle execute: %s", ora_get_error());
        return KDBC_ERROR;
    }

    /* Read back OUT / INOUT values and cache them as strings in out_values[].
     *
     * For PL/SQL OUT params, ODPI-C writes the result directly into the
     * dpiData struct we obtained at dpiConn_newVar time — that's what
     * sd->var_data[i] points to. (dpiVar_getReturnedData is specifically for
     * DML RETURNING clauses, not for PL/SQL OUT binds.)
     *
     * We dispatch on var_native_types[i] to read the right union member. */
    if (stmt->out_params && stmt->out_values && sd->var_native_types && sd->var_data) {
        for (int i = 0; i < sd->param_count; i++) {
            if (!stmt->out_params[i] || !sd->var_data[i]) continue;

            dpiData *data = sd->var_data[i];
            free(stmt->out_values[i]);
            stmt->out_values[i] = NULL;
            if (data->isNull) continue;

            char buf[64];
            int srclen = 0;
            const char *src = ora_format_scalar(data, sd->var_native_types[i],
                                                buf, sizeof(buf), &srclen);
            if (src && srclen > 0) {
                stmt->out_values[i] = (char *)malloc((size_t)srclen + 1);
                if (stmt->out_values[i]) {
                    memcpy(stmt->out_values[i], src, (size_t)srclen);
                    stmt->out_values[i][srclen] = '\0';
                }
            }
        }
    }

    return 1;
}

/* ========================================================================
 * Driver vtable
 * ======================================================================== */

static const kdbc_driver_vtable oracle_vtable = {
    .name               = "Oracle",
    .gk_strategy        = KDBC_GK_BY_NAME, /* 12c+ uses RETURNING; 11g falls back to NONE at runtime */
    .supports_release_savepoint = 0,
    .load               = ora_load,
    .loaded             = ora_loaded,
    .connect            = ora_connect,
    .close              = ora_close,
    .cancel             = ora_cancel,
    .exec_direct        = NULL,
    .query_direct       = NULL,
    .set_autocommit     = ora_set_autocommit,
    .commit             = ora_commit,
    .rollback           = ora_rollback,
    .get_product_name   = ora_get_product_name,
    .get_product_version = ora_get_product_version,
    .get_major_version  = ora_get_major_version,
    .get_minor_version  = ora_get_minor_version,
    .prepare            = ora_prepare,
    .stmt_close         = ora_stmt_close_fn,
    .bind_null          = ora_bind_null,
    .bind_int           = ora_bind_int,
    .bind_long          = ora_bind_long,
    .bind_double        = ora_bind_double,
    .bind_string        = ora_bind_string,
    .bind_blob          = ora_bind_blob,
    .bind_timestamp     = ora_bind_timestamp,
    .bind_date          = ora_bind_date,
    .bind_time          = ora_bind_time,
    .execute_update     = ora_execute_update,
    .execute_query      = ora_execute_query,
    .get_generated_key  = ora_get_gen_key,
    .rs_next            = ora_rs_next,
    .rs_col_name        = ora_rs_col_name,
    .rs_col_label       = NULL,
    .rs_is_null         = ora_rs_is_null,
    .rs_get_long        = ora_rs_get_long,
    .rs_get_double      = ora_rs_get_double,
    .rs_get_string      = ora_rs_get_string,
    .rs_get_blob        = ora_rs_get_blob,
    .rs_get_timestamp   = ora_rs_get_timestamp,
    .rs_get_date        = ora_rs_get_date,
    .rs_get_time        = ora_rs_get_time,
    .rs_close           = ora_rs_close,
    .stmt_reset         = NULL,
    .conn_gk_strategy   = ora_conn_gk_strategy,
    .prepare_call       = NULL,
    .call_execute       = ora_call_execute,
    .call_get_out       = NULL,
};

void kdbc_register_oracle(void) {
    kdbc_register_driver(KDBC_ORACLE, &oracle_vtable);
}
