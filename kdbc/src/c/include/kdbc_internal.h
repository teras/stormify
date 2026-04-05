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
#include <stdlib.h>
#include <stdio.h>
#include <stdarg.h>

/* ========================================================================
 * Error buffer
 * ======================================================================== */

#define KDBC_ERR_SIZE 1024

/* Thread-local global error buffer for connection-less errors */
static _Thread_local char g_error[KDBC_ERR_SIZE] = "";

static inline void kdbc_set_global_error(const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(g_error, KDBC_ERR_SIZE, fmt, ap);
    va_end(ap);
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
    char                 error[KDBC_ERR_SIZE];
    char                 product_name[128];
    char                 product_version[256];
};

/* Parameter storage for prepared statements */
typedef struct {
    kdbc_type type;
    union {
        int64_t  i64;
        double   dbl;
        struct { const char *ptr; size_t len; } str;
        struct { const void *ptr; size_t len; } blob;
    } val;
    /* For string/blob copies that we own */
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
 * ======================================================================== */

#define CONN_ERR(conn, fmt, ...) \
    snprintf((conn)->error, KDBC_ERR_SIZE, fmt, ##__VA_ARGS__)

#define STMT_ERR(stmt, fmt, ...) \
    snprintf((stmt)->error, KDBC_ERR_SIZE, fmt, ##__VA_ARGS__)

#define RS_ERR(rs, fmt, ...) \
    snprintf((rs)->error, KDBC_ERR_SIZE, fmt, ##__VA_ARGS__)

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
