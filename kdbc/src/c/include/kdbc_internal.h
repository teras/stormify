/*
 * KDBC Native - Internal structures and driver vtable
 *
 * Not part of the public API. Included only by driver implementations
 * and the core dispatch layer.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
#ifndef KDBC_INTERNAL_H
#define KDBC_INTERNAL_H

#define _POSIX_C_SOURCE 200809L
#define _DEFAULT_SOURCE

#include "kdbc.h"
#include <ctype.h>
#include <string.h>
#include <strings.h>
#include <stdlib.h>
#include <stdio.h>
#include <stdarg.h>

/* ========================================================================
 * Error buffer
 * ======================================================================== */

#define KDBC_ERR_SIZE 1024

/*
 * Thread-local global error buffer for connection-less errors.
 *
 * iOS does not support C11 _Thread_local in static libraries (dyld limitation),
 * so we fall back to pthread_key_t thread-specific storage there. All other
 * platforms use the faster _Thread_local.
 */
#if defined(__APPLE__) && __has_include(<TargetConditionals.h>)
  #include <TargetConditionals.h>
#endif

/* Thread-local global vendor metadata, paired with `g_error`. The pthread_key
 * fallback for iOS allocates a single combined slab so both buffers share the
 * lifetime of one thread-specific key. */
#if defined(TARGET_OS_IPHONE) && TARGET_OS_IPHONE
  #include <pthread.h>
  typedef struct { char err[KDBC_ERR_SIZE]; char sqlstate[6]; int errcode; } _g_err_block;
  static pthread_key_t  _g_error_key;
  static pthread_once_t _g_error_once = PTHREAD_ONCE_INIT;
  static void _g_error_init(void) { pthread_key_create(&_g_error_key, free); }
  static inline _g_err_block *_g_error_block(void) {
      pthread_once(&_g_error_once, _g_error_init);
      _g_err_block *blk = (_g_err_block *)pthread_getspecific(_g_error_key);
      if (!blk) { blk = (_g_err_block *)calloc(1, sizeof(*blk)); pthread_setspecific(_g_error_key, blk); }
      return blk;
  }
  #define g_error    (_g_error_block()->err)
  #define g_sqlstate (_g_error_block()->sqlstate)
  #define g_errcode  (_g_error_block()->errcode)
#else
  static _Thread_local char _g_error_storage[KDBC_ERR_SIZE] = "";
  static _Thread_local char _g_sqlstate_storage[6] = "";
  static _Thread_local int  _g_errcode_storage = 0;
  #define g_error    _g_error_storage
  #define g_sqlstate _g_sqlstate_storage
  #define g_errcode  _g_errcode_storage
#endif

static inline void kdbc_set_global_error(const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(g_error, KDBC_ERR_SIZE, fmt, ap);
    va_end(ap);
    g_sqlstate[0] = '\0';
    g_errcode = 0;
}

/* Global error setter that also captures vendor sqlstate/errcode. Pass NULL
 * for sqlstate when the driver does not provide one. */
static inline void kdbc_set_global_error_v(const char *sqlstate, int errcode,
                                           const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(g_error, KDBC_ERR_SIZE, fmt, ap);
    va_end(ap);
    if (sqlstate) {
        size_t n = strlen(sqlstate);
        if (n > 5) n = 5;
        memcpy(g_sqlstate, sqlstate, n);
        g_sqlstate[n] = '\0';
    } else {
        g_sqlstate[0] = '\0';
    }
    g_errcode = errcode;
}

/* ========================================================================
 * Internal handle structures
 * ======================================================================== */

/* Forward declare driver vtable */
typedef struct kdbc_driver_vtable kdbc_driver_vtable;

