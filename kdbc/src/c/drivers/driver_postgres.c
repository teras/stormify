/*
 * KDBC Native - PostgreSQL driver (binary protocol)
 *
 * Uses dlopen/dlsym to load libpq at runtime.
 * Parameters are sent in binary format (network byte order).
 * Results are received in binary format and decoded natively.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
#include "../include/kdbc_internal.h"
#include "../include/kdbc_dl.h"
#include <libpq-fe.h>
#include <postgres_ext.h>
#include <pthread.h>

/* libpq < 14 lacks pipeline-mode enum constants; pin them to the documented
 * values so compilation works against older system headers. The runtime
 * dlsym() of PQenterPipelineMode/etc. is the actual gate that decides whether
 * pipeline mode is used at all. */
#ifndef PGRES_PIPELINE_SYNC
#define PGRES_PIPELINE_SYNC    10
#endif
#ifndef PGRES_PIPELINE_ABORTED
#define PGRES_PIPELINE_ABORTED 11
#endif

/* PostgreSQL type OIDs - stable catalog values, not in libpq headers */
#define PG_BOOL_OID        16
#define PG_INT2_OID        21
#define PG_INT4_OID        23
#define PG_INT8_OID        20
#define PG_FLOAT4_OID     700
#define PG_FLOAT8_OID     701
#define PG_TEXT_OID        25
#define PG_VARCHAR_OID   1043
#define PG_BYTEA_OID       17
#define PG_NUMERIC_OID   1700
#define PG_TIMESTAMP_OID 1114
#define PG_DATE_OID      1082
#define PG_TIME_OID      1083

/* ========================================================================
 * Byte-order helpers (network = big-endian)
 * ======================================================================== */

static inline int16_t pg_htobe16(int16_t h) {
    uint16_t u = (uint16_t)h;
    return (int16_t)(((u & 0xFF) << 8) | ((u >> 8) & 0xFF));
}

static inline int32_t pg_htobe32(int32_t h) {
    uint32_t u = (uint32_t)h;
    return (int32_t)(((u & 0xFF) << 24) | (((u >> 8) & 0xFF) << 16) |
                     (((u >> 16) & 0xFF) << 8) | ((u >> 24) & 0xFF));
}

static inline int64_t pg_htobe64(int64_t h) {
    uint64_t u = (uint64_t)h;
    return (int64_t)(
        ((u & 0xFFULL) << 56) | (((u >> 8) & 0xFFULL) << 48) |
        (((u >> 16) & 0xFFULL) << 40) | (((u >> 24) & 0xFFULL) << 32) |
        (((u >> 32) & 0xFFULL) << 24) | (((u >> 40) & 0xFFULL) << 16) |
        (((u >> 48) & 0xFFULL) << 8)  | ((u >> 56) & 0xFFULL));
}

#define pg_be16toh pg_htobe16  /* symmetric */
#define pg_be32toh pg_htobe32
#define pg_be64toh pg_htobe64

/* ========================================================================
 * Function pointer types
 * ======================================================================== */

typedef PGconn    *(*fn_PQconnectdb)(const char *);
typedef void       (*fn_PQfinish)(PGconn *);
typedef int        (*fn_PQstatus)(const PGconn *);
typedef char      *(*fn_PQerrorMessage)(const PGconn *);
typedef PGresult  *(*fn_PQexec)(PGconn *, const char *);
typedef PGresult  *(*fn_PQprepare)(PGconn *, const char *, const char *, int, const Oid *);
typedef PGresult  *(*fn_PQexecPrepared)(PGconn *, const char *, int,
                                         const char *const *, const int *, const int *, int);
typedef int        (*fn_PQresultStatus)(const PGresult *);
typedef char      *(*fn_PQresultErrorMessage)(const PGresult *);
typedef char      *(*fn_PQcmdTuples)(PGresult *);
typedef int        (*fn_PQntuples)(const PGresult *);
typedef int        (*fn_PQnfields)(const PGresult *);
typedef char      *(*fn_PQfname)(const PGresult *, int);
typedef Oid        (*fn_PQftype)(const PGresult *, int);
typedef int        (*fn_PQfformat)(const PGresult *, int);
typedef int        (*fn_PQgetisnull)(const PGresult *, int, int);
typedef char      *(*fn_PQgetvalue)(const PGresult *, int, int);
typedef int        (*fn_PQgetlength)(const PGresult *, int, int);
typedef void       (*fn_PQclear)(PGresult *);
typedef int        (*fn_PQserverVersion)(const PGconn *);
typedef PGcancel  *(*fn_PQgetCancel)(PGconn *);
typedef int        (*fn_PQcancel)(PGcancel *, char *, int);
typedef void       (*fn_PQfreeCancel)(PGcancel *);
typedef int        (*fn_PQsetClientEncoding)(PGconn *, const char *);
typedef PGTransactionStatusType (*fn_PQtransactionStatus)(const PGconn *);
typedef char      *(*fn_PQresultErrorField)(const PGresult *, int);
/* Pipeline mode (libpq 14+). Loaded best-effort: missing => batch falls back to per-row loop. */
typedef int        (*fn_PQenterPipelineMode)(PGconn *);
typedef int        (*fn_PQexitPipelineMode)(PGconn *);
typedef int        (*fn_PQpipelineSync)(PGconn *);
typedef int        (*fn_PQpipelineStatus)(const PGconn *);
typedef int        (*fn_PQsendQueryPrepared)(PGconn *, const char *, int,
                                              const char *const *, const int *, const int *, int);
typedef int        (*fn_PQsendQueryParams)(PGconn *, const char *, int,
                                            const Oid *, const char *const *,
                                            const int *, const int *, int);
typedef int        (*fn_PQsetSingleRowMode)(PGconn *);
typedef PGresult  *(*fn_PQgetResult)(PGconn *);

/* PG_DIAG_* selectors from libpq-fe.h. Re-declared here so we don't depend
 * on the application-side header for the field selector constants. */
#ifndef PG_DIAG_SQLSTATE
#define PG_DIAG_SQLSTATE 'C'
#endif

/* ========================================================================
 * Loaded function pointers
 * ======================================================================== */

static kdbc_lib_handle lib_handle = NULL;

static fn_PQconnectdb          p_connectdb;
static fn_PQfinish             p_finish;
static fn_PQstatus             p_status;
static fn_PQerrorMessage       p_errorMessage;
static fn_PQexec               p_exec;
static fn_PQprepare            p_prepare;
static fn_PQexecPrepared       p_execPrepared;
static fn_PQresultStatus       p_resultStatus;
static fn_PQresultErrorMessage p_resultErrorMessage;
static fn_PQcmdTuples          p_cmdTuples;
static fn_PQntuples            p_ntuples;
static fn_PQnfields            p_nfields;
static fn_PQfname              p_fname;
static fn_PQftype              p_ftype;
static fn_PQfformat            p_fformat;
static fn_PQgetisnull          p_getisnull;
static fn_PQgetvalue           p_getvalue;
static fn_PQgetlength          p_getlength;
static fn_PQclear              p_clear;
static fn_PQserverVersion      p_serverVersion;
static fn_PQgetCancel          p_getCancel;
static fn_PQcancel             p_cancel;
static fn_PQfreeCancel         p_freeCancel;
static fn_PQsetClientEncoding  p_setClientEncoding;
static fn_PQtransactionStatus  p_transactionStatus;
static fn_PQresultErrorField   p_resultErrorField;
static fn_PQsendQueryPrepared  p_sendQueryPrepared;
static fn_PQsendQueryParams    p_sendQueryParams;
static fn_PQsetSingleRowMode   p_setSingleRowMode;
static fn_PQgetResult          p_getResult;

/* Pipeline mode optionals — non-NULL iff libpq >= 14. */
static fn_PQenterPipelineMode  p_enterPipelineMode;
static fn_PQexitPipelineMode   p_exitPipelineMode;
static fn_PQpipelineSync       p_pipelineSync;
static fn_PQpipelineStatus     p_pipelineStatus;

/* ========================================================================
 * Driver-specific structures
 * ======================================================================== */

typedef struct {
    PGresult *res;
    int       row_count;
    int       col_count;
    int       current_row;
    /* Cached column type OIDs */
    Oid      *col_types;
    /* String conversion buffer per result set */
    char      conv_buf[64];
    /* Streaming via PQsetSingleRowMode: `res` holds the current row, each
     * pg_rs_next replaces it via PQgetResult. */
    int       streaming;
    /* Borrowed from the connection (owned elsewhere). Non-NULL iff the query
     * was dispatched via PQsendQueryPrepared, which is the case whenever
     * fetch_size > 0. libpq requires PQgetResult to be drained until NULL
     * after any async send — pg_rs_close uses `pg != NULL` as the gate. */
    PGconn   *pg;
} pg_result_set;

/* Per-parameter binary buffer */
typedef struct {
    char    *data;       /* binary data (owned) */
    int      len;        /* length in bytes, 0 = NULL */
    int      format;     /* 0=text, 1=binary */
    Oid      oid;        /* 0 = let server infer; otherwise explicit (INT4OID, etc.) */
} pg_param_buf;

typedef struct {
    PGconn      *pg;
    char         stmt_name[32];
    char        *sql;          /* owned copy of SQL to be prepared (may include appended RETURNING) */
    int          param_count;
    pg_param_buf *params;
    /* Server-side prepare is deferred until first execute so we can supply
     * accurate per-parameter Oids derived from the actual bind calls. Once
     * prepared, prepared_oids holds the types committed to the server; a
     * subsequent execute with different Oids triggers a DEALLOCATE + re-Parse. */
    int          prepared;
    Oid         *prepared_oids;
} pg_stmt_data;

/* ========================================================================
 * Library loading
 * ======================================================================== */

#define PG_LOAD(name) do { \
    p_##name = (fn_PQ##name)kdbc_dl_sym(lib_handle, "PQ" #name); \
    if (!p_##name) { kdbc_dl_close(lib_handle); lib_handle = NULL; return; } \
} while (0)

static pthread_once_t pg_load_once = PTHREAD_ONCE_INIT;
static int            pg_load_ok   = 0;

