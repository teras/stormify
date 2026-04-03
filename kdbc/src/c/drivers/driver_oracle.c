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

/* ========================================================================
 * Function pointer types
 * ======================================================================== */

typedef int (*fn_dpiContext_createWithParams)(unsigned int, unsigned int, void *,
                                              dpiContext **, dpiErrorInfo *);
typedef void (*fn_dpiContext_getError)(const dpiContext *, dpiErrorInfo *);
typedef int (*fn_dpiConn_create)(const dpiContext *,
                                 const char *, unsigned int,
                                 const char *, unsigned int,
                                 const char *, unsigned int,
                                 void *, void *, dpiConn **);
typedef int (*fn_dpiConn_close)(dpiConn *, unsigned int, const char *, unsigned int);
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
typedef int (*fn_dpiStmt_close)(dpiStmt *, const char *, unsigned int);
typedef int (*fn_dpiStmt_release)(dpiStmt *);
typedef int (*fn_dpiVar_release)(dpiVar *);
typedef int (*fn_dpiVar_setFromBytes)(dpiVar *, unsigned int, const char *, unsigned int);
typedef int (*fn_dpiVar_getReturnedData)(dpiVar *, unsigned int, unsigned int *, dpiData **);

/* ========================================================================
 * Loaded function pointers
 * ======================================================================== */

static kdbc_lib_handle lib_handle = NULL;

static fn_dpiContext_createWithParams    p_ctx_create;
static fn_dpiContext_getError            p_ctx_getError;
static fn_dpiConn_create                 p_conn_create;
static fn_dpiConn_close                  p_conn_close;
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
static fn_dpiStmt_close                  p_stmt_close;
static fn_dpiStmt_release                p_stmt_release;
static fn_dpiVar_release                 p_var_release;
static fn_dpiVar_setFromBytes            p_var_setFromBytes;
static fn_dpiVar_getReturnedData         p_var_getReturnedData;

static dpiContext *g_ora_ctx = NULL;

/* ========================================================================
 * Library loading
 * ======================================================================== */

#define ORA_LOAD(var, name) do { \
    var = kdbc_dl_sym(lib_handle, name); \
    if (!var) { kdbc_dl_close(lib_handle); lib_handle = NULL; return 0; } \
} while (0)