struct kdbc_conn {
    kdbc_driver          driver;
    const kdbc_driver_vtable *vt;
    void                *native;     /* driver-specific connection handle */
    int                  autocommit; /* 1 = autocommit on (default) */
    /* Deferred-BEGIN flag — set by kdbc_set_autocommit(false) on drivers that
     * opt into the optimisation via vt->begin_tx. The actual BEGIN/START
     * TRANSACTION is delayed until the next execute, where the driver can
     * either send it solo (via vt->begin_tx) or piggyback it on the user's
     * statement using protocol-level pipelining (libpq pipeline mode).
     * Drivers that don't opt in (vt->begin_tx == NULL) keep eager-BEGIN
     * behaviour and never see this flag set. */
    int                  begin_pending;
    char                 error[KDBC_ERR_SIZE];
    char                 sqlstate[6];   /* SQL-92 SQLSTATE, "" if driver does not provide */
    int                  errcode;       /* vendor-specific numeric code, 0 if none */
    char                 product_name[128];
    char                 product_version[256];
};

/* Parameter storage for prepared statements.
 *
 * Invariant: for temporal types (KDBC_TYPE_DATE/TIME/TIMESTAMP) and scalar
 * types (NULL/INT/LONG/DOUBLE/BOOL), `owned` MUST be NULL. Only KDBC_TYPE_STRING
 * and KDBC_TYPE_BLOB allocate an owned heap copy. Violating this invariant
 * causes double-free via the batch snapshot, which deep-copies the kdbc_param
 * array but does not duplicate the `owned` buffer — snapshot and live slot
 * would both point to the same block and both would be freed. */
typedef struct {
    kdbc_type type;
    union {
        int64_t  i64;
        double   dbl;
        struct { const char *ptr; size_t len; } str;
        struct { const void *ptr; size_t len; } blob;
        /* Decomposed temporal value. Shared by DATE (time fields zero),
         * TIME (date fields zero), and TIMESTAMP (all fields used).
         * 12 bytes, fits in the existing 16-byte union slot with 4 bytes slack.
         * Layout mirrors Oracle's dpiTimestamp; widens to int at call-sites
         * via implicit C integer promotion. */
        struct {
            int16_t year;
            uint8_t month, day;
            uint8_t hour, minute, second;
            uint8_t _pad;
            int32_t usec;
        } ts;
    } val;
    /* For string/blob copies that we own. MUST be NULL for non-STRING/BLOB types. */
    void *owned;
} kdbc_param;

struct kdbc_stmt {
    kdbc_conn  *conn;
    void       *native;     /* driver-specific statement handle */
    char       *sql;        /* original SQL (with ? placeholders) */
    char       *native_sql; /* translated SQL ($1, :1, etc.) */
    int         param_count;
    kdbc_param *params;
    /* For returning generated keys */
    char      **ret_col_names;
    int         ret_col_count;
    int         generated_keys_requested; /* prepare_returning was called, even if col_names is NULL */
    /* Generated key after INSERT. For numeric PKs (AUTO_INCREMENT, sequences)
     * drivers populate `generated_key`. For non-numeric PKs (UUID, VARCHAR2,
     * ROWID) drivers populate `generated_key_str` with the canonical text
     * representation; `kdbc_generated_keys` then surfaces that string through
     * the synthetic result set instead of formatting the int64 as text. */
    int64_t     generated_key;
    int         has_generated_key;
    char       *generated_key_str;  /* owned; may be NULL */
    /* Fetch size hint (0 = default) */
    int          fetch_size;
    /* Batch execution */
    kdbc_param **batches;     /* array of param snapshots */
    int          batch_count;
    int          batch_cap;
    /* Callable statement */
    int          is_callable;
    int         *out_params;  /* bitmask: 1 = registered as OUT */
    /* OUT param results (stored after execution) */
    char       **out_values;  /* string representation of OUT values */
    char        error[KDBC_ERR_SIZE];
    char        sqlstate[6];  /* SQL-92 SQLSTATE, "" if driver does not provide */
    int         errcode;      /* vendor-specific numeric code, 0 if none */
};