static void pg_load_impl(void) {
    lib_handle = kdbc_dl_open(KDBC_LIBNAME("pq", "5"), RTLD_LAZY);
    if (!lib_handle) lib_handle = kdbc_dl_open(KDBC_LIBNAME_NOVER("pq"), RTLD_LAZY);
    if (!lib_handle) lib_handle = kdbc_dl_open(KDBC_LIBNAME_LIBPREFIX("pq"), RTLD_LAZY);
    if (!lib_handle) return;

    PG_LOAD(connectdb);
    PG_LOAD(finish);
    PG_LOAD(status);
    PG_LOAD(errorMessage);
    PG_LOAD(exec);
    PG_LOAD(prepare);
    PG_LOAD(execPrepared);
    PG_LOAD(resultStatus);
    PG_LOAD(resultErrorMessage);
    PG_LOAD(cmdTuples);
    PG_LOAD(ntuples);
    PG_LOAD(nfields);
    PG_LOAD(fname);
    PG_LOAD(ftype);
    PG_LOAD(fformat);
    PG_LOAD(getisnull);
    PG_LOAD(getvalue);
    PG_LOAD(getlength);
    PG_LOAD(clear);
    PG_LOAD(serverVersion);
    PG_LOAD(getCancel);
    PG_LOAD(cancel);
    PG_LOAD(freeCancel);
    PG_LOAD(setClientEncoding);
    PG_LOAD(transactionStatus);
    PG_LOAD(resultErrorField);
    /* Streaming trio. PQsetSingleRowMode requires libpq 9.2+, the floor
     * also enforced at connect against the server version. */
    PG_LOAD(sendQueryPrepared);
    PG_LOAD(sendQueryParams);
    PG_LOAD(setSingleRowMode);
    PG_LOAD(getResult);

    /* Pipeline mode (libpq 14+). Optional — failure to resolve is non-fatal,
     * the batch path detects NULL function pointers and falls back to the
     * per-row loop in kdbc_execute_batch. */
    p_enterPipelineMode = (fn_PQenterPipelineMode)kdbc_dl_sym(lib_handle, "PQenterPipelineMode");
    p_exitPipelineMode  = (fn_PQexitPipelineMode) kdbc_dl_sym(lib_handle, "PQexitPipelineMode");
    p_pipelineSync      = (fn_PQpipelineSync)     kdbc_dl_sym(lib_handle, "PQpipelineSync");
    p_pipelineStatus    = (fn_PQpipelineStatus)   kdbc_dl_sym(lib_handle, "PQpipelineStatus");

    pg_load_ok = 1;
}

static int pg_load(void) {
    pthread_once(&pg_load_once, pg_load_impl);
    return pg_load_ok;
}

static int pg_loaded(void) { return lib_handle != NULL; }

/* ========================================================================
 * Connection
 * ======================================================================== */

static void *pg_connect(const char *url, const char *user, const char *password,
                        char *err, size_t err_size) {
    char connstr[1024];
    if (user && password) {
        snprintf(connstr, sizeof(connstr), "postgresql://%s:%s@%s", user, password, url);
    } else if (user) {
        snprintf(connstr, sizeof(connstr), "postgresql://%s@%s", user, url);
    } else {
        snprintf(connstr, sizeof(connstr), "postgresql://%s", url);
    }

    PGconn *conn = p_connectdb(connstr);
    if (!conn) {
        snprintf(err, err_size, "PostgreSQL: failed to allocate connection");
        return NULL;
    }
    if (p_status(conn) != CONNECTION_OK) {
        snprintf(err, err_size, "PostgreSQL: %s", p_errorMessage(conn));
        p_finish(conn);
        return NULL;
    }
    /* Minimum supported server: PostgreSQL 9.2 (2012). This is also the
     * floor for PQsetSingleRowMode, our row-streaming primitive — anything
     * older would silently fall back to buffered reads, which is exactly
     * the kind of "looks fine, dies under load" surprise we'd rather catch
     * at connect time than mid-iteration. PQserverVersion encodes as
     * MMmmpp (e.g. 90200 == 9.2.0, 170000 == 17.0). 0 means the version
     * couldn't be determined — refuse on the safe side. */
    int srv_ver = p_serverVersion(conn);
    if (srv_ver < 90200) {
        snprintf(err, err_size,
                 "PostgreSQL: server version %d.%d.%d is below the minimum supported (9.2). "
                 "Upgrade the server or use a different kdbc release.",
                 srv_ver / 10000, (srv_ver / 100) % 100, srv_ver % 100);
        p_finish(conn);
        return NULL;
    }
    /* Pin the session's client_encoding to UTF8 so the server transcodes
     * result bytes into UTF-8 regardless of the cluster's server_encoding or
     * the PGCLIENTENCODING environment variable. The rest of the driver
     * assumes TEXT-like binary values are UTF-8 (see pg_get_string and the
     * BYTEA vs TEXT decode paths) — this makes that assumption explicit and
     * brings the driver in line with the other backends (MariaDB: utf8mb4,
     * Oracle: AL32UTF8, MSSQL: DBSETCHARSET=UTF-8). */
    if (p_setClientEncoding(conn, "UTF8") != 0) {
        snprintf(err, err_size, "PostgreSQL: failed to set client_encoding UTF8: %s",
                 p_errorMessage(conn));
        p_finish(conn);
        return NULL;
    }
    /* Pre-PARSE the BEGIN statement once per connection so the deferred-BEGIN
     * hot path can bundle it inside pipeline mode via PQsendQueryPrepared
     * (Bind+Execute only, ~10 wire bytes, no per-iter Parse cost on the
     * server). pgjdbc does the equivalent — its wire trace shows BEGIN as a
     * named prepared statement (S_5). The PG server accepts Parse for BEGIN
     * even though the SQL `PREPARE name AS BEGIN` form is rejected.
     * Failure here is non-fatal: the bundled path is the only consumer and
     * the surrounding error handling collapses to the existing SQL fallback. */
    PGresult *r = p_prepare(conn, "_kdbc_tx_begin", "BEGIN", 0, NULL);
    if (r) p_clear(r);
    /* COMMIT / ROLLBACK intentionally NOT pre-prepared. Empirically, sending
     * them via PQexecPrepared adds more libpq Bind+Execute overhead than the
     * single-token simple-query PQexec they replace — measured as a tx_rollback
     * regression in the 5-run sweep. Solo PQexec wins for trivial commands. */
    return conn;
}

static void pg_close(void *native) {
    if (native) p_finish((PGconn *)native);
}

/* Async cancel of the currently-executing query on this connection.
 * Safe to call from a thread different than the one blocked inside libpq:
 * PQgetCancel + PQcancel open a separate backend channel to send the
 * cancel request, without touching the original PGconn's protocol state. */
static int pg_cancel(kdbc_conn *conn) {
    if (!conn || !conn->native) return KDBC_ERROR;
    PGcancel *c = p_getCancel((PGconn *)conn->native);
    if (!c) return KDBC_ERROR;
    char errbuf[256];
    int ok = p_cancel(c, errbuf, (int)sizeof(errbuf));
    p_freeCancel(c);
    if (!ok) {
        CONN_ERR(conn, "PostgreSQL cancel failed: %s", errbuf);
        return KDBC_ERROR;
    }
    return KDBC_OK;
}

/* ========================================================================
 * Transactions
 * ======================================================================== */

static int pg_exec_simple(kdbc_conn *conn, const char *sql) {
    PGconn *pg = (PGconn *)conn->native;
    PGresult *res = p_exec(pg, sql);
    if (!res) {
        CONN_ERR(conn, "PostgreSQL: %s", p_errorMessage(pg));
        return KDBC_ERROR;
    }
    int st = p_resultStatus(res);
    if (st != PGRES_COMMAND_OK && st != PGRES_TUPLES_OK) {
        const char *sqlst = p_resultErrorField(res, PG_DIAG_SQLSTATE);
        CONN_ERR_V(conn, sqlst, 0,
                   "PostgreSQL: %s", p_resultErrorMessage(res));
        p_clear(res);
        return KDBC_ERROR;
    }
    p_clear(res);
    return KDBC_OK;
}

static int pg_set_autocommit(kdbc_conn *conn, int enabled) {
    /* Not reached in normal operation: the deferred-BEGIN opt-in (vt->begin_tx
     * below) makes kdbc_core handle setAutoCommit entirely via conn->begin_pending,
     * never delegating here. Kept as a defensive fallback for any path that
     * dispatches to vt->set_autocommit directly. */
    PGconn *pg = (PGconn *)conn->native;
    if (enabled && !conn->autocommit) {
        if (p_transactionStatus(pg) != PQTRANS_IDLE)
            return pg_exec_simple(conn, "COMMIT");
    } else if (!enabled && conn->autocommit) {
        return pg_exec_simple(conn, "BEGIN");
    }
    return KDBC_OK;
}

static int pg_commit(kdbc_conn *conn) {
    /* Simple-query PQexec is measurably faster than PQexecPrepared on a
     * trivial single-token command like COMMIT — extended-query Bind+Execute
     * adds more libpq client-side overhead than the parse it skips. The
     * pre-prepared name (_kdbc_tx_commit) is wired up at connect for use only
     * when bundled inside pipeline mode, where the saving comes from packing
     * BEGIN + DML in one TCP write — not relevant for solo COMMIT. */
    return pg_exec_simple(conn, "COMMIT");
}

static int pg_rollback(kdbc_conn *conn) {
    return pg_exec_simple(conn, "ROLLBACK");
}

/* Solo-BEGIN fallback used by kdbc_core's kdbc_flush_pending_begin when a
 * non-hot path (raw exec_direct, query_direct, batch fallback) needs the
 * deferred BEGIN to land before its statement. The hot execute paths
 * (pg_execute_update / pg_execute_batch) bypass this helper and bundle the
 * BEGIN into their existing pipeline-mode write for one fewer round-trip. */
static int pg_begin_tx(kdbc_conn *conn) {
    return pg_exec_simple(conn, "BEGIN");
}

/* ========================================================================
 * Metadata
 * ======================================================================== */

static void pg_get_product_name(void *native, char *buf, size_t sz) {
    (void)native;
    snprintf(buf, sz, "PostgreSQL");
}

static void pg_get_product_version(void *native, char *buf, size_t sz) {
    int ver = p_serverVersion((PGconn *)native);
    snprintf(buf, sz, "%d.%d", ver / 10000, (ver % 10000) / 100);
}