static int ora_load(void) {
    if (lib_handle) return 1;

    lib_handle = kdbc_dl_open(KDBC_LIBNAME_NOVER("odpic"), RTLD_LAZY);
    if (!lib_handle) lib_handle = kdbc_dl_open(KDBC_LIBNAME("odpic", "5"), RTLD_LAZY);
    if (!lib_handle) return 0;

    ORA_LOAD(p_ctx_create,              "dpiContext_createWithParams");
    ORA_LOAD(p_ctx_getError,            "dpiContext_getError");
    ORA_LOAD(p_conn_create,             "dpiConn_create");
    ORA_LOAD(p_conn_close,              "dpiConn_close");
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
    ORA_LOAD(p_stmt_close,              "dpiStmt_close");
    ORA_LOAD(p_stmt_release,            "dpiStmt_release");
    ORA_LOAD(p_var_release,             "dpiVar_release");
    ORA_LOAD(p_var_setFromBytes,        "dpiVar_setFromBytes");
    ORA_LOAD(p_var_getReturnedData,  "dpiVar_getReturnedData");

    /* Initialize global ODPI-C context */
    dpiErrorInfo errInfo;
    if (p_ctx_create(DPI_MAJOR_VERSION, DPI_MINOR_VERSION, NULL,
                     &g_ora_ctx, &errInfo) != DPI_SUCCESS) {
        kdbc_dl_close(lib_handle);
        lib_handle = NULL;
        g_ora_ctx = NULL;
        return 0;
    }

    return 1;
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

    if (p_conn_create(g_ora_ctx,
                      user, user ? (unsigned int)strlen(user) : 0,
                      password, password ? (unsigned int)strlen(password) : 0,
                      url, (unsigned int)strlen(url),
                      NULL, NULL, &oc->conn) != DPI_SUCCESS) {
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
    dpiVar   **vars;       /* bound variables (one per param) */
    /* RETURNING INTO support (Oracle 12c+) */
    dpiVar    *ret_var;    /* OUT variable for RETURNING INTO :ret */
    int        has_returning;
} ora_stmt_data;

static void *ora_prepare(kdbc_conn *conn, const char *native_sql,
                         const char **ret_cols, int n_ret_cols,
                         char *err, size_t err_size) {
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
    /* Count input params from the ORIGINAL native_sql (before RETURNING append).
     * native_sql has :1, :2 etc for input params only. */
    sd->param_count = 0;
    for (const char *p = native_sql; *p; p++) {
        if (*p == ':' && p[1] >= '1' && p[1] <= '9') sd->param_count++;
    }
    free(final_sql);
    if (sd->param_count > 0) {
        sd->vars = (dpiVar **)calloc(sd->param_count, sizeof(dpiVar *));
    }

    /* Create OUT variable for RETURNING INTO */
    if (sd->has_returning) {
        dpiData *ret_data = NULL;
        if (p_conn_newVar(oc->conn, DPI_ORACLE_TYPE_NUMBER, DPI_NATIVE_TYPE_INT64,
                          1, 0, 0, 0, NULL, &sd->ret_var, &ret_data) != DPI_SUCCESS) {
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
    return kdbc_bind_long(stmt, idx, (int64_t)val);
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

static int ora_bind_string(kdbc_stmt *stmt, int idx, const char *val) {
    ora_stmt_data *sd = (ora_stmt_data *)stmt->native;
    unsigned int len = (unsigned int)strlen(val);
    dpiData *data = ora_create_and_bind_var(stmt, idx, DPI_ORACLE_TYPE_VARCHAR,
                                            DPI_NATIVE_TYPE_BYTES, len + 1);
    if (!data) return KDBC_ERROR;
    if (p_var_setFromBytes(sd->vars[idx - 1], 0, val, len) != DPI_SUCCESS) {
        STMT_ERR(stmt, "Oracle setBytes: %s", ora_get_error());
        return KDBC_ERROR;
    }
    return KDBC_OK;
}

static int ora_bind_blob(kdbc_stmt *stmt, int idx, const void *data, size_t len) {
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

    /* Retrieve RETURNING INTO value if available */
    if (sd->has_returning && sd->ret_var && rowCount > 0) {
        unsigned int numElements = 0;
        dpiData *retData = NULL;
        if (p_var_getReturnedData(sd->ret_var, 0, &numElements, &retData) == DPI_SUCCESS
            && numElements > 0 && retData && !retData->isNull) {
            /* The returned value is INT64 (we bound as DPI_NATIVE_TYPE_INT64) */
            stmt->generated_key = retData->value.asInt64;
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

    /* Cache column info */
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

static const char *ora_rs_get_string(kdbc_result *rs, int col) {
    ora_result_set *ors = (ora_result_set *)rs->native;
    unsigned int nt;
    dpiData *data = ora_get_data(ors, col, &nt);
    if (!data || data->isNull) { rs->last_null = 1; return NULL; }
    rs->last_null = 0;

    switch (nt) {
        case DPI_NATIVE_TYPE_BYTES: {
            unsigned int n = data->value.asBytes.length;
            if (kdbc_ensure_strbuf(rs, n + 1) != 0) return NULL;
            memcpy(rs->str_buf, data->value.asBytes.ptr, n);
            rs->str_buf[n] = '\0';
            return rs->str_buf;
        }
        case DPI_NATIVE_TYPE_INT64:
            snprintf(ors->conv_buf, sizeof(ors->conv_buf), "%lld",
                     (long long)data->value.asInt64);
            return ors->conv_buf;
        case DPI_NATIVE_TYPE_DOUBLE:
            snprintf(ors->conv_buf, sizeof(ors->conv_buf), "%.17g",
                     data->value.asDouble);
            return ors->conv_buf;
        case DPI_NATIVE_TYPE_FLOAT:
            snprintf(ors->conv_buf, sizeof(ors->conv_buf), "%.7g",
                     (double)data->value.asFloat);
            return ors->conv_buf;
        case DPI_NATIVE_TYPE_BOOLEAN:
            return data->value.asBoolean ? "true" : "false";
        default:
            return NULL;
    }
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
    .call_execute       = NULL,
    .call_get_out       = NULL,
};

void kdbc_register_oracle(void) {
    kdbc_register_driver(KDBC_ORACLE, &oracle_vtable);
}