struct kdbc_result {
    kdbc_conn  *conn;
    void       *native;     /* driver-specific result handle */
    int         col_count;
    int         current_row; /* -1 = before first */
    int         last_null;   /* was last get_* call null? */
    /* For direct query: owns the stmt, auto-closed with result */
    kdbc_stmt  *owned_stmt;
    /* String conversion buffer (reusable) */
    char       *str_buf;
    size_t      str_buf_cap;
    /* Synthetic result (native == NULL) column name — owned, freed on close */
    char       *synthetic_col_name;
    char        error[KDBC_ERR_SIZE];
    char        sqlstate[6];  /* SQL-92 SQLSTATE, "" if driver does not provide */
    int         errcode;      /* vendor-specific numeric code, 0 if none */
};

/* ========================================================================
 * Driver vtable - each driver implements these
 * ======================================================================== */

struct kdbc_driver_vtable {
    /* Driver info */
    const char *name;
    kdbc_gk_strategy gk_strategy;
    int supports_release_savepoint;

    /* Library loading */
    int  (*load)(void);    /* attempt to dlopen, returns 1 on success */
    int  (*loaded)(void);  /* is library already loaded? */

    /* Connection */
    void *(*connect)(const char *url, const char *user, const char *password,
                     char *err, size_t err_size);
    void  (*close)(void *native);

    /* Best-effort async cancellation of any currently-executing statement
     * on this connection. Must be safe to call from a thread different from
     * the one currently inside a blocking driver call on the same connection.
     * Optional: if NULL, kdbc_cancel() returns KDBC_ERROR. */
    int (*cancel)(kdbc_conn *conn);

    /* Direct SQL execution (no prepare - for DDL, SAVEPOINT, etc.)
     * Optional: if NULL, falls back to prepare+execute. */
    int (*exec_direct)(kdbc_conn *conn, const char *sql);

    /* Direct query execution (no prepare - for SELECT without params).
     * Optional: if NULL, falls back to prepare+execute_query.
     * Returns native result handle + col_count, like execute_query vtable. */
    void *(*query_direct)(kdbc_conn *conn, const char *sql, int *out_col_count,
                          char *err, size_t err_size);

    /* Transaction */
    int (*set_autocommit)(kdbc_conn *conn, int enabled);
    int (*commit)(kdbc_conn *conn);
    int (*rollback)(kdbc_conn *conn);

    /* Optional. Drivers set this to opt into deferred-BEGIN: kdbc_set_autocommit
     * stops calling set_autocommit and instead toggles conn->begin_pending. The
     * driver is then expected to call kdbc_flush_pending_begin (or check the
     * flag inline) at the start of every execute path so the BEGIN actually
     * reaches the server before any DML. The fallback implementation here
     * (when only this hook is provided) sends BEGIN as a solo round-trip;
     * drivers with protocol-level pipelining can ignore this hook on the hot
     * paths and bundle the BEGIN with the next statement themselves. */
    int (*begin_tx)(kdbc_conn *conn);

    /* Metadata */
    void (*get_product_name)(void *native, char *buf, size_t buf_size);
    void (*get_product_version)(void *native, char *buf, size_t buf_size);
    int  (*get_major_version)(void *native);
    int  (*get_minor_version)(void *native);

    /* Statement preparation.
     * generated_keys_requested=1 when caller used kdbc_prepare_returning, even
     * if ret_cols is NULL — drivers can use this to pick an identity-retrieval
     * strategy when no explicit columns were named (e.g. MSSQL OUTPUT INSERTED.$IDENTITY). */
    void *(*prepare)(kdbc_conn *conn, const char *native_sql,
                     const char **ret_cols, int n_ret_cols,
                     int generated_keys_requested,
                     char *err, size_t err_size);
    void  (*stmt_close)(void *native_stmt, void *native_conn);