static int pg_get_major_version(void *native) {
    return p_serverVersion((PGconn *)native) / 10000;
}

static int pg_get_minor_version(void *native) {
    return (p_serverVersion((PGconn *)native) % 10000) / 100;
}

/* ========================================================================
 * Statement preparation
 * ======================================================================== */

static _Atomic long pg_stmt_counter = 0;

static void *pg_prepare(kdbc_conn *conn, const char *native_sql,
                        const char **ret_cols, int n_ret_cols,
                        int generated_keys_requested,
                        char *err, size_t err_size) {
    (void)generated_keys_requested; /* PostgreSQL uses BY_NAME with explicit RETURNING */
    PGconn *pg = (PGconn *)conn->native;

    /* Append RETURNING clause if needed */
    char *final_sql = NULL;
    if (ret_cols && n_ret_cols > 0) {
        size_t len = strlen(native_sql) + 32;
        for (int i = 0; i < n_ret_cols; i++)
            len += strlen(ret_cols[i]) + 2;
        final_sql = (char *)malloc(len);
        if (!final_sql) {
            snprintf(err, err_size, "Out of memory");
            return NULL;
        }
        strcpy(final_sql, native_sql);
        strcat(final_sql, " RETURNING ");
        for (int i = 0; i < n_ret_cols; i++) {
            if (i > 0) strcat(final_sql, ", ");
            strcat(final_sql, ret_cols[i]);
        }
    }

    pg_stmt_data *sd = (pg_stmt_data *)calloc(1, sizeof(pg_stmt_data));
    if (!sd) {
        free(final_sql);
        snprintf(err, err_size, "Out of memory");
        return NULL;
    }

    sd->pg = pg;
    snprintf(sd->stmt_name, sizeof(sd->stmt_name), "_kdbc_%ld",
             pg_stmt_counter++);
    /* Count params from the TRANSLATED sql ($1, $2...) by counting $ markers */
    sd->param_count = 0;
    for (const char *p = native_sql; *p; p++) {
        if (*p == '$' && p[1] >= '1' && p[1] <= '9') sd->param_count++;
    }

    /* Allocate parameter buffers */
    if (sd->param_count > 0) {
        sd->params = (pg_param_buf *)calloc(sd->param_count, sizeof(pg_param_buf));
        if (!sd->params) {
            free(final_sql);
            free(sd);
            snprintf(err, err_size, "Out of memory");
            return NULL;
        }
    }

    /* Keep an owned copy of the SQL for the deferred Parse. */
    sd->sql = strdup(final_sql ? final_sql : native_sql);
    free(final_sql);
    if (!sd->sql) {
        free(sd->params);
        free(sd);
        snprintf(err, err_size, "Out of memory");
        return NULL;
    }
    sd->prepared = 0;
    sd->prepared_oids = NULL;
    return sd;
}

/* Ensure the statement is Parsed server-side with Oids matching the current
 * bind state. Re-Parses (DEALLOCATE + PQprepare) if Oids have changed since
 * the last execute — rare in practice but necessary when a caller rebinds
 * the same prepared statement with a different Kotlin-side type. */
static int pg_ensure_prepared(kdbc_stmt *stmt, char *err, size_t err_size) {
    pg_stmt_data *sd = (pg_stmt_data *)stmt->native;
    /* Collect current Oids from bind state. */
    Oid *current = NULL;
    if (sd->param_count > 0) {
        current = (Oid *)calloc(sd->param_count, sizeof(Oid));
        if (!current) {
            snprintf(err, err_size, "Out of memory");
            return KDBC_ERROR;
        }
        for (int i = 0; i < sd->param_count; i++) current[i] = sd->params[i].oid;
    }

    /* Already prepared with matching Oids? Nothing to do. */
    if (sd->prepared && sd->param_count > 0 && sd->prepared_oids) {
        int same = 1;
        for (int i = 0; i < sd->param_count; i++) {
            if (sd->prepared_oids[i] != current[i]) { same = 0; break; }
        }
        if (same) { free(current); return KDBC_OK; }
    } else if (sd->prepared && sd->param_count == 0) {
        free(current);
        return KDBC_OK;
    }

    /* If already prepared under different Oids, tear down the server-side
     * prepared statement before re-parsing. */
    if (sd->prepared) {
        char dealloc[64];
        snprintf(dealloc, sizeof(dealloc), "DEALLOCATE %s", sd->stmt_name);
        PGresult *dres = p_exec(sd->pg, dealloc);
        if (dres) p_clear(dres);
        sd->prepared = 0;
    }

    PGresult *res = p_prepare(sd->pg, sd->stmt_name, sd->sql,
                              sd->param_count, current);
    if (!res || p_resultStatus(res) != PGRES_COMMAND_OK) {
        const char *sqlst = res ? p_resultErrorField(res, PG_DIAG_SQLSTATE) : NULL;
        STMT_ERR_V(stmt, sqlst, 0,
                   "PostgreSQL prepare: %s",
                   res ? p_resultErrorMessage(res) : p_errorMessage(sd->pg));
        (void)err; (void)err_size;
        if (res) p_clear(res);
        free(current);
        return KDBC_ERROR;
    }
    p_clear(res);

    free(sd->prepared_oids);
    sd->prepared_oids = current;  /* transfer ownership */
    sd->prepared = 1;
    return KDBC_OK;
}

static void pg_stmt_close(void *native_stmt, void *native_conn) {
    pg_stmt_data *sd = (pg_stmt_data *)native_stmt;
    (void)native_conn;
    if (!sd) return;

    /* Only DEALLOCATE if we actually Parsed on the server. A stmt that was
     * prepared but never executed has no server-side state to clean up. */
    if (sd->prepared) {
        char sql[64];
        snprintf(sql, sizeof(sql), "DEALLOCATE %s", sd->stmt_name);
        PGresult *res = p_exec(sd->pg, sql);
        if (res) p_clear(res);
    }

    if (sd->params) {
        for (int i = 0; i < sd->param_count; i++)
            free(sd->params[i].data);
        free(sd->params);
    }
    free(sd->sql);
    free(sd->prepared_oids);
    free(sd);
}

/* ========================================================================
 * Parameter binding (binary format)
 *
 * PostgreSQL binary wire format:
 *   bool:   1 byte (0 or 1)
 *   int2:   2 bytes big-endian
 *   int4:   4 bytes big-endian
 *   int8:   8 bytes big-endian
 *   float4: 4 bytes IEEE 754 big-endian
 *   float8: 8 bytes IEEE 754 big-endian
 *   text:   raw UTF-8 bytes (no null terminator)
 *   bytea:  raw bytes
 * ======================================================================== */

static void pg_param_set_binary(pg_stmt_data *sd, int idx, const void *data, int len, Oid oid) {
    int i = idx - 1;
    free(sd->params[i].data);
    sd->params[i].data = (char *)malloc(len);
    if (sd->params[i].data) {
        memcpy(sd->params[i].data, data, len);
        sd->params[i].len = len;
    } else {
        sd->params[i].len = 0;
    }
    sd->params[i].format = 1; /* binary */
    sd->params[i].oid = oid;
}

static void pg_param_set_null(pg_stmt_data *sd, int idx) {
    int i = idx - 1;
    free(sd->params[i].data);
    sd->params[i].data = NULL;
    sd->params[i].len = 0;
    sd->params[i].format = 1;
    /* Leave oid unchanged: a null followed by a typed bind in a later execute
     * should keep the typed Oid. A plain kdbc_bind_null without any prior
     * bind leaves oid=0 (let server infer from SQL context). */
}

static int pg_bind_null(kdbc_stmt *stmt, int idx) {
    pg_param_set_null((pg_stmt_data *)stmt->native, idx);
    return KDBC_OK;
}

/* Set a text-format parameter. PG will parse the string and convert to
 * whatever type the target column expects. Used for string binds and any
 * other case where the exact type isn't known at bind time. */
static void pg_param_set_text(pg_stmt_data *sd, int idx, const char *text, Oid oid) {
    int i = idx - 1;
    free(sd->params[i].data);
    int len = (int)strlen(text);
    sd->params[i].data = (char *)malloc((size_t)len + 1);
    if (sd->params[i].data) {
        memcpy(sd->params[i].data, text, (size_t)len + 1);
        sd->params[i].len = len;
    } else {
        sd->params[i].len = 0;
    }
    sd->params[i].format = 0; /* text */
    sd->params[i].oid = oid;
}

static int pg_bind_int(kdbc_stmt *stmt, int idx, int val) {
    /* 4-byte big-endian int4 with Oid=INT4. The server will implicit-cast to
     * int2/int8/numeric/etc. as needed by the target column since we told it
     * at Parse time that this param is int4. */
    int32_t be = pg_htobe32((int32_t)val);
    pg_param_set_binary((pg_stmt_data *)stmt->native, idx, &be, 4, PG_INT4_OID);
    return KDBC_OK;
}

static int pg_bind_long(kdbc_stmt *stmt, int idx, int64_t val) {
    int64_t be = pg_htobe64(val);
    pg_param_set_binary((pg_stmt_data *)stmt->native, idx, &be, 8, PG_INT8_OID);
    return KDBC_OK;
}

static int pg_bind_double(kdbc_stmt *stmt, int idx, double val) {
    /* IEEE 754 bit pattern sent as 8 bytes big-endian — bit-exact round-trip
     * for every finite double, including subnormals and NaN payloads. */
    int64_t bits;
    memcpy(&bits, &val, 8);
    int64_t be = pg_htobe64(bits);
    pg_param_set_binary((pg_stmt_data *)stmt->native, idx, &be, 8, PG_FLOAT8_OID);
    return KDBC_OK;
}

static int pg_bind_bool(kdbc_stmt *stmt, int idx, int val) {
    /* PostgreSQL BOOLEAN: 1 byte, 0x00=false, 0x01=true, Oid=BOOL. */
    char b = val ? 1 : 0;
    pg_param_set_binary((pg_stmt_data *)stmt->native, idx, &b, 1, PG_BOOL_OID);
    return KDBC_OK;
}