    /* Parameter binding */
    int (*bind_null)(kdbc_stmt *stmt, int idx);
    int (*bind_bool)(kdbc_stmt *stmt, int idx, int val);
    int (*bind_int)(kdbc_stmt *stmt, int idx, int val);
    int (*bind_long)(kdbc_stmt *stmt, int idx, int64_t val);
    int (*bind_double)(kdbc_stmt *stmt, int idx, double val);
    int (*bind_string)(kdbc_stmt *stmt, int idx, const char *val);
    int (*bind_blob)(kdbc_stmt *stmt, int idx, const void *data, size_t len);
    int (*bind_timestamp)(kdbc_stmt *stmt, int idx,
                          int year, int month, int day,
                          int hour, int minute, int second, int usec);
    int (*bind_date)(kdbc_stmt *stmt, int idx, int year, int month, int day);
    int (*bind_time)(kdbc_stmt *stmt, int idx, int hour, int minute, int second, int usec);

    /* Execution */
    int   (*execute_update)(kdbc_stmt *stmt);
    void *(*execute_query)(kdbc_stmt *stmt, int *out_col_count,
                           char *err, size_t err_size);
    int   (*get_generated_key)(kdbc_stmt *stmt, int64_t *out_key);

    /*
     * Optional batch-execute hook. NULL means "fall back to the per-row loop in
     * kdbc_execute_batch (one synchronous execute_update per snapshot)". Drivers that
     * can do better — Postgres pipeline mode, Oracle dpiStmt_executeMany, MariaDB
     * STMT_ATTR_ARRAY_SIZE — set this to bundle all snapshot rows into a single
     * round-trip (or as close as the wire protocol allows).
     *
     * Contract: the implementation reads stmt->batches[0..batch_count-1] (each is a
     * kdbc_param[] of stmt->param_count slots), restores the parameters via the same
     * vt->bind_* callbacks the loop would use, and dispatches them. On success it
     * returns the SUM of affected-row counts and frees the batch storage with
     * free_batches(stmt). On error it returns KDBC_ERROR after also freeing batches.
     */
    int   (*execute_batch)(kdbc_stmt *stmt);

    /* Result set */
    int         (*rs_next)(kdbc_result *rs);
    const char *(*rs_col_name)(void *native_rs, int col);
    const char *(*rs_col_label)(void *native_rs, int col);
    int         (*rs_is_null)(kdbc_result *rs, int col);
    int64_t     (*rs_get_long)(kdbc_result *rs, int col);
    double      (*rs_get_double)(kdbc_result *rs, int col);
    const char *(*rs_get_string)(kdbc_result *rs, int col);
    const void *(*rs_get_blob)(kdbc_result *rs, int col, size_t *out_len);
    int         (*rs_get_timestamp)(kdbc_result *rs, int col,
                                    int *year, int *month, int *day,
                                    int *hour, int *minute, int *second, int *usec);
    int         (*rs_get_date)(kdbc_result *rs, int col, int *year, int *month, int *day);
    int         (*rs_get_time)(kdbc_result *rs, int col, int *hour, int *minute, int *second, int *usec);
    void        (*rs_close)(void *native_rs);

    /* Statement reset (optional - NULL if not supported) */
    int (*stmt_reset)(void *native_stmt);

    /* Runtime GK strategy override (optional - NULL uses driver default).
     * Used by Oracle to return NONE for 11g, BY_NAME for 12c+. */
    kdbc_gk_strategy (*conn_gk_strategy)(void *native);

    /* Callable statement support (optional - NULL means not supported).
     * prepare_call: prepare a stored proc call from cleaned SQL.
     * call_execute: execute and populate OUT params.
     * call_get_out: retrieve OUT param at idx as string. */
    void *(*prepare_call)(kdbc_conn *conn, const char *proc_sql,
                          char *err, size_t err_size);
    int   (*call_execute)(kdbc_stmt *stmt);
    const char *(*call_get_out)(kdbc_stmt *stmt, int idx);
};

/* ========================================================================
 * Helper macros for setting errors
 *
 * The plain CONN_ERR/STMT_ERR/RS_ERR macros set only the message and clear
 * the structured vendor metadata so a previous driver-level error does not
 * leak through. The _V variants additionally take an SQLSTATE string (or
 * NULL) and a vendor errcode and copy them into the handle. SQLSTATE is
 * truncated to 5 characters as defined by SQL-92.
 * ======================================================================== */

static inline void kdbc_copy_sqlstate(char *dst, const char *src) {
    if (!src) { dst[0] = '\0'; return; }
    size_t n = strlen(src);
    if (n > 5) n = 5;
    memcpy(dst, src, n);
    dst[n] = '\0';
}

#define CONN_ERR(conn, fmt, ...) do { \
    snprintf((conn)->error, KDBC_ERR_SIZE, fmt, ##__VA_ARGS__); \
    (conn)->sqlstate[0] = '\0'; \
    (conn)->errcode = 0; \
} while (0)

#define STMT_ERR(stmt, fmt, ...) do { \
    snprintf((stmt)->error, KDBC_ERR_SIZE, fmt, ##__VA_ARGS__); \
    (stmt)->sqlstate[0] = '\0'; \
    (stmt)->errcode = 0; \
} while (0)

#define RS_ERR(rs, fmt, ...) do { \
    snprintf((rs)->error, KDBC_ERR_SIZE, fmt, ##__VA_ARGS__); \
    (rs)->sqlstate[0] = '\0'; \
    (rs)->errcode = 0; \
} while (0)

#define CONN_ERR_V(conn, sqlstate_str, code, fmt, ...) do { \
    snprintf((conn)->error, KDBC_ERR_SIZE, fmt, ##__VA_ARGS__); \
    kdbc_copy_sqlstate((conn)->sqlstate, (sqlstate_str)); \
    (conn)->errcode = (code); \
} while (0)

#define STMT_ERR_V(stmt, sqlstate_str, code, fmt, ...) do { \
    snprintf((stmt)->error, KDBC_ERR_SIZE, fmt, ##__VA_ARGS__); \
    kdbc_copy_sqlstate((stmt)->sqlstate, (sqlstate_str)); \
    (stmt)->errcode = (code); \
} while (0)

#define RS_ERR_V(rs, sqlstate_str, code, fmt, ...) do { \
    snprintf((rs)->error, KDBC_ERR_SIZE, fmt, ##__VA_ARGS__); \
    kdbc_copy_sqlstate((rs)->sqlstate, (sqlstate_str)); \
    (rs)->errcode = (code); \
} while (0)

/* ========================================================================
 * SQL translation helpers
 * ======================================================================== */

/**
 * Check if current position is inside a SQL context where ? should be ignored.
 * Handles: 'single quotes', "double quotes", -- line comments, and block comments.
 * Returns 1 if the character at *p is "code" (not in string/comment), 0 otherwise.
 * Advances *p past the non-code region if applicable.
 */
typedef struct {
    int in_single;
    int in_double;
    int in_line_comment;
    int in_block_comment;
} sql_scan_state;

static inline void sql_scan_init(sql_scan_state *s) {
    memset(s, 0, sizeof(*s));
}

/**
 * Process one character, updating scan state.
 * Returns 1 if *p is "live SQL" (not in string or comment), 0 otherwise.
 */
static inline int sql_scan_char(const char **pp, sql_scan_state *s) {
    const char *p = *pp;
    char c = *p;

    if (s->in_line_comment) {
        if (c == '\n') s->in_line_comment = 0;
        return 0;
    }
    if (s->in_block_comment) {
        if (c == '*' && p[1] == '/') { s->in_block_comment = 0; (*pp)++; }
        return 0;
    }
    if (s->in_single) {
        if (c == '\'' && p[1] == '\'') { (*pp)++; return 0; } /* escaped quote */
        if (c == '\'') s->in_single = 0;
        return 0;
    }
    if (s->in_double) {
        if (c == '"') s->in_double = 0;
        return 0;
    }
    /* Live SQL */
    if (c == '\'') { s->in_single = 1; return 0; }
    if (c == '"')  { s->in_double = 1; return 0; }
    if (c == '-' && p[1] == '-') { s->in_line_comment = 1; (*pp)++; return 0; }
    if (c == '/' && p[1] == '*') { s->in_block_comment = 1; (*pp)++; return 0; }
    return 1; /* this is live SQL */
}