static int pg_bind_string(kdbc_stmt *stmt, int idx, const char *val) {
    /* Strings sent as text format with Oid=0 (unspecified) so the server
     * parses them via its normal type coercion path. This matters for
     * non-string target columns:
     *   - NUMERIC / DECIMAL: binary format would require the base-10000 wire
     *     encoding; text lets PG parse arbitrary-precision BigDecimal strings.
     *   - UUID / JSON / INET / etc.: text format just works; binary would need
     *     per-type encoding.
     * Each param's format is recorded independently in paramFormats[], so
     * mixing binary numerics and text strings in the same execute is fine. */
    pg_param_set_text((pg_stmt_data *)stmt->native, idx, val, 0);
    return KDBC_OK;
}

static int pg_bind_blob(kdbc_stmt *stmt, int idx, const void *data, size_t len) {
    /* Blobs sent as binary format with Oid=BYTEA */
    pg_param_set_binary((pg_stmt_data *)stmt->native, idx, data, (int)len, PG_BYTEA_OID);
    return KDBC_OK;
}

/* Date/time binding: PostgreSQL binary format
 * TIMESTAMP: int64 microseconds since 2000-01-01 00:00:00 (big-endian)
 * DATE: int32 days since 2000-01-01 (big-endian)
 * TIME: int64 microseconds since midnight (big-endian) */

/* Days from 1970-01-01 to 2000-01-01 */
#define PG_EPOCH_OFFSET_DAYS 10957
/* Microseconds from Unix epoch to PG epoch */
#define PG_EPOCH_OFFSET_USEC (PG_EPOCH_OFFSET_DAYS * 86400LL * 1000000LL)

static int days_from_civil(int y, int m, int d) {
    /* Convert y/m/d to days since 1970-01-01 (Rata Die algorithm) */
    if (m <= 2) { y--; m += 9; } else { m -= 3; }
    int era = (y >= 0 ? y : y - 399) / 400;
    int yoe = y - era * 400;
    int doy = (153 * m + 2) / 5 + d - 1;
    int doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    return era * 146097 + doe - 719468;
}

static void civil_from_days(int z, int *y, int *m, int *d) {
    /* Convert days since 1970-01-01 back to y/m/d */
    z += 719468;
    int era = (z >= 0 ? z : z - 146096) / 146097;
    int doe = z - era * 146097;
    int yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    *y = yoe + era * 400;
    int doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    int mp = (5 * doy + 2) / 153;
    *d = doy - (153 * mp + 2) / 5 + 1;
    *m = mp + (mp < 10 ? 3 : -9);
    if (*m <= 2) (*y)++;
}

static int pg_bind_timestamp(kdbc_stmt *stmt, int idx,
                             int year, int month, int day,
                             int hour, int minute, int second, int usec) {
    int days = days_from_civil(year, month, day);
    int64_t unix_usec = (int64_t)days * 86400LL * 1000000LL
                      + (int64_t)hour * 3600000000LL
                      + (int64_t)minute * 60000000LL
                      + (int64_t)second * 1000000LL
                      + usec;
    int64_t pg_usec = unix_usec - PG_EPOCH_OFFSET_USEC;
    int64_t be = pg_htobe64(pg_usec);
    pg_param_set_binary((pg_stmt_data *)stmt->native, idx, &be, 8, PG_TIMESTAMP_OID);
    return KDBC_OK;
}

static int pg_bind_date(kdbc_stmt *stmt, int idx, int year, int month, int day) {
    int unix_days = days_from_civil(year, month, day);
    int32_t pg_days = (int32_t)(unix_days - PG_EPOCH_OFFSET_DAYS);
    int32_t be = pg_htobe32(pg_days);
    pg_param_set_binary((pg_stmt_data *)stmt->native, idx, &be, 4, PG_DATE_OID);
    return KDBC_OK;
}

static int pg_bind_time(kdbc_stmt *stmt, int idx,
                        int hour, int minute, int second, int usec) {
    int64_t time_usec = (int64_t)hour * 3600000000LL
                      + (int64_t)minute * 60000000LL
                      + (int64_t)second * 1000000LL
                      + usec;
    int64_t be = pg_htobe64(time_usec);
    pg_param_set_binary((pg_stmt_data *)stmt->native, idx, &be, 8, PG_TIME_OID);
    return KDBC_OK;
}

/* ========================================================================
 * Build PQexecPrepared argument arrays from pg_param_buf
 * ======================================================================== */

typedef struct {
    const char **values;
    int         *lengths;
    int         *formats;
} pg_exec_args;

static pg_exec_args pg_build_args(pg_stmt_data *sd) {
    pg_exec_args a = { NULL, NULL, NULL };
    int n = sd->param_count;
    if (n == 0) return a;

    a.values  = (const char **)calloc(n, sizeof(const char *));
    a.lengths = (int *)calloc(n, sizeof(int));
    a.formats = (int *)calloc(n, sizeof(int));

    for (int i = 0; i < n; i++) {
        a.values[i]  = sd->params[i].data;   /* NULL for NULL params */
        a.lengths[i] = sd->params[i].len;
        a.formats[i] = sd->params[i].format;
    }
    return a;
}

static void pg_free_args(pg_exec_args *a) {
    free(a->values);
    free(a->lengths);
    free(a->formats);
}

/* ========================================================================
 * Execution
 * ======================================================================== */

/* Forward decls — used by pg_execute_update / pg_execute_batch when a
 * deferred BEGIN is pending and pipeline mode is available. Bodies below. */
static int pg_pipelined_begin_then_update(kdbc_stmt *stmt);
static int pg_pipeline_available(void);
static void pg_drain_results(PGconn *pg);

/* Extract row count (or generated key from a RETURNING clause) from a single
 * PGresult produced by an INSERT/UPDATE/DELETE execute. Frees the result.
 * Returns rows on success or KDBC_ERROR with stmt->error populated. */
static int pg_consume_update_result(kdbc_stmt *stmt, PGresult *res) {
    int status = p_resultStatus(res);
    if (status == PGRES_TUPLES_OK) {
        /* RETURNING clause — extract generated key from text result */
        if (p_ntuples(res) > 0 && p_nfields(res) > 0 && !p_getisnull(res, 0, 0)) {
            const char *txt = p_getvalue(res, 0, 0);
            int tlen = p_getlength(res, 0, 0);
            /* Always keep text form for non-numeric PKs (UUID, etc.) */
            free(stmt->generated_key_str);
            stmt->generated_key_str = (char *)malloc((size_t)tlen + 1);
            if (stmt->generated_key_str) {
                memcpy(stmt->generated_key_str, txt, tlen);
                stmt->generated_key_str[tlen] = '\0';
            }
            /* Also parse as int64 for numeric PKs */
            long long k = 0;
            sscanf(txt, "%lld", &k);
            stmt->generated_key = k;
            stmt->has_generated_key = 1;
        }
        int rows = p_ntuples(res);
        p_clear(res);
        return rows > 0 ? rows : 0;
    } else if (status == PGRES_COMMAND_OK) {
        const char *ct = p_cmdTuples(res);
        int rows = ct ? atoi(ct) : 0;
        p_clear(res);
        return rows;
    } else {
        const char *sqlst = p_resultErrorField(res, PG_DIAG_SQLSTATE);
        STMT_ERR_V(stmt, sqlst, 0,
                   "PostgreSQL: %s", p_resultErrorMessage(res));
        p_clear(res);
        return KDBC_ERROR;
    }
}

static int pg_execute_update(kdbc_stmt *stmt) {
    pg_stmt_data *sd = (pg_stmt_data *)stmt->native;
    if (pg_ensure_prepared(stmt, stmt->error, KDBC_ERR_SIZE) != KDBC_OK)
        return KDBC_ERROR;

    /* Pipeline-bundle BEGIN with the user statement when there's a deferred
     * BEGIN pending and pipeline mode is available — saves the dedicated BEGIN
     * round-trip. Falls through to solo flush + sync execute on libpq < 14. */
    if (stmt->conn->begin_pending && pg_pipeline_available())
        return pg_pipelined_begin_then_update(stmt);
    if (kdbc_flush_pending_begin(stmt->conn) != KDBC_OK)
        return KDBC_ERROR;

    pg_exec_args args = pg_build_args(sd);
    /* Always request text results for RETURNING clause — this gives a
     * universal text representation of the generated key regardless of type
     * (integer, UUID, VARCHAR, etc.), matching the Oracle RETURNING INTO
     * approach. PQcmdTuples also requires text format for non-RETURNING. */
    PGresult *res = p_execPrepared(sd->pg, sd->stmt_name,
                                    sd->param_count,
                                    args.values, args.lengths, args.formats,
                                    0 /* text result */);
    pg_free_args(&args);

    if (!res) {
        STMT_ERR(stmt, "PostgreSQL: %s", p_errorMessage(sd->pg));
        return KDBC_ERROR;
    }
    return pg_consume_update_result(stmt, res);
}

/* Pipelined "BEGIN; <user prepared exec>" with one PQpipelineSync.
 * Wire shape:  [Parse/Bind/Exec BEGIN][Bind/Exec user_stmt][Sync] → one TCP
 * write, one round-trip, two responses.  Replaces the eager-BEGIN path that
 * cost an extra RT.  Always exits pipeline mode before returning, regardless
 * of success/failure, and clears conn->begin_pending so the caller doesn't
 * retry the BEGIN. */