/**
 * Extract the procedure name from a "VERB proc_name[(...)]" SQL string.
 * Used by callable-statement drivers (MariaDB, MSSQL) to get the target
 * procedure name from the core's rewritten CALL/EXEC text. Accepts any
 * of the passed-in verb keywords (case-insensitive), writes up to
 * buf_size-1 chars into buf, and NUL-terminates. Returns 1 on success,
 * 0 if the SQL does not start with one of `verbs[]`.
 *
 * Identifier character set: letters, digits, underscore, $, dot, backtick,
 * hash, and square brackets — a superset that covers MySQL/MariaDB
 * (backticks), SQL Server (brackets, schema.proc), and MS-style temp names.
 */
static inline int kdbc_extract_proc_name(const char *sql,
                                          const char *const *verbs, int n_verbs,
                                          char *buf, size_t buf_size) {
    while (*sql == ' ' || *sql == '\t' || *sql == '\n' || *sql == '\r') sql++;

    const char *after_verb = NULL;
    for (int i = 0; i < n_verbs; i++) {
        size_t vlen = strlen(verbs[i]);
        if (strncasecmp(sql, verbs[i], vlen) == 0
            && (sql[vlen] == ' ' || sql[vlen] == '\t')) {
            after_verb = sql + vlen;
            break;
        }
    }
    if (!after_verb) return 0;

    while (*after_verb == ' ' || *after_verb == '\t') after_verb++;

    size_t i = 0;
    while (*after_verb && i < buf_size - 1 &&
           (isalnum((unsigned char)*after_verb) || *after_verb == '_' ||
            *after_verb == '$' || *after_verb == '.' || *after_verb == '`' ||
            *after_verb == '#' || *after_verb == '[' || *after_verb == ']')) {
        buf[i++] = *after_verb++;
    }
    buf[i] = '\0';
    return i > 0 ? 1 : 0;
}

/**
 * Count ? placeholders in SQL.
 * Respects quoted strings, -- line comments, and block comments.
 */
static inline int kdbc_count_params(const char *sql) {
    int count = 0;
    sql_scan_state s;
    sql_scan_init(&s);
    for (const char *p = sql; *p; p++) {
        if (sql_scan_char(&p, &s) && *p == '?') count++;
    }
    return count;
}

/**
 * Translate ? placeholders to numbered params ($1, $2, ... for PostgreSQL).
 * Respects strings and comments. Caller must free the returned string.
 */
static inline char *kdbc_translate_params_dollar(const char *sql) {
    int count = kdbc_count_params(sql);
    size_t len = strlen(sql) + (size_t)count * 4 + 1;
    char *out = (char *)malloc(len);
    if (!out) return NULL;

    char *dst = out;
    int param_num = 0;
    sql_scan_state sc;
    sql_scan_init(&sc);
    for (const char *p = sql; *p; p++) {
        const char *before = p;
        int live = sql_scan_char(&p, &sc);
        /* Copy any chars we skipped (e.g. second char of -- or *‍/) */
        for (const char *q = before; q <= p; q++) {
            if (live && *q == '?') {
                param_num++;
                dst += sprintf(dst, "$%d", param_num);
            } else {
                *dst++ = *q;
            }
        }
    }
    *dst = '\0';
    return out;
}

/**
 * Translate ? placeholders to :1, :2, ... for Oracle.
 * Respects strings and comments. Caller must free the returned string.
 */
static inline char *kdbc_translate_params_colon(const char *sql) {
    int count = kdbc_count_params(sql);
    size_t len = strlen(sql) + (size_t)count * 4 + 1;
    char *out = (char *)malloc(len);
    if (!out) return NULL;

    char *dst = out;
    int param_num = 0;
    sql_scan_state sc;
    sql_scan_init(&sc);
    for (const char *p = sql; *p; p++) {
        const char *before = p;
        int live = sql_scan_char(&p, &sc);
        for (const char *q = before; q <= p; q++) {
            if (live && *q == '?') {
                param_num++;
                dst += sprintf(dst, ":%d", param_num);
            } else {
                *dst++ = *q;
            }
        }
    }
    *dst = '\0';
    return out;
}

/**
 * Duplicate SQL as-is (for SQLite, MariaDB, MSSQL which use ? natively).
 */
static inline char *kdbc_translate_params_native(const char *sql) {
    return strdup(sql);
}

/* ========================================================================
 * Driver registration
 * ======================================================================== */

/* Each driver file defines its vtable and registers here */
extern const kdbc_driver_vtable *kdbc_drivers[KDBC_DRIVER_COUNT];

/*
 * Restore the parameter snapshot for a single batch row by replaying it through the
 * connection's vt->bind_* callbacks. Drivers that implement vt->execute_batch (e.g.
 * Postgres pipeline mode) call this once per row to rebuild the driver-side parameter
 * state before dispatching. Returns KDBC_OK on success.
 */
int kdbc_apply_batch_row(kdbc_stmt *stmt, int batch_idx);

/*
 * Per-row fallback batch executor. Identical to the loop used by kdbc_execute_batch
 * when vt->execute_batch is NULL. Exposed for drivers that do install a vt->execute_batch
 * but want to delegate to the fallback at runtime when their fast path is unavailable
 * (e.g. libpq lacking pipeline mode on older versions). Frees the batch storage on
 * both success and failure paths exactly like the core.
 */
int kdbc_execute_batch_per_row(kdbc_stmt *stmt);

/*
 * Frees stmt->batches and resets the batch counters. Called automatically by
 * kdbc_execute_batch and kdbc_stmt_reset; drivers that implement vt->execute_batch
 * must call this themselves on every exit path.
 */
void free_batches(kdbc_stmt *stmt);

/*
 * Flushes a deferred BEGIN if the connection has one pending. No-op if the
 * driver did not opt into deferred-BEGIN (vt->begin_tx == NULL) or if no
 * BEGIN is pending. Drivers that opt in must call this at the start of every
 * execute path that touches the server, except when they choose to bundle the
 * BEGIN with the user statement themselves (e.g. PG pipeline mode). On
 * success the flag is cleared; on failure the flag is left set so the caller
 * propagates the error and the next execute can retry.
 */
int kdbc_flush_pending_begin(kdbc_conn *conn);

/* Driver registration */
void kdbc_register_driver(kdbc_driver id, const kdbc_driver_vtable *vt);

/* Initialize all built-in drivers. Called automatically on first use,
 * but can be called explicitly if needed. Safe to call multiple times. */
void kdbc_init(void);

/* Each driver declares its registration function */
void kdbc_register_sqlite(void);
void kdbc_register_postgres(void);
void kdbc_register_mariadb(void);
void kdbc_register_oracle(void);
void kdbc_register_mssql(void);

/* Ensure result string buffer can hold at least `needed` bytes */
static inline int kdbc_ensure_strbuf(kdbc_result *rs, size_t needed) {
    if (rs->str_buf_cap >= needed) return 0;
    size_t cap = needed < 256 ? 256 : needed;
    char *p = (char *)realloc(rs->str_buf, cap);
    if (!p) return KDBC_ERROR;
    rs->str_buf = p;
    rs->str_buf_cap = cap;
    return 0;
}

#endif /* KDBC_INTERNAL_H */