static int pg_pipelined_begin_then_update(kdbc_stmt *stmt) {
    pg_stmt_data *sd = (pg_stmt_data *)stmt->native;
    pg_drain_results(sd->pg);

    if (!p_enterPipelineMode(sd->pg)) {
        STMT_ERR(stmt, "PostgreSQL: PQenterPipelineMode failed: %s",
                 p_errorMessage(sd->pg));
        return KDBC_ERROR;
    }

    int sent_ok = 1;
    /* BEGIN is pre-PARSEd at connect time as `_kdbc_tx_begin` (see
     * pg_connect), so we send a parameterless Bind+Execute — no per-iter
     * Parse work, no SQL text on the wire, matching pgjdbc's behaviour. */
    if (!p_sendQueryPrepared(sd->pg, "_kdbc_tx_begin", 0, NULL, NULL, NULL, 0)) {
        STMT_ERR(stmt, "PostgreSQL: PQsendQueryPrepared BEGIN failed: %s",
                 p_errorMessage(sd->pg));
        sent_ok = 0;
    }

    pg_exec_args args = pg_build_args(sd);
    if (sent_ok && !p_sendQueryPrepared(sd->pg, sd->stmt_name, sd->param_count,
                                        args.values, args.lengths, args.formats,
                                        0 /* text result */)) {
        STMT_ERR(stmt, "PostgreSQL: PQsendQueryPrepared failed: %s",
                 p_errorMessage(sd->pg));
        sent_ok = 0;
    }
    pg_free_args(&args);

    if (!p_pipelineSync(sd->pg)) {
        STMT_ERR(stmt, "PostgreSQL: PQpipelineSync failed: %s",
                 p_errorMessage(sd->pg));
        sent_ok = 0;
    }

    /* Drain: BEGIN result, then user result, then PGRES_PIPELINE_SYNC.
     * NULL between query results is the per-query terminator. */
    PGresult *user_res = NULL;
    int errored = !sent_ok;
    char err_msg[KDBC_ERR_SIZE]; err_msg[0] = '\0';
    char err_state[8]; err_state[0] = '\0';
    int seen_begin_done = 0;
    int safety = 32;
    while (safety-- > 0) {
        PGresult *r = p_getResult(sd->pg);
        if (!r) continue;  /* per-query terminator */
        int s = p_resultStatus(r);
        if (s == PGRES_PIPELINE_SYNC) {
            p_clear(r);
            break;
        }
        if (s == PGRES_PIPELINE_ABORTED) {
            errored = 1;
            p_clear(r);
            continue;
        }
        if (s == PGRES_FATAL_ERROR || s == PGRES_NONFATAL_ERROR) {
            errored = 1;
            if (!err_msg[0]) {
                const char *m = p_resultErrorMessage(r);
                if (m) snprintf(err_msg, sizeof(err_msg), "%s", m);
                const char *st = p_resultErrorField(r, PG_DIAG_SQLSTATE);
                if (st) snprintf(err_state, sizeof(err_state), "%s", st);
            }
            p_clear(r);
            continue;
        }
        if (!seen_begin_done) {
            /* BEGIN result — discard, move on to user result. */
            seen_begin_done = 1;
            p_clear(r);
            continue;
        }
        /* First non-BEGIN, non-error result is the user statement's. */
        if (user_res) p_clear(r);   /* defensive, shouldn't happen */
        else user_res = r;
    }

    if (!p_exitPipelineMode(sd->pg) && !errored) {
        STMT_ERR(stmt, "PostgreSQL: PQexitPipelineMode failed: %s",
                 p_errorMessage(sd->pg));
        errored = 1;
    }

    /* Whether the bundle succeeded or not, BEGIN is now committed-or-aborted
     * server-side, so the deferred flag must clear. On error the surrounding
     * Stormify transaction will roll back via the normal error path. */
    stmt->conn->begin_pending = 0;

    if (errored) {
        if (user_res) p_clear(user_res);
        if (err_msg[0])
            STMT_ERR_V(stmt, err_state, 0, "PostgreSQL (begin+exec pipeline): %s", err_msg);
        else if (!stmt->error[0])
            STMT_ERR(stmt, "PostgreSQL: begin+exec pipeline failed");
        return KDBC_ERROR;
    }

    if (!user_res) {
        STMT_ERR(stmt, "PostgreSQL: pipelined exec missing user result");
        return KDBC_ERROR;
    }
    return pg_consume_update_result(stmt, user_res);
}

/* ========================================================================
 * Pipelined batch execution (libpq 14+)
 * ======================================================================== */

/*
 * Returns 1 if pipeline mode is available on this libpq build, 0 otherwise.
 * All four pipeline functions must be present together — there is no useful
 * subset.
 */
static int pg_pipeline_available(void) {
    return p_enterPipelineMode && p_exitPipelineMode &&
           p_pipelineSync && p_pipelineStatus;
}

/*
 * Pipelined batch execute: feeds every row to libpq in pipeline mode under a
 * single PQpipelineSync, then drains all per-row results plus the trailing
 * PGRES_PIPELINE_SYNC marker. Avoids one server round-trip per row, which is
 * the dominant cost on the legacy per-row sync loop.
 *
 * Falls back to KDBC_ERROR (which the core treats as "batch failed") only on
 * actual driver errors. If pipeline mode is unavailable on this libpq, the
 * vt->execute_batch slot is left NULL during register so the core's per-row
 * loop runs instead — see kdbc_register_postgres.
 */
static int pg_execute_batch(kdbc_stmt *stmt) {
    /* Older libpq (pre-14) lacks pipeline functions — fall back to per-row sync.
     * The fallback path through kdbc_execute_batch_per_row already flushes any
     * deferred BEGIN solo before the loop. */
    if (!pg_pipeline_available())
        return kdbc_execute_batch_per_row(stmt);

    pg_stmt_data *sd = (pg_stmt_data *)stmt->native;
    int n = stmt->batch_count;
    int begin_in_pipeline = stmt->conn->begin_pending;

    /* Server-side prepare must exist before we send a single Bind/Execute. */
    if (pg_ensure_prepared(stmt, stmt->error, KDBC_ERR_SIZE) != KDBC_OK) {
        free_batches(stmt);
        return KDBC_ERROR;
    }
    /* Drain any leftover from a prior streaming query before entering pipeline. */
    pg_drain_results(sd->pg);

    if (!p_enterPipelineMode(sd->pg)) {
        STMT_ERR(stmt, "PostgreSQL: PQenterPipelineMode failed: %s",
                 p_errorMessage(sd->pg));
        free_batches(stmt);
        return KDBC_ERROR;
    }

    int sent_ok = 1;
    /* Bundle BEGIN as the first item in the pipeline if a deferred BEGIN is
     * pending. Uses the connection-cached prepared name so the BEGIN goes
     * out as a tiny Bind+Execute, avoiding per-iter Parse cost. */
    if (begin_in_pipeline) {
        if (!p_sendQueryPrepared(sd->pg, "_kdbc_tx_begin", 0, NULL, NULL, NULL, 0)) {
            STMT_ERR(stmt, "PostgreSQL: PQsendQueryPrepared BEGIN failed: %s",
                     p_errorMessage(sd->pg));
            sent_ok = 0;
        }
    }
    for (int b = 0; b < n && sent_ok; b++) {
        if (kdbc_apply_batch_row(stmt, b) != KDBC_OK) { sent_ok = 0; break; }
        pg_exec_args args = pg_build_args(sd);
        int ok = p_sendQueryPrepared(sd->pg, sd->stmt_name, sd->param_count,
                                     args.values, args.lengths, args.formats,
                                     0 /* text result */);
        pg_free_args(&args);
        if (!ok) {
            STMT_ERR(stmt, "PostgreSQL: PQsendQueryPrepared failed at row %d: %s",
                     b, p_errorMessage(sd->pg));
            sent_ok = 0;
            break;
        }
    }

    /*
     * Sync barrier — required even on the error path so libpq drains its
     * outgoing queue. Without it the connection sits in a half-committed
     * pipeline state that the next operation would refuse.
     */
    if (!p_pipelineSync(sd->pg)) {
        STMT_ERR(stmt, "PostgreSQL: PQpipelineSync failed: %s",
                 p_errorMessage(sd->pg));
        sent_ok = 0;
    }

    /*
     * Drain results. libpq emits, per dispatched query: one or more PGresults
     * (typically one PGRES_COMMAND_OK), then a NULL terminator. After the
     * last query a PGRES_PIPELINE_SYNC closes the sync barrier. On error any
     * later command in the pipeline arrives as PGRES_PIPELINE_ABORTED — we
     * count those as zero-row updates and propagate the first real error.
     */
    int total = 0;
    int errored = !sent_ok;
    char err_msg[KDBC_ERR_SIZE];
    char err_state[8];
    err_msg[0] = '\0';
    err_state[0] = '\0';

    /* Hard cap — at most batch_count + a few extra results before we expect
     * PGRES_PIPELINE_SYNC. Acts as a safety net if libpq hangs on a broken
     * connection (PQgetResult would otherwise block forever). */
    int safety = (n + 1) * 4 + 16;
    while (safety-- > 0) {
        PGresult *r = p_getResult(sd->pg);
        if (!r) {
            /* End of one query's results — keep draining. */
            continue;
        }
        int s = p_resultStatus(r);
        if (s == PGRES_PIPELINE_SYNC) {
            p_clear(r);
            break;
        }
        if (s == PGRES_COMMAND_OK) {
            const char *ct = p_cmdTuples(r);
            total += ct ? atoi(ct) : 0;
        } else if (s == PGRES_PIPELINE_ABORTED) {
            /* Command after a previous error — counted as zero affected rows. */
            errored = 1;
        } else if (s == PGRES_FATAL_ERROR || s == PGRES_NONFATAL_ERROR) {
            errored = 1;
            if (!err_msg[0]) {
                const char *m = p_resultErrorMessage(r);
                if (m) snprintf(err_msg, sizeof(err_msg), "%s", m);
                const char *st = p_resultErrorField(r, PG_DIAG_SQLSTATE);
                if (st) snprintf(err_state, sizeof(err_state), "%s", st);
            }
        }
        p_clear(r);
    }

    /* Always exit pipeline mode, regardless of success. */
    if (!p_exitPipelineMode(sd->pg) && !errored) {
        STMT_ERR(stmt, "PostgreSQL: PQexitPipelineMode failed: %s",
                 p_errorMessage(sd->pg));
        errored = 1;
    }

    /* If BEGIN was bundled in the pipeline its result is now consumed —
     * either it landed (server is in tx) or the pipeline aborted (errored).
     * In both cases the deferred flag must clear so we don't re-send BEGIN.
     * BEGIN's PGRES_COMMAND_OK adds 0 to `total` (PQcmdTuples returns
     * "BEGIN" → atoi → 0), so no row-count adjustment is needed. */
    if (begin_in_pipeline) stmt->conn->begin_pending = 0;

    free_batches(stmt);
    if (errored) {
        if (err_msg[0])
            STMT_ERR_V(stmt, err_state, 0, "PostgreSQL (pipelined batch): %s", err_msg);
        else if (!stmt->error[0])
            STMT_ERR(stmt, "PostgreSQL: pipelined batch failed");
        return KDBC_ERROR;
    }
    return total;
}

/* Drain any remaining PGresults from a streaming query. libpq requires the
 * caller to keep calling PQgetResult until it returns NULL, otherwise the
 * connection is left in a state that breaks the next query. Each non-NULL
 * result is freed. Safe to call when no streaming is in progress (becomes
 * a no-op). */
static void pg_drain_results(PGconn *pg) {
    if (!pg) return;
    PGresult *r;
    while ((r = p_getResult(pg)) != NULL) p_clear(r);
}

static void *pg_execute_query(kdbc_stmt *stmt, int *out_col_count,
                              char *err, size_t err_size) {
    pg_stmt_data *sd = (pg_stmt_data *)stmt->native;
    if (pg_ensure_prepared(stmt, err, err_size) != KDBC_OK) return NULL;
    /* Reads are rare inside an explicit transaction in practice — a SELECT
     * wrapped by setAutoCommit(false)/commit() is the unusual case. We pay
     * one solo-BEGIN round-trip rather than complicating the streaming
     * machinery with mid-pipeline result handling. */
    if (kdbc_flush_pending_begin(stmt->conn) != KDBC_OK) {
        snprintf(err, err_size, "%s", stmt->conn->error);
        return NULL;
    }
    pg_exec_args args = pg_build_args(sd);

    /* fetch_size > 0 opts into streaming via the async + single-row-mode
     * combo. Server version 9.2+ is enforced at connect. */
    int streaming = (stmt->fetch_size > 0);
    PGresult *res = NULL;
    if (streaming) {
        /* Each row arrives as its own PGRES_SINGLE_TUPLE; bounds memory at
         * one row at a time (libpq has no native cursor batching here). */
        if (!p_sendQueryPrepared(sd->pg, sd->stmt_name, sd->param_count,
                                 args.values, args.lengths, args.formats,
                                 1 /* binary result */)) {
            pg_free_args(&args);
            snprintf(err, err_size, "PostgreSQL: %s", p_errorMessage(sd->pg));
            return NULL;
        }
        if (!p_setSingleRowMode(sd->pg)) {
            /* Non-fatal — fall back to fetching everything. The send is
             * already queued so we still have to drain it; PQgetResult
             * will return one big PGRES_TUPLES_OK with all rows. */
            streaming = 0;
        }
        res = p_getResult(sd->pg);
        pg_free_args(&args);
        if (!res) {
            snprintf(err, err_size, "PostgreSQL: %s", p_errorMessage(sd->pg));
            return NULL;
        }
    } else {
        /* Sync (eager) path: PQexecPrepared blocks until the full result is
         * client-side. Lower latency for small result sets and matches the
         * pre-streaming behaviour exactly when fetch_size is 0. */
        res = p_execPrepared(sd->pg, sd->stmt_name, sd->param_count,
                             args.values, args.lengths, args.formats,
                             1 /* binary result */);
        pg_free_args(&args);
        if (!res) {
            snprintf(err, err_size, "PostgreSQL: %s", p_errorMessage(sd->pg));
            return NULL;
        }
    }

    int status = p_resultStatus(res);
    int is_single_tuple = (status == PGRES_SINGLE_TUPLE);
    int is_tuples_ok    = (status == PGRES_TUPLES_OK);
    /* Drain on any error/cleanup path that used PQsendQueryPrepared — not
     * just when single-row mode is active. The closing NULL marker libpq
     * queues after every async send must be consumed regardless. */
    int async_path = (stmt->fetch_size > 0);
    if (!is_single_tuple && !is_tuples_ok) {
        const char *sqlst = p_resultErrorField(res, PG_DIAG_SQLSTATE);
        STMT_ERR_V(stmt, sqlst, 0,
                   "PostgreSQL: %s", p_resultErrorMessage(res));
        p_clear(res);
        if (async_path) pg_drain_results(sd->pg);
        return NULL;
    }

    int ncols = p_nfields(res);
    pg_result_set *prs = (pg_result_set *)calloc(1, sizeof(pg_result_set));
    if (!prs) {
        snprintf(err, err_size, "Out of memory");
        p_clear(res);
        if (async_path) pg_drain_results(sd->pg);
        return NULL;
    }

    prs->res = res;
    /* A SINGLE_TUPLE result holds one row at index 0; pg_rs_next bumps
     * current_row from -1 to 0 on the first call. */
    prs->row_count   = is_single_tuple ? 1 : p_ntuples(res);
    prs->col_count   = ncols;
    prs->current_row = -1;
    prs->streaming   = streaming;
    prs->pg          = (stmt->fetch_size > 0) ? sd->pg : NULL;

    /* Cache column type OIDs for efficient binary decoding */
    prs->col_types = (Oid *)calloc(ncols, sizeof(Oid));
    if (prs->col_types) {
        for (int i = 0; i < ncols; i++)
            prs->col_types[i] = p_ftype(res, i);
    }

    *out_col_count = ncols;
    return prs;
}

static int pg_get_generated_key(kdbc_stmt *stmt, int64_t *out_key) {
    if (stmt->has_generated_key) {
        *out_key = stmt->generated_key;
        return KDBC_OK;
    }
    STMT_ERR(stmt, "No generated key available");
    return KDBC_ERROR;
}

/* ========================================================================
 * Result set - binary format decoding
 *
 * When PQfformat(res, col) == 1 (binary), the data from PQgetvalue is
 * in PostgreSQL's binary wire format: big-endian for numeric types,
 * raw bytes for text/bytea.
 * ======================================================================== */

static int pg_rs_next(kdbc_result *rs) {
    pg_result_set *prs = (pg_result_set *)rs->native;
    if (!prs->streaming) {
        prs->current_row++;
        return (prs->current_row < prs->row_count) ? 1 : 0;
    }
    /* Streaming: each row is its own PGresult. The very first call after
     * pg_execute_query advances over the SINGLE_TUPLE the executor already
     * fetched — we have a row at current_row=0 with prs->res holding it.
     * Subsequent calls discard the previous PGresult and ask libpq for the
     * next one. PGRES_SINGLE_TUPLE → another row; PGRES_TUPLES_OK → end of
     * stream (the closing empty result libpq always sends after
     * single-row mode); anything else → fatal, drain and stop. */
    if (prs->current_row < 0) {
        prs->current_row = 0;
        return prs->row_count > 0 ? 1 : 0;
    }
    if (prs->res) {
        p_clear(prs->res);
        prs->res = NULL;
    }
    PGresult *next = p_getResult(prs->pg);
    if (!next) {
        prs->row_count = 0;
        return 0;
    }
    int status = p_resultStatus(next);
    if (status == PGRES_SINGLE_TUPLE) {
        prs->res = next;
        prs->current_row = 0;
        prs->row_count = 1;
        return 1;
    }
    /* Closing result (PGRES_TUPLES_OK with 0 rows) or an error. Either way
     * the stream is done — clear and drain the rest so the connection is
     * usable for the next query. */
    p_clear(next);
    pg_drain_results(prs->pg);
    prs->row_count = 0;
    return 0;
}

static const char *pg_rs_col_name(void *native_rs, int col) {
    pg_result_set *prs = (pg_result_set *)native_rs;
    return p_fname(prs->res, col - 1);
}

/* Decode PostgreSQL binary NUMERIC wire format into a canonical decimal
 * string written to `out` (capacity `cap`). Returns the number of bytes
 * written (excluding NUL) or -1 on error.
 *
 * Wire format (big-endian int16 fields):
 *   ndigits   number of base-10000 "digits" in the value
 *   weight    weight of the first digit — value = Σ digit[i] * 10000^(weight-i)
 *   sign      0x0000=pos, 0x4000=neg, 0xC000=NaN, 0xD000=+inf, 0xF000=-inf
 *   dscale    display scale: digit count after the decimal point
 *   digits[ndigits]  base-10000 digits, most significant first
 *
 * The integer part uses weight+1 base-10000 digits (zero-padded if fewer
 * digits are stored than the weight implies). The fractional part is the
 * remaining stored digits plus leading-zero padding, trimmed/padded to
 * exactly dscale decimal characters. */
static int pg_decode_numeric(const unsigned char *raw, int raw_len,
                             char *out, int cap) {
    if (raw_len < 8) return -1;
    int ndigits = (int16_t)((raw[0] << 8) | raw[1]);
    int weight  = (int16_t)((raw[2] << 8) | raw[3]);
    int sign    = (uint16_t)((raw[4] << 8) | raw[5]);
    int dscale  = (int16_t)((raw[6] << 8) | raw[7]);
    if (raw_len < 8 + ndigits * 2) return -1;

    if (sign == 0xC000) {
        if (cap < 4) return -1;
        memcpy(out, "NaN", 4); return 3;
    }
    if (sign == 0xD000) {
        if (cap < 9) return -1;
        memcpy(out, "Infinity", 9); return 8;
    }
    if (sign == 0xF000) {
        if (cap < 10) return -1;
        memcpy(out, "-Infinity", 10); return 9;
    }

    /* Read base-10000 digits. */
    int16_t digits[128];
    if (ndigits > (int)(sizeof(digits) / sizeof(digits[0]))) return -1;
    for (int i = 0; i < ndigits; i++) {
        digits[i] = (int16_t)((raw[8 + i * 2] << 8) | raw[9 + i * 2]);
    }

    int pos = 0;
    if (sign == 0x4000) { if (pos >= cap) return -1; out[pos++] = '-'; }

    /* Integer part. If weight < 0 there is no integer part — emit "0". */
    if (weight < 0) {
        if (pos >= cap) return -1;
        out[pos++] = '0';
    } else {
        for (int i = 0; i <= weight; i++) {
            int d = (i < ndigits) ? digits[i] : 0;
            if (i == 0) {
                int n = snprintf(out + pos, (size_t)(cap - pos), "%d", d);
                if (n < 0 || pos + n >= cap) return -1;
                pos += n;
            } else {
                if (pos + 4 >= cap) return -1;
                pos += snprintf(out + pos, (size_t)(cap - pos), "%04d", d);
            }
        }
    }

    /* Fractional part. */
    if (dscale > 0) {
        if (pos + 1 >= cap) return -1;
        out[pos++] = '.';
        /* The first fractional digit group is at index (weight+1) — which may
         * be beyond what's stored (in that case we synthesize leading zeros)
         * OR negative (meaning the integer part is 0 and the first stored
         * digit already lies below the decimal point, possibly with leading
         * zero groups). */
        int emitted = 0;
        int frac_index = weight + 1;
        while (emitted < dscale) {
            int d;
            if (frac_index < 0 || frac_index >= ndigits) {
                d = 0;
            } else {
                d = digits[frac_index];
            }
            frac_index++;
            /* Each group contributes 4 decimal chars but we may need fewer
             * to reach dscale exactly. */
            int need = dscale - emitted;
            if (need >= 4) {
                if (pos + 4 >= cap) return -1;
                pos += snprintf(out + pos, (size_t)(cap - pos), "%04d", d);
                emitted += 4;
            } else {
                /* Truncate the last group to the required digit count. */
                char tmp[8];
                snprintf(tmp, sizeof(tmp), "%04d", d);
                if (pos + need >= cap) return -1;
                memcpy(out + pos, tmp, (size_t)need);
                pos += need;
                emitted += need;
            }
        }
    }

    if (pos >= cap) return -1;
    out[pos] = '\0';
    return pos;
}

static int pg_rs_is_null(kdbc_result *rs, int col) {
    pg_result_set *prs = (pg_result_set *)rs->native;
    rs->last_null = p_getisnull(prs->res, prs->current_row, col - 1);
    return rs->last_null;
}

static int64_t pg_rs_get_long(kdbc_result *rs, int col) {
    pg_result_set *prs = (pg_result_set *)rs->native;
    int ci = col - 1;

    if (p_getisnull(prs->res, prs->current_row, ci)) {
        rs->last_null = 1;
        return 0;
    }
    rs->last_null = 0;

    const char *raw = p_getvalue(prs->res, prs->current_row, ci);
    int len = p_getlength(prs->res, prs->current_row, ci);
    int fmt = p_fformat(prs->res, ci);
    Oid oid = (prs->col_types && ci < prs->col_count) ? prs->col_types[ci] : 0;

    if (fmt == 1) {
        /* Only decode as binary integer when the column OID is a numeric/bool type.
         * Otherwise (e.g. TEXT/VARCHAR) the raw bytes are a UTF-8 string and must be
         * sscanf'd instead of memcpy'd — otherwise "42" (len=2) would be decoded as
         * the 16-bit big-endian integer 13362 (= 0x3432). */
        int is_int = (oid == PG_BOOL_OID || oid == PG_INT2_OID ||
                      oid == PG_INT4_OID || oid == PG_INT8_OID);
        if (is_int) {
            if (len == 1) return (int64_t)(*(int8_t *)raw);              /* bool */
            if (len == 2) { int16_t v; memcpy(&v, raw, 2); return pg_be16toh(v); }
            if (len == 4) { int32_t v; memcpy(&v, raw, 4); return pg_be32toh(v); }
            if (len == 8) { int64_t v; memcpy(&v, raw, 8); return pg_be64toh(v); }
        }
        if (oid == PG_NUMERIC_OID) {
            /* Decode the base-10000 binary NUMERIC into a text representation
             * then parse as int64. Truncates any fractional part and saturates
             * silently if the value exceeds int64 range — callers needing full
             * precision should use rs_get_string/BigDecimal. */
            char buf[64];
            int n = pg_decode_numeric((const unsigned char *)raw, len, buf, sizeof(buf));
            if (n > 0) {
                long long v = 0;
                sscanf(buf, "%lld", &v);
                return v;
            }
            return 0;
        }
        /* For TEXT-like types, the raw bytes are UTF-8 without NUL terminator. */
        if (raw && len > 0) {
            char buf[64];
            int blen = len < 63 ? len : 63;
            memcpy(buf, raw, blen);
            buf[blen] = '\0';
            long long v = 0;
            sscanf(buf, "%lld", &v);
            return v;
        }
        return 0;
    }

    /* Text format - raw is already null-terminated */
    long long v = 0;
    if (raw) sscanf(raw, "%lld", &v);
    return v;
}

static double pg_rs_get_double(kdbc_result *rs, int col) {
    pg_result_set *prs = (pg_result_set *)rs->native;
    int ci = col - 1;

    if (p_getisnull(prs->res, prs->current_row, ci)) {
        rs->last_null = 1;
        return 0.0;
    }
    rs->last_null = 0;

    const char *raw = p_getvalue(prs->res, prs->current_row, ci);
    int len = p_getlength(prs->res, prs->current_row, ci);
    int fmt = p_fformat(prs->res, ci);

    if (fmt == 1) {
        /* Binary format */
        if (len == 4) {
            /* float4 */
            int32_t bits;
            memcpy(&bits, raw, 4);
            bits = pg_be32toh(bits);
            float f;
            memcpy(&f, &bits, sizeof(f));
            return (double)f;
        }
        if (len == 8) {
            /* float8 or int8 - check type OID to distinguish */
            Oid oid = prs->col_types ? prs->col_types[ci] : 0;
            if (oid == PG_INT8_OID) {
                int64_t v;
                memcpy(&v, raw, 8);
                return (double)pg_be64toh(v);
            }
            /* Assume float8 */
            int64_t bits;
            memcpy(&bits, raw, 8);
            bits = pg_be64toh(bits);
            double d;
            memcpy(&d, &bits, sizeof(d));
            return d;
        }
        if (len == 2) { int16_t v; memcpy(&v, raw, 2); return (double)pg_be16toh(v); }
        if (len == 4) { int32_t v; memcpy(&v, raw, 4); return (double)pg_be32toh(v); }
    }

    /* Text format or fallback */
    return raw ? strtod(raw, NULL) : 0.0;
}

static const char *pg_rs_get_string(kdbc_result *rs, int col) {
    pg_result_set *prs = (pg_result_set *)rs->native;
    int ci = col - 1;

    if (p_getisnull(prs->res, prs->current_row, ci)) {
        rs->last_null = 1;
        return NULL;
    }
    rs->last_null = 0;

    const char *raw = p_getvalue(prs->res, prs->current_row, ci);
    int len = p_getlength(prs->res, prs->current_row, ci);
    int fmt = p_fformat(prs->res, ci);

    if (fmt == 0) {
        /* Text format - already a C string */
        return raw;
    }

    /* Binary format - need to convert to string based on type */
    Oid oid = prs->col_types ? prs->col_types[ci] : 0;

    switch (oid) {
        case PG_BOOL_OID:
            return (len >= 1 && raw[0]) ? "true" : "false";

        case PG_INT2_OID:
            if (len == 2) {
                int16_t v; memcpy(&v, raw, 2);
                snprintf(prs->conv_buf, sizeof(prs->conv_buf), "%d", (int)pg_be16toh(v));
                return prs->conv_buf;
            }
            break;

        case PG_INT4_OID:
            if (len == 4) {
                int32_t v; memcpy(&v, raw, 4);
                snprintf(prs->conv_buf, sizeof(prs->conv_buf), "%d", (int)pg_be32toh(v));
                return prs->conv_buf;
            }
            break;

        case PG_INT8_OID:
            if (len == 8) {
                int64_t v; memcpy(&v, raw, 8);
                snprintf(prs->conv_buf, sizeof(prs->conv_buf), "%lld", (long long)pg_be64toh(v));
                return prs->conv_buf;
            }
            break;

        case PG_FLOAT4_OID:
            if (len == 4) {
                int32_t bits; memcpy(&bits, raw, 4); bits = pg_be32toh(bits);
                float f; memcpy(&f, &bits, sizeof(f));
                snprintf(prs->conv_buf, sizeof(prs->conv_buf), "%.7g", (double)f);
                return prs->conv_buf;
            }
            break;

        case PG_FLOAT8_OID:
            if (len == 8) {
                int64_t bits; memcpy(&bits, raw, 8); bits = pg_be64toh(bits);
                double d; memcpy(&d, &bits, sizeof(d));
                snprintf(prs->conv_buf, sizeof(prs->conv_buf), "%.17g", d);
                return prs->conv_buf;
            }
            break;

        case PG_TEXT_OID:
        case PG_VARCHAR_OID:
            /* Text in binary format is just raw UTF-8 bytes, but NOT null-terminated!
             * We need to copy into a buffer with null terminator. */
            if (kdbc_ensure_strbuf(rs, len + 1) == 0) {
                memcpy(rs->str_buf, raw, len);
                rs->str_buf[len] = '\0';
                return rs->str_buf;
            }
            break;

        case PG_NUMERIC_OID:
            /* Decode base-10000 NUMERIC into a canonical decimal string. */
            if (kdbc_ensure_strbuf(rs, (size_t)len * 2 + 32) == 0) {
                int n = pg_decode_numeric((const unsigned char *)raw, len,
                                          rs->str_buf, (int)rs->str_buf_cap);
                if (n >= 0) return rs->str_buf;
            }
            break;

        case PG_BYTEA_OID:
            /* Return hex representation for bytea as string */
            if (kdbc_ensure_strbuf(rs, len * 2 + 3) == 0) {
                rs->str_buf[0] = '\\';
                rs->str_buf[1] = 'x';
                for (int i = 0; i < len; i++)
                    sprintf(rs->str_buf + 2 + i * 2, "%02x", (unsigned char)raw[i]);
                return rs->str_buf;
            }
            break;

        default:
            /* Unknown binary type - try to return raw bytes as string if printable,
             * otherwise return hex representation */
            if (kdbc_ensure_strbuf(rs, len + 1) == 0) {
                memcpy(rs->str_buf, raw, len);
                rs->str_buf[len] = '\0';
                return rs->str_buf;
            }
            break;
    }

    return NULL;
}

static const void *pg_rs_get_blob(kdbc_result *rs, int col, size_t *out_len) {
    pg_result_set *prs = (pg_result_set *)rs->native;
    int ci = col - 1;

    if (p_getisnull(prs->res, prs->current_row, ci)) {
        rs->last_null = 1;
        *out_len = 0;
        return NULL;
    }
    rs->last_null = 0;

    /* In binary format, PQgetvalue returns raw bytes directly */
    *out_len = (size_t)p_getlength(prs->res, prs->current_row, ci);
    return p_getvalue(prs->res, prs->current_row, ci);
}

/* Date/time result retrieval: PG binary format */
static int pg_rs_get_timestamp(kdbc_result *rs, int col,
                               int *year, int *month, int *day,
                               int *hour, int *minute, int *second, int *usec) {
    pg_result_set *prs = (pg_result_set *)rs->native;
    int ci = col - 1;
    if (p_getisnull(prs->res, prs->current_row, ci)) { rs->last_null = 1; return KDBC_ERROR; }
    rs->last_null = 0;
    const char *raw = p_getvalue(prs->res, prs->current_row, ci);
    int len = p_getlength(prs->res, prs->current_row, ci);
    int fmt = p_fformat(prs->res, ci);

    if (fmt == 1 && len == 8) {
        /* Binary: int64 microseconds since 2000-01-01 */
        int64_t pg_usec;
        memcpy(&pg_usec, raw, 8);
        pg_usec = pg_be64toh(pg_usec);
        int64_t unix_usec = pg_usec + PG_EPOCH_OFFSET_USEC;
        int64_t total_sec = unix_usec / 1000000;
        int us = (int)(unix_usec % 1000000);
        if (us < 0) { total_sec--; us += 1000000; }
        int days = (int)(total_sec / 86400);
        int day_sec = (int)(total_sec % 86400);
        if (day_sec < 0) { days--; day_sec += 86400; }
        civil_from_days(days, year, month, day);
        *hour = day_sec / 3600;
        *minute = (day_sec % 3600) / 60;
        *second = day_sec % 60;
        *usec = us;
        return KDBC_OK;
    }
    /* Text fallback */
    *usec = 0;
    if (sscanf(raw, "%d-%d-%d %d:%d:%d.%d", year, month, day, hour, minute, second, usec) >= 6)
        return KDBC_OK;
    if (sscanf(raw, "%d-%d-%dT%d:%d:%d.%d", year, month, day, hour, minute, second, usec) >= 6)
        return KDBC_OK;
    RS_ERR(rs, "PostgreSQL: cannot parse timestamp value");
    return KDBC_ERROR;
}

static int pg_rs_get_date(kdbc_result *rs, int col, int *year, int *month, int *day) {
    pg_result_set *prs = (pg_result_set *)rs->native;
    int ci = col - 1;
    if (p_getisnull(prs->res, prs->current_row, ci)) { rs->last_null = 1; return KDBC_ERROR; }
    rs->last_null = 0;
    const char *raw = p_getvalue(prs->res, prs->current_row, ci);
    int len = p_getlength(prs->res, prs->current_row, ci);
    int fmt = p_fformat(prs->res, ci);

    if (fmt == 1 && len == 4) {
        /* Binary: int32 days since 2000-01-01 */
        int32_t pg_days;
        memcpy(&pg_days, raw, 4);
        pg_days = pg_be32toh(pg_days);
        civil_from_days(pg_days + PG_EPOCH_OFFSET_DAYS, year, month, day);
        return KDBC_OK;
    }
    return (sscanf(raw, "%d-%d-%d", year, month, day) >= 3) ? KDBC_OK : KDBC_ERROR;
}

static int pg_rs_get_time(kdbc_result *rs, int col,
                          int *hour, int *minute, int *second, int *usec) {
    pg_result_set *prs = (pg_result_set *)rs->native;
    int ci = col - 1;
    if (p_getisnull(prs->res, prs->current_row, ci)) { rs->last_null = 1; return KDBC_ERROR; }
    rs->last_null = 0;
    const char *raw = p_getvalue(prs->res, prs->current_row, ci);
    int len = p_getlength(prs->res, prs->current_row, ci);
    int fmt = p_fformat(prs->res, ci);

    if (fmt == 1 && len == 8) {
        /* Binary: int64 microseconds since midnight */
        int64_t time_usec;
        memcpy(&time_usec, raw, 8);
        time_usec = pg_be64toh(time_usec);
        *usec = (int)(time_usec % 1000000);
        int total_sec = (int)(time_usec / 1000000);
        *hour = total_sec / 3600;
        *minute = (total_sec % 3600) / 60;
        *second = total_sec % 60;
        return KDBC_OK;
    }
    *usec = 0;
    return (sscanf(raw, "%d:%d:%d.%d", hour, minute, second, usec) >= 3) ? KDBC_OK : KDBC_ERROR;
}

static void pg_rs_close(void *native_rs) {
    pg_result_set *prs = (pg_result_set *)native_rs;
    if (prs) {
        if (prs->res) p_clear(prs->res);
        /* Drain whenever the dispatch used PQsendQueryPrepared (covers
         * early-close mid-stream, eager fallback that still has the closing
         * marker queued, and the idempotent re-drain of a fully-consumed
         * stream). Skipping it leaves libpq in async state and wedges the
         * next query with "another command is already in progress". */
        if (prs->pg) pg_drain_results(prs->pg);
        free(prs->col_types);
        free(prs);
    }
}

/* ========================================================================
 * Callable statements
 *
 * PostgreSQL procedures (PG 11+, introduced by CREATE PROCEDURE / CALL) with
 * OUT and INOUT parameters return their OUT/INOUT values as a regular result
 * set from CALL. Each OUT/INOUT parameter becomes one column in the result,
 * in the order declared in the procedure signature. Pure IN parameters do
 * not appear in the output.
 *
 * PG 14+ is required for CALL to return OUT values via libpq — earlier
 * versions silently discarded them.
 *
 * Implementation: run the prepared CALL via PQexecPrepared in text result
 * format (so we can read arbitrary types as strings and cache them into
 * stmt->out_values[]), then walk the output columns and map them to the
 * corresponding OUT/INOUT param slots. The core's call_get_long / call_get_string
 * fallback path reads from out_values[] — no per-driver call_get_out needed.
 * ======================================================================== */

static int pg_call_execute(kdbc_stmt *stmt) {
    pg_stmt_data *sd = (pg_stmt_data *)stmt->native;
    if (pg_ensure_prepared(stmt, stmt->error, KDBC_ERR_SIZE) != KDBC_OK)
        return KDBC_ERROR;

    pg_exec_args args = pg_build_args(sd);
    /* Text result format for simplicity — out values are parsed as strings
     * and cached in stmt->out_values[]. */
    PGresult *res = p_execPrepared(sd->pg, sd->stmt_name,
                                   sd->param_count,
                                   args.values, args.lengths, args.formats,
                                   0 /* text result */);
    pg_free_args(&args);

    if (!res) {
        STMT_ERR(stmt, "PostgreSQL: %s", p_errorMessage(sd->pg));
        return KDBC_ERROR;
    }

    int status = p_resultStatus(res);
    if (status != PGRES_TUPLES_OK && status != PGRES_COMMAND_OK) {
        const char *sqlst = p_resultErrorField(res, PG_DIAG_SQLSTATE);
        STMT_ERR_V(stmt, sqlst, 0,
                   "PostgreSQL: %s", p_resultErrorMessage(res));
        p_clear(res);
        return KDBC_ERROR;
    }

    /* Map result columns back to OUT/INOUT param slots.
     *
     * PG's CALL returns one row where each column corresponds to the next
     * OUT or INOUT parameter (IN params are skipped). Walk the params array
     * and assign columns in order. */
    if (status == PGRES_TUPLES_OK && p_ntuples(res) > 0
        && stmt->out_params && stmt->out_values) {
        int ncols = p_nfields(res);
        int col = 0;
        for (int i = 0; i < stmt->param_count && col < ncols; i++) {
            if (!stmt->out_params[i]) continue;  /* skip pure IN params */

            free(stmt->out_values[i]);
            stmt->out_values[i] = NULL;

            if (!p_getisnull(res, 0, col)) {
                const char *v = p_getvalue(res, 0, col);
                int len = p_getlength(res, 0, col);
                stmt->out_values[i] = (char *)malloc((size_t)len + 1);
                if (stmt->out_values[i]) {
                    memcpy(stmt->out_values[i], v, (size_t)len);
                    stmt->out_values[i][len] = '\0';
                }
            }
            col++;
        }
    }

    p_clear(res);
    return 1;
}

/* ========================================================================
 * Driver vtable
 * ======================================================================== */

static const kdbc_driver_vtable postgres_vtable = {
    .name               = "PostgreSQL",
    .gk_strategy        = KDBC_GK_BY_NAME,
    .supports_release_savepoint = 1,
    .load               = pg_load,
    .loaded             = pg_loaded,
    .connect            = pg_connect,
    .close              = pg_close,
    .cancel             = pg_cancel,
    .exec_direct        = NULL,
    .query_direct       = NULL,
    .set_autocommit     = pg_set_autocommit,
    .commit             = pg_commit,
    .rollback           = pg_rollback,
    .begin_tx           = pg_begin_tx,
    .get_product_name   = pg_get_product_name,
    .get_product_version = pg_get_product_version,
    .get_major_version  = pg_get_major_version,
    .get_minor_version  = pg_get_minor_version,
    .prepare            = pg_prepare,
    .stmt_close         = pg_stmt_close,
    .bind_null          = pg_bind_null,
    .bind_bool          = pg_bind_bool,
    .bind_int           = pg_bind_int,
    .bind_long          = pg_bind_long,
    .bind_double        = pg_bind_double,
    .bind_string        = pg_bind_string,
    .bind_blob          = pg_bind_blob,
    .bind_timestamp     = pg_bind_timestamp,
    .bind_date          = pg_bind_date,
    .bind_time          = pg_bind_time,
    .execute_update     = pg_execute_update,
    .execute_query      = pg_execute_query,
    .get_generated_key  = pg_get_generated_key,
    /* pg_execute_batch internally checks pg_pipeline_available() and falls back to
     * the per-row helper from kdbc_core when libpq lacks pipeline functions. */
    .execute_batch      = pg_execute_batch,
    .rs_next            = pg_rs_next,
    .rs_col_name        = pg_rs_col_name,
    .rs_col_label       = NULL,
    .rs_is_null         = pg_rs_is_null,
    .rs_get_long        = pg_rs_get_long,
    .rs_get_double      = pg_rs_get_double,
    .rs_get_string      = pg_rs_get_string,
    .rs_get_blob        = pg_rs_get_blob,
    .rs_get_timestamp   = pg_rs_get_timestamp,
    .rs_get_date        = pg_rs_get_date,
    .rs_get_time        = pg_rs_get_time,
    .rs_close           = pg_rs_close,
    .stmt_reset         = NULL,
    .conn_gk_strategy   = NULL,
    .prepare_call       = NULL,
    .call_execute       = pg_call_execute,
    .call_get_out       = NULL,
};

void kdbc_register_postgres(void) {
    kdbc_register_driver(KDBC_POSTGRES, &postgres_vtable);
}
