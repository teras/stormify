/*
 * KDBC Native - FreeTDS driver (MS SQL Server) using ct-lib
 *
 * Uses dlopen/dlsym to load libct (FreeTDS Client Library) at runtime.
 * ct-lib provides proper prepared statements via ct_dynamic(),
 * typed parameter binding via ct_param(), and typed result fetching
 * via ct_bind() + ct_fetch().
 *
 * API hierarchy: CS_CONTEXT → CS_CONNECTION → CS_COMMAND
 *
 * SPDX-License-Identifier: Apache-2.0
 */
#include "../include/kdbc_internal.h"
#include "../include/kdbc_dl.h"
#include <ctpublic.h>

/* Note: CS_NULLTERM (-9) is intentionally NOT used in this driver.
 * FreeTDS mishandles it in some ct_* functions, causing unsigned overflow.
 * All string lengths are passed explicitly via strlen(). */

/* ========================================================================
 * Function pointer types
 * ======================================================================== */

typedef CS_RETCODE (*fn_cs_ctx_alloc)(CS_INT, CS_CONTEXT **);
typedef CS_RETCODE (*fn_ct_init)(CS_CONTEXT *, CS_INT);
typedef CS_RETCODE (*fn_ct_con_alloc)(CS_CONTEXT *, CS_CONNECTION **);
typedef CS_RETCODE (*fn_ct_con_props)(CS_CONNECTION *, CS_INT, CS_INT, void *, CS_INT, CS_INT *);
typedef CS_RETCODE (*fn_ct_connect)(CS_CONNECTION *, CS_CHAR *, CS_INT);
typedef CS_RETCODE (*fn_ct_close)(CS_CONNECTION *, CS_INT);
typedef CS_RETCODE (*fn_ct_con_drop)(CS_CONNECTION *);
typedef CS_RETCODE (*fn_ct_cmd_alloc)(CS_CONNECTION *, CS_COMMAND **);
typedef CS_RETCODE (*fn_ct_cmd_drop)(CS_COMMAND *);
typedef CS_RETCODE (*fn_ct_command)(CS_COMMAND *, CS_INT, const CS_CHAR *, CS_INT, CS_INT);
typedef CS_RETCODE (*fn_ct_send)(CS_COMMAND *);
typedef CS_RETCODE (*fn_ct_results)(CS_COMMAND *, CS_INT *);
typedef CS_RETCODE (*fn_ct_res_info)(CS_COMMAND *, CS_INT, void *, CS_INT, CS_INT *);
typedef CS_RETCODE (*fn_ct_describe)(CS_COMMAND *, CS_INT, CS_DATAFMT *);
typedef CS_RETCODE (*fn_ct_bind)(CS_COMMAND *, CS_INT, CS_DATAFMT *, void *, CS_INT *, CS_SMALLINT *);
typedef CS_RETCODE (*fn_ct_fetch)(CS_COMMAND *, CS_INT, CS_INT, CS_INT, CS_INT *);
typedef CS_RETCODE (*fn_ct_cancel)(CS_COMMAND *, CS_CONNECTION *, CS_INT);
typedef CS_RETCODE (*fn_ct_dynamic)(CS_COMMAND *, CS_INT, CS_CHAR *, CS_INT, CS_CHAR *, CS_INT);
typedef CS_RETCODE (*fn_ct_param)(CS_COMMAND *, CS_DATAFMT *, void *, CS_INT, CS_SMALLINT);
typedef CS_RETCODE (*fn_ct_exit)(CS_CONTEXT *, CS_INT);
typedef CS_RETCODE (*fn_cs_ctx_drop)(CS_CONTEXT *);

/* ========================================================================
 * Loaded function pointers
 * ======================================================================== */

static kdbc_lib_handle lib_handle = NULL;
static CS_CONTEXT *g_ctx = NULL;

static fn_cs_ctx_alloc   p_cs_ctx_alloc;
static fn_ct_init        p_ct_init;
static fn_ct_con_alloc   p_ct_con_alloc;
static fn_ct_con_props   p_ct_con_props;
static fn_ct_connect     p_ct_connect;
static fn_ct_close       p_ct_close;
static fn_ct_con_drop    p_ct_con_drop;
static fn_ct_cmd_alloc   p_ct_cmd_alloc;
static fn_ct_cmd_drop    p_ct_cmd_drop;
static fn_ct_command     p_ct_command;
static fn_ct_send        p_ct_send;
static fn_ct_results     p_ct_results;
static fn_ct_res_info    p_ct_res_info;
static fn_ct_describe    p_ct_describe;
static fn_ct_bind        p_ct_bind;
static fn_ct_fetch       p_ct_fetch;
static fn_ct_cancel      p_ct_cancel;
static fn_ct_dynamic     p_ct_dynamic;
static fn_ct_param       p_ct_param;
static fn_ct_exit        p_ct_exit;
static fn_cs_ctx_drop    p_cs_ctx_drop;

/* ========================================================================
 * Library loading
 * ======================================================================== */

#define CT_LOAD(var, name) do { \
    var = kdbc_dl_sym(lib_handle, name); \
    if (!var) { kdbc_dl_close(lib_handle); lib_handle = NULL; return 0; } \
} while (0)

static int tds_load(void) {
    if (lib_handle) return 1;

    lib_handle = kdbc_dl_open(KDBC_LIBNAME("ct", "4"), RTLD_LAZY);
    if (!lib_handle) lib_handle = kdbc_dl_open(KDBC_LIBNAME_NOVER("ct"), RTLD_LAZY);
    if (!lib_handle) return 0;

    CT_LOAD(p_cs_ctx_alloc,  "cs_ctx_alloc");
    CT_LOAD(p_ct_init,       "ct_init");
    CT_LOAD(p_ct_con_alloc,  "ct_con_alloc");
    CT_LOAD(p_ct_con_props,  "ct_con_props");
    CT_LOAD(p_ct_connect,    "ct_connect");
    CT_LOAD(p_ct_close,      "ct_close");
    CT_LOAD(p_ct_con_drop,   "ct_con_drop");
    CT_LOAD(p_ct_cmd_alloc,  "ct_cmd_alloc");
    CT_LOAD(p_ct_cmd_drop,   "ct_cmd_drop");
    CT_LOAD(p_ct_command,    "ct_command");
    CT_LOAD(p_ct_send,       "ct_send");
    CT_LOAD(p_ct_results,    "ct_results");
    CT_LOAD(p_ct_res_info,   "ct_res_info");
    CT_LOAD(p_ct_describe,   "ct_describe");
    CT_LOAD(p_ct_bind,       "ct_bind");
    CT_LOAD(p_ct_fetch,      "ct_fetch");
    CT_LOAD(p_ct_cancel,     "ct_cancel");
    CT_LOAD(p_ct_dynamic,    "ct_dynamic");
    CT_LOAD(p_ct_param,      "ct_param");
    CT_LOAD(p_ct_exit,       "ct_exit");
    CT_LOAD(p_cs_ctx_drop,   "cs_ctx_drop");

    /* Initialize global context */
    if (p_cs_ctx_alloc(CS_VERSION_125, &g_ctx) != CS_SUCCEED) {
        kdbc_dl_close(lib_handle);
        lib_handle = NULL;
        return 0;
    }
    if (p_ct_init(g_ctx, CS_VERSION_125) != CS_SUCCEED) {
        p_cs_ctx_drop(g_ctx);
        g_ctx = NULL;
        kdbc_dl_close(lib_handle);
        lib_handle = NULL;
        return 0;
    }

    return 1;
}

static int tds_loaded(void) { return lib_handle != NULL && g_ctx != NULL; }

/* ========================================================================
 * Helper: execute simple SQL via ct_command (for transactions etc.)
 * ======================================================================== */

static int ct_exec_simple(CS_CONNECTION *conn, const char *sql,
                          char *err, size_t err_size) {
    CS_COMMAND *cmd = NULL;
    if (p_ct_cmd_alloc(conn, &cmd) != CS_SUCCEED) {
        snprintf(err, err_size, "FreeTDS: ct_cmd_alloc failed");
        return KDBC_ERROR;
    }

    if (p_ct_command(cmd, CS_LANG_CMD, sql, (CS_INT)strlen(sql), CS_UNUSED) != CS_SUCCEED) {
        snprintf(err, err_size, "FreeTDS: ct_command failed");
        p_ct_cmd_drop(cmd);
        return KDBC_ERROR;
    }

    if (p_ct_send(cmd) != CS_SUCCEED) {
        snprintf(err, err_size, "FreeTDS: ct_send failed");
        p_ct_cmd_drop(cmd);
        return KDBC_ERROR;
    }

    /* Consume all results */
    CS_INT res_type;
    while (p_ct_results(cmd, &res_type) == CS_SUCCEED) {
        if (res_type == CS_ROW_RESULT) {
            /* Consume rows */
            CS_INT rows_read;
            while (p_ct_fetch(cmd, CS_UNUSED, CS_UNUSED, CS_UNUSED, &rows_read) == CS_SUCCEED) {}
        }
    }

    p_ct_cmd_drop(cmd);
    return KDBC_OK;
}

/* ========================================================================
 * Connection
 * ======================================================================== */

typedef struct {
    CS_CONNECTION *conn;
    int            autocommit;
    int            in_transaction;
} tds_conn;

/* Direct SQL execution (DDL, savepoints, admin commands).
 * Uses ct_command instead of ct_dynamic — works for non-preparable statements. */
static int tds_exec_direct(kdbc_conn *conn, const char *sql) {
    tds_conn *tc = (tds_conn *)conn->native;
    return ct_exec_simple(tc->conn, sql, conn->error, KDBC_ERR_SIZE);
}

/* Column data and result set structs — defined early for use in both
 * direct query and prepared statement paths */
typedef struct {
    CS_INT      datatype;
    union {
        CS_INT   i32;
        long long i64;
        double   dbl;
        float    flt;
    } num;
    char        *buf;
    CS_INT       buf_cap;
    CS_INT       datalen;
    CS_SMALLINT  indicator;
} tds_col_data;

typedef struct {
    CS_COMMAND   *cmd;
    int           col_count;
    int           owns_cmd;   /* 1 = direct query, must ct_cmd_drop on close */
    tds_col_data *cols;
    char        **col_names;
    char          conv_buf[64];
} tds_result_set;

/* Direct query execution (SELECT without params).
 * Uses ct_command instead of ct_dynamic — works for all column types. */
static void *tds_query_direct(kdbc_conn *conn, const char *sql, int *out_col_count,
                              char *err, size_t err_size) {
    tds_conn *tc = (tds_conn *)conn->native;
    CS_COMMAND *cmd = NULL;
    if (p_ct_cmd_alloc(tc->conn, &cmd) != CS_SUCCEED) {
        snprintf(err, err_size, "FreeTDS: ct_cmd_alloc failed");
        return NULL;
    }
    if (p_ct_command(cmd, CS_LANG_CMD, sql, (CS_INT)strlen(sql), CS_UNUSED) != CS_SUCCEED) {
        snprintf(err, err_size, "FreeTDS: ct_command failed");
        p_ct_cmd_drop(cmd);
        return NULL;
    }
    if (p_ct_send(cmd) != CS_SUCCEED) {
        snprintf(err, err_size, "FreeTDS: ct_send failed");
        p_ct_cmd_drop(cmd);
        return NULL;
    }

    /* Find ROW_RESULT */
    CS_INT res_type;
    while (p_ct_results(cmd, &res_type) == CS_SUCCEED) {
        if (res_type == CS_ROW_RESULT) break;
    }
    if (res_type != CS_ROW_RESULT) {
        snprintf(err, err_size, "FreeTDS: no result set");
        p_ct_cmd_drop(cmd);
        return NULL;
    }

    CS_INT ncols = 0;
    p_ct_res_info(cmd, CS_NUMDATA, &ncols, sizeof(ncols), NULL);

    tds_result_set *trs = (tds_result_set *)calloc(1, sizeof(tds_result_set));
    if (!trs) { p_ct_cmd_drop(cmd); snprintf(err, err_size, "Out of memory"); return NULL; }

    trs->cmd = cmd;
    trs->col_count = ncols;
    trs->owns_cmd = 1; /* direct query — we own the cmd handle */
    trs->col_names = (char **)calloc(ncols, sizeof(char *));
    trs->cols = (tds_col_data *)calloc(ncols, sizeof(tds_col_data));

    if (!trs->cols || !trs->col_names) {
        free(trs->cols); free(trs->col_names); free(trs);
        p_ct_cmd_drop(cmd);
        snprintf(err, err_size, "Out of memory");
        return NULL;
    }

    /* Describe and bind columns — same as execute_query */
    for (int i = 0; i < ncols; i++) {
        CS_DATAFMT desc;
        memset(&desc, 0, sizeof(desc));
        p_ct_describe(cmd, i + 1, &desc);

        if (desc.namelen > 0) {
            trs->col_names[i] = (char *)malloc(desc.namelen + 1);
            if (trs->col_names[i]) {
                memcpy(trs->col_names[i], desc.name, desc.namelen);
                trs->col_names[i][desc.namelen] = '\0';
            }
        }

        tds_col_data *c = &trs->cols[i];
        c->datatype = desc.datatype;
        CS_DATAFMT bind_fmt;
        memset(&bind_fmt, 0, sizeof(bind_fmt));
        bind_fmt.count = 1;

        switch (desc.datatype) {
            case CS_INT_TYPE: case CS_SMALLINT_TYPE: case CS_TINYINT_TYPE: case CS_BIT_TYPE:
                bind_fmt.datatype = CS_INT_TYPE;
                bind_fmt.maxlength = sizeof(CS_INT);
                p_ct_bind(cmd, i + 1, &bind_fmt, &c->num.i32, &c->datalen, &c->indicator);
                c->datatype = CS_INT_TYPE;
                break;
            case CS_BIGINT_TYPE:
                bind_fmt.datatype = CS_BIGINT_TYPE;
                bind_fmt.maxlength = 8;
                p_ct_bind(cmd, i + 1, &bind_fmt, &c->num.i64, &c->datalen, &c->indicator);
                c->datatype = CS_BIGINT_TYPE;
                break;
            case CS_FLOAT_TYPE:
                bind_fmt.datatype = CS_FLOAT_TYPE;
                bind_fmt.maxlength = sizeof(double);
                p_ct_bind(cmd, i + 1, &bind_fmt, &c->num.dbl, &c->datalen, &c->indicator);
                c->datatype = CS_FLOAT_TYPE;
                break;
            case CS_REAL_TYPE:
                bind_fmt.datatype = CS_REAL_TYPE;
                bind_fmt.maxlength = sizeof(float);
                p_ct_bind(cmd, i + 1, &bind_fmt, &c->num.flt, &c->datalen, &c->indicator);
                c->datatype = CS_REAL_TYPE;
                break;
            default: {
                /* Min 256 bytes: binary types (DATETIME=8, BINARY=N) need
                 * room for string conversion; max 64K for MAX types */
                CS_INT maxlen = desc.maxlength;
                if (maxlen < 256) maxlen = 256;
                if (maxlen > 65536) maxlen = 65536;
                bind_fmt.datatype = CS_CHAR_TYPE;
                c->buf_cap = maxlen + 1;
                c->buf = (char *)malloc(c->buf_cap);
                bind_fmt.maxlength = c->buf_cap;
                bind_fmt.format = CS_FMT_NULLTERM;
                p_ct_bind(cmd, i + 1, &bind_fmt, c->buf, &c->datalen, &c->indicator);
                c->datatype = CS_CHAR_TYPE;
                break;
            }
        }
    }

    *out_col_count = ncols;
    return trs;
}

static void *tds_connect(const char *url, const char *user, const char *password,
                         char *err, size_t err_size) {
    CS_CONNECTION *conn = NULL;
    if (p_ct_con_alloc(g_ctx, &conn) != CS_SUCCEED) {
        snprintf(err, err_size, "FreeTDS: ct_con_alloc failed");
        return NULL;
    }

    /* Set connection properties (use explicit strlen, not CS_NULLTERM
     * which some FreeTDS versions don't handle correctly) */
    if (user) {
        p_ct_con_props(conn, CS_SET, CS_USERNAME, (void *)user,
                       (CS_INT)strlen(user), NULL);
    }
    if (password) {
        p_ct_con_props(conn, CS_SET, CS_PASSWORD, (void *)password,
                       (CS_INT)strlen(password), NULL);
    }
    p_ct_con_props(conn, CS_SET, CS_APPNAME, (void *)"KDBC",
                   4, NULL);

    /* Parse url: host:port/database */
    char server[256];
    char db[256] = "";

    const char *slash = strchr(url, '/');
    if (slash) {
        size_t hp_len = slash - url;
        if (hp_len >= sizeof(server)) hp_len = sizeof(server) - 1;
        memcpy(server, url, hp_len);
        server[hp_len] = '\0';
        snprintf(db, sizeof(db), "%s", slash + 1);
    } else {
        snprintf(server, sizeof(server), "%s", url);
    }

    /* Connect */
    if (p_ct_connect(conn, server, (CS_INT)strlen(server)) != CS_SUCCEED) {
        snprintf(err, err_size, "FreeTDS: failed to connect to %s", server);
        p_ct_con_drop(conn);
        return NULL;
    }

    tds_conn *tc = (tds_conn *)calloc(1, sizeof(tds_conn));
    if (!tc) {
        p_ct_close(conn, CS_UNUSED);
        p_ct_con_drop(conn);
        snprintf(err, err_size, "Out of memory");
        return NULL;
    }

    tc->conn = conn;
    tc->autocommit = 1;
    tc->in_transaction = 0;

    /* USE database if specified */
    if (db[0]) {
        char use_sql[300];
        snprintf(use_sql, sizeof(use_sql), "USE %s", db);
        if (ct_exec_simple(conn, use_sql, err, err_size) != KDBC_OK) {
            p_ct_close(conn, CS_UNUSED);
            p_ct_con_drop(conn);
            free(tc);
            return NULL;
        }
    }

    return tc;
}

static void tds_close(void *native) {
    tds_conn *tc = (tds_conn *)native;
    if (!tc) return;
    p_ct_close(tc->conn, CS_UNUSED);
    p_ct_con_drop(tc->conn);
    free(tc);
}

/* ========================================================================
 * Transactions
 * ======================================================================== */

static int tds_set_autocommit(kdbc_conn *conn, int enabled) {
    tds_conn *tc = (tds_conn *)conn->native;
    if (enabled && !tc->autocommit) {
        if (tc->in_transaction) {
            int rc = ct_exec_simple(tc->conn, "COMMIT TRANSACTION",
                                    conn->error, KDBC_ERR_SIZE);
            if (rc != KDBC_OK) return rc;
            tc->in_transaction = 0;
        }
    } else if (!enabled && tc->autocommit) {
        int rc = ct_exec_simple(tc->conn, "BEGIN TRANSACTION",
                                conn->error, KDBC_ERR_SIZE);
        if (rc != KDBC_OK) return rc;
        tc->in_transaction = 1;
    }
    tc->autocommit = enabled;
    return KDBC_OK;
}

static int tds_commit(kdbc_conn *conn) {
    tds_conn *tc = (tds_conn *)conn->native;
    int rc = ct_exec_simple(tc->conn, "COMMIT TRANSACTION",
                            conn->error, KDBC_ERR_SIZE);
    if (rc != KDBC_OK) return rc;
    tc->in_transaction = 0;
    if (!tc->autocommit) {
        rc = ct_exec_simple(tc->conn, "BEGIN TRANSACTION",
                            conn->error, KDBC_ERR_SIZE);
        if (rc == KDBC_OK) tc->in_transaction = 1;
    }
    return rc;
}

static int tds_rollback(kdbc_conn *conn) {
    tds_conn *tc = (tds_conn *)conn->native;
    int rc = ct_exec_simple(tc->conn, "ROLLBACK TRANSACTION",
                            conn->error, KDBC_ERR_SIZE);
    if (rc != KDBC_OK) return rc;
    tc->in_transaction = 0;
    if (!tc->autocommit) {
        rc = ct_exec_simple(tc->conn, "BEGIN TRANSACTION",
                            conn->error, KDBC_ERR_SIZE);
        if (rc == KDBC_OK) tc->in_transaction = 1;
    }
    return rc;
}

/* ========================================================================
 * Metadata
 * ======================================================================== */

static void tds_get_product_name(void *native, char *buf, size_t sz) {
    (void)native;
    snprintf(buf, sz, "Microsoft SQL Server");
}

static void tds_get_product_version(void *native, char *buf, size_t sz) {
    /* Query @@VERSION via simple exec */
    tds_conn *tc = (tds_conn *)native;
    CS_COMMAND *cmd = NULL;
    if (p_ct_cmd_alloc(tc->conn, &cmd) != CS_SUCCEED) {
        snprintf(buf, sz, "unknown");
        return;
    }
    if (p_ct_command(cmd, CS_LANG_CMD, "SELECT @@VERSION", 17, CS_UNUSED) != CS_SUCCEED ||
        p_ct_send(cmd) != CS_SUCCEED) {
        p_ct_cmd_drop(cmd);
        snprintf(buf, sz, "unknown");
        return;
    }

    CS_INT res_type;
    int got_version = 0;
    while (p_ct_results(cmd, &res_type) == CS_SUCCEED) {
        if (res_type == CS_ROW_RESULT && !got_version) {
            CS_DATAFMT fmt;
            memset(&fmt, 0, sizeof(fmt));
            fmt.datatype = CS_CHAR_TYPE;
            fmt.maxlength = (CS_INT)(sz - 1);
            fmt.format = CS_FMT_NULLTERM;
            CS_INT datalen;
            CS_SMALLINT ind;
            p_ct_bind(cmd, 1, &fmt, buf, &datalen, &ind);
            CS_INT rows_read;
            if (p_ct_fetch(cmd, CS_UNUSED, CS_UNUSED, CS_UNUSED, &rows_read) == CS_SUCCEED) {
                got_version = 1;
            }
            /* Consume remaining rows */
            while (p_ct_fetch(cmd, CS_UNUSED, CS_UNUSED, CS_UNUSED, &rows_read) == CS_SUCCEED) {}
        }
    }

    p_ct_cmd_drop(cmd);
    if (!got_version) snprintf(buf, sz, "unknown");
}

/**
 * Parse SQL Server version from @@VERSION string.
 * Format: "Microsoft SQL Server 2022 ... - 16.0.1000.6 ..."
 * We look for the "X.Y" pattern after the dash.
 */
static void tds_parse_version(void *native, int *major, int *minor) {
    *major = 0;
    *minor = 0;
    char buf[512];
    tds_get_product_version(native, buf, sizeof(buf));

    /* Find " - " followed by version number */
    const char *dash = strstr(buf, " - ");
    if (dash) {
        dash += 3; /* skip " - " */
        if (sscanf(dash, "%d.%d", major, minor) >= 1)
            return;
    }
    /* Fallback: look for any "N.N" pattern */
    for (const char *p = buf; *p; p++) {
        if (p[0] >= '0' && p[0] <= '9') {
            if (sscanf(p, "%d.%d", major, minor) >= 1)
                return;
        }
    }
}

static int tds_get_major_version(void *native) {
    int maj = 0, min = 0;
    tds_parse_version(native, &maj, &min);
    return maj;
}

static int tds_get_minor_version(void *native) {
    int maj = 0, min = 0;
    tds_parse_version(native, &maj, &min);
    return min;
}

/* ========================================================================
 * Statement preparation using ct_dynamic
 *
 * ct_dynamic(CS_PREPARE) prepares a statement with ? placeholders.
 * ct_param() binds typed parameters before execution.
 * ct_dynamic(CS_EXECUTE) executes the prepared statement.
 * ======================================================================== */

typedef struct {
    tds_conn     *tc;
    CS_COMMAND   *prep_cmd;    /* command handle used for CS_PREPARE */
    CS_COMMAND   *exec_cmd;    /* command handle used for CS_EXECUTE (separate per FreeTDS requirement) */
    char          stmt_id[32]; /* unique prepared statement name */
    int           param_count;
    /* Per-parameter storage */
    struct {
        CS_INT      datatype;
        union {
            CS_INT      i32;
            long long   i64;
            double      dbl;
        } num;
        char       *str;
        CS_INT      str_len;
        CS_SMALLINT indicator; /* -1 for NULL */
    } *params;
} tds_stmt_data;

static _Atomic long tds_stmt_counter = 0;

static void *tds_prepare_fn(kdbc_conn *conn, const char *native_sql,
                            const char **ret_cols, int n_ret_cols,
                            char *err, size_t err_size) {
    tds_conn *tc = (tds_conn *)conn->native;

    tds_stmt_data *sd = (tds_stmt_data *)calloc(1, sizeof(tds_stmt_data));
    if (!sd) { snprintf(err, err_size, "Out of memory"); return NULL; }

    /* For generated keys, inject OUTPUT INSERTED.col into the INSERT SQL.
     * SQL Server: INSERT INTO t (a,b) OUTPUT INSERTED.id VALUES (?,?)
     * The OUTPUT clause goes between column list and VALUES. */
    char *final_sql = NULL;
    if (ret_cols && n_ret_cols > 0) {
        /* Find "VALUES" keyword (case-insensitive) to insert OUTPUT before it */
        const char *values_pos = NULL;
        for (const char *p = native_sql; *p; p++) {
            if ((p == native_sql || !((p[-1] >= 'a' && p[-1] <= 'z') || (p[-1] >= 'A' && p[-1] <= 'Z'))) &&
                (strncasecmp(p, "VALUES", 6) == 0) &&
                (p[6] == ' ' || p[6] == '(' || p[6] == '\0')) {
                values_pos = p;
                break;
            }
        }
        if (values_pos) {
            size_t prefix_len = values_pos - native_sql;
            size_t len = strlen(native_sql) + 32;
            for (int i = 0; i < n_ret_cols; i++)
                len += strlen(ret_cols[i]) + 12; /* "INSERTED." + ", " */
            final_sql = (char *)malloc(len);
            if (final_sql) {
                memcpy(final_sql, native_sql, prefix_len);
                char *dst = final_sql + prefix_len;
                dst += sprintf(dst, "OUTPUT ");
                for (int i = 0; i < n_ret_cols; i++) {
                    if (i > 0) dst += sprintf(dst, ", ");
                    dst += sprintf(dst, "INSERTED.%s", ret_cols[i]);
                }
                sprintf(dst, " %s", values_pos);
            }
        }
    }

    const char *sql_to_prepare = final_sql ? final_sql : native_sql;

    sd->tc = tc;
    snprintf(sd->stmt_id, sizeof(sd->stmt_id), "_kdbc_%ld",
             tds_stmt_counter++);

    /* Allocate TWO command handles: one for prepare, one for execute.
     * FreeTDS ct_dynamic requires separate handles per the Sybase ct-lib spec. */
    if (p_ct_cmd_alloc(tc->conn, &sd->prep_cmd) != CS_SUCCEED) {
        snprintf(err, err_size, "FreeTDS: ct_cmd_alloc (prepare) failed");
        free(sd);
        return NULL;
    }
    if (p_ct_cmd_alloc(tc->conn, &sd->exec_cmd) != CS_SUCCEED) {
        snprintf(err, err_size, "FreeTDS: ct_cmd_alloc (execute) failed");
        p_ct_cmd_drop(sd->prep_cmd);
        free(sd);
        return NULL;
    }

    /* Prepare the statement using prep_cmd */
    if (p_ct_dynamic(sd->prep_cmd, CS_PREPARE, sd->stmt_id, (CS_INT)strlen(sd->stmt_id),
                     (CS_CHAR *)sql_to_prepare, (CS_INT)strlen(sql_to_prepare)) != CS_SUCCEED) {
        snprintf(err, err_size, "FreeTDS: ct_dynamic CS_PREPARE failed");
        free(final_sql);
        p_ct_cmd_drop(sd->exec_cmd);
        p_ct_cmd_drop(sd->prep_cmd);
        free(sd);
        return NULL;
    }

    if (p_ct_send(sd->prep_cmd) != CS_SUCCEED) {
        snprintf(err, err_size, "FreeTDS: ct_send (prepare) failed");
        p_ct_cmd_drop(sd->exec_cmd);
        p_ct_cmd_drop(sd->prep_cmd);
        free(sd);
        return NULL;
    }

    /* Consume prepare results and check for failure */
    CS_INT res_type;
    int prep_failed = 0;
    while (p_ct_results(sd->prep_cmd, &res_type) == CS_SUCCEED) {
        if (res_type == CS_CMD_FAIL) prep_failed = 1;
    }
    free(final_sql);
    if (prep_failed) {
        snprintf(err, err_size, "FreeTDS: server rejected prepared statement "
                 "(possible type mismatch — binary columns may need CAST)");
        p_ct_cmd_drop(sd->exec_cmd);
        p_ct_cmd_drop(sd->prep_cmd);
        free(sd);
        return NULL;
    }

    sd->param_count = kdbc_count_params(native_sql);
    if (sd->param_count > 0) {
        sd->params = calloc(sd->param_count, sizeof(*sd->params));
        if (!sd->params) {
            p_ct_cmd_drop(sd->exec_cmd);
            p_ct_cmd_drop(sd->prep_cmd);
            free(sd);
            snprintf(err, err_size, "Out of memory");
            return NULL;
        }
        /* Default all to NULL */
        for (int i = 0; i < sd->param_count; i++)
            sd->params[i].indicator = -1;
    }

    return sd;
}

static void tds_stmt_close_fn(void *native_stmt, void *native_conn) {
    (void)native_conn;
    tds_stmt_data *sd = (tds_stmt_data *)native_stmt;
    if (!sd) return;

    /* Deallocate prepared statement using prep_cmd */
    if (sd->prep_cmd) {
        p_ct_dynamic(sd->prep_cmd, CS_DEALLOC, sd->stmt_id,
                     (CS_INT)strlen(sd->stmt_id), NULL, CS_UNUSED);
        p_ct_send(sd->prep_cmd);
        CS_INT res_type;
        while (p_ct_results(sd->prep_cmd, &res_type) == CS_SUCCEED) {}
        p_ct_cmd_drop(sd->prep_cmd);
    }
    if (sd->exec_cmd) {
        p_ct_cmd_drop(sd->exec_cmd);
    }

    if (sd->params) {
        for (int i = 0; i < sd->param_count; i++)
            free(sd->params[i].str);
        free(sd->params);
    }
    free(sd);
}

/* ========================================================================
 * Parameter binding (typed via ct_param)
 * ======================================================================== */

static int tds_bind_null(kdbc_stmt *stmt, int idx) {
    tds_stmt_data *sd = (tds_stmt_data *)stmt->native;
    int i = idx - 1;
    sd->params[i].indicator = -1;
    sd->params[i].datatype = CS_CHAR_TYPE;
    return KDBC_OK;
}

static int tds_bind_int(kdbc_stmt *stmt, int idx, int val) {
    tds_stmt_data *sd = (tds_stmt_data *)stmt->native;
    int i = idx - 1;
    sd->params[i].indicator = 0;
    sd->params[i].datatype = CS_INT_TYPE;
    sd->params[i].num.i32 = val;
    return KDBC_OK;
}

static int tds_bind_long(kdbc_stmt *stmt, int idx, int64_t val) {
    tds_stmt_data *sd = (tds_stmt_data *)stmt->native;
    int i = idx - 1;
    sd->params[i].indicator = 0;
    sd->params[i].datatype = CS_BIGINT_TYPE;
    sd->params[i].num.i64 = val;
    return KDBC_OK;
}

static int tds_bind_double(kdbc_stmt *stmt, int idx, double val) {
    tds_stmt_data *sd = (tds_stmt_data *)stmt->native;
    int i = idx - 1;
    sd->params[i].indicator = 0;
    sd->params[i].datatype = CS_FLOAT_TYPE;
    sd->params[i].num.dbl = val;
    return KDBC_OK;
}

static int tds_bind_string(kdbc_stmt *stmt, int idx, const char *val) {
    tds_stmt_data *sd = (tds_stmt_data *)stmt->native;
    int i = idx - 1;
    sd->params[i].indicator = 0;
    sd->params[i].datatype = CS_CHAR_TYPE;
    free(sd->params[i].str);
    sd->params[i].str_len = (CS_INT)strlen(val);
    sd->params[i].str = strdup(val);
    if (!sd->params[i].str) { STMT_ERR(stmt, "Out of memory"); return KDBC_ERROR; }
    return KDBC_OK;
}

/* Datetime binding: ODBC canonical string format.
 * CS_DATETIME_TYPE via ct_param has precision issues in FreeTDS,
 * but string "YYYY-MM-DD HH:MM:SS.fff" works reliably. */
static int tds_bind_timestamp(kdbc_stmt *stmt, int idx,
                              int year, int month, int day,
                              int hour, int minute, int second, int usec) {
    char buf[32];
    int millis = usec / 1000;
    snprintf(buf, sizeof(buf), "%04d-%02d-%02d %02d:%02d:%02d.%03d",
             year, month, day, hour, minute, second, millis);
    return tds_bind_string(stmt, idx, buf);
}

static int tds_bind_date(kdbc_stmt *stmt, int idx, int year, int month, int day) {
    char buf[32];
    snprintf(buf, sizeof(buf), "%04d-%02d-%02d", year, month, day);
    return tds_bind_string(stmt, idx, buf);
}

static int tds_bind_time(kdbc_stmt *stmt, int idx,
                         int hour, int minute, int second, int usec) {
    char buf[32];
    int millis = usec / 1000;
    snprintf(buf, sizeof(buf), "%02d:%02d:%02d.%03d", hour, minute, second, millis);
    return tds_bind_string(stmt, idx, buf);
}

static int tds_bind_blob(kdbc_stmt *stmt, int idx, const void *data, size_t len) {
    /* FreeTDS ct_dynamic doesn't support binary params reliably.
     * Send as hex string (0xDEAD...) which SQL Server auto-converts to VARBINARY. */
    tds_stmt_data *sd = (tds_stmt_data *)stmt->native;
    int i = idx - 1;
    sd->params[i].indicator = 0;
    sd->params[i].datatype = CS_CHAR_TYPE;
    free(sd->params[i].str);
    sd->params[i].str_len = (CS_INT)(2 + len * 2);
    sd->params[i].str = (char *)malloc(sd->params[i].str_len + 1);
    if (!sd->params[i].str) { STMT_ERR(stmt, "Out of memory"); return KDBC_ERROR; }
    sd->params[i].str[0] = '0'; sd->params[i].str[1] = 'x';
    const unsigned char *bytes = (const unsigned char *)data;
    for (size_t j = 0; j < len; j++)
        sprintf(sd->params[i].str + 2 + j * 2, "%02x", bytes[j]);
    sd->params[i].str[sd->params[i].str_len] = '\0';
    return KDBC_OK;
}

/* ========================================================================
 * Send parameters via ct_param and execute via ct_dynamic(CS_EXECUTE)
 * ======================================================================== */

static int tds_send_execute(tds_stmt_data *sd, char *err, size_t err_size) {
    /* Issue CS_EXECUTE on the exec_cmd handle (separate from prep_cmd) */
    if (p_ct_dynamic(sd->exec_cmd, CS_EXECUTE, sd->stmt_id, (CS_INT)strlen(sd->stmt_id),
                     NULL, CS_UNUSED) != CS_SUCCEED) {
        snprintf(err, err_size, "FreeTDS: ct_dynamic CS_EXECUTE failed");
        return KDBC_ERROR;
    }

    /* Bind parameters via ct_param */
    for (int i = 0; i < sd->param_count; i++) {
        CS_DATAFMT fmt;
        memset(&fmt, 0, sizeof(fmt));
        snprintf(fmt.name, sizeof(fmt.name), "@p%d", i + 1);
        fmt.namelen = (CS_INT)strlen(fmt.name);
        fmt.status = CS_INPUTVALUE;
        fmt.datatype = sd->params[i].datatype;

        void *data = NULL;
        CS_INT datalen = 0;
        CS_SMALLINT indicator = sd->params[i].indicator;

        if (indicator != -1) {
            switch (sd->params[i].datatype) {
                case CS_INT_TYPE:
                    fmt.maxlength = sizeof(CS_INT);
                    data = &sd->params[i].num.i32;
                    datalen = sizeof(CS_INT);
                    break;
                case CS_BIGINT_TYPE:
                    fmt.maxlength = 8;
                    data = &sd->params[i].num.i64;
                    datalen = 8;
                    break;
                case CS_FLOAT_TYPE:
                    fmt.maxlength = sizeof(double);
                    data = &sd->params[i].num.dbl;
                    datalen = sizeof(double);
                    break;
                case CS_CHAR_TYPE:
                    fmt.maxlength = sd->params[i].str_len;
                    data = sd->params[i].str;
                    datalen = sd->params[i].str_len;
                    break;
                case CS_IMAGE_TYPE:
                    fmt.maxlength = sd->params[i].str_len;
                    data = sd->params[i].str;
                    datalen = sd->params[i].str_len;
                    break;
                default:
                    snprintf(err, err_size, "FreeTDS: unknown param type %d",
                             sd->params[i].datatype);
                    return KDBC_ERROR;
            }
        }

        if (p_ct_param(sd->exec_cmd, &fmt, data, datalen, indicator) != CS_SUCCEED) {
            snprintf(err, err_size, "FreeTDS: ct_param failed for param %d", i + 1);
            return KDBC_ERROR;
        }
    }

    /* Send */
    if (p_ct_send(sd->exec_cmd) != CS_SUCCEED) {
        snprintf(err, err_size, "FreeTDS: ct_send (execute) failed");
        return KDBC_ERROR;
    }

    return KDBC_OK;
}

/* ========================================================================
 * Execution
 * ======================================================================== */

static int tds_execute_update(kdbc_stmt *stmt) {
    tds_stmt_data *sd = (tds_stmt_data *)stmt->native;

    if (tds_send_execute(sd, stmt->error, KDBC_ERR_SIZE) != KDBC_OK)
        return KDBC_ERROR;

    int affected = 0;
    CS_INT res_type;
    while (p_ct_results(sd->exec_cmd, &res_type) == CS_SUCCEED) {
        switch (res_type) {
            case CS_CMD_DONE: {
                /* Row count is ONLY valid after CS_CMD_DONE */
                CS_INT row_count = -1;
                p_ct_res_info(sd->exec_cmd, CS_ROW_COUNT, &row_count,
                              sizeof(row_count), NULL);
                if (row_count > 0) affected += row_count;
                break;
            }
            case CS_CMD_SUCCEED:
                /* Non-data command succeeded, no row count here */
                break;
            case CS_ROW_RESULT: {
                /* OUTPUT INSERTED result — extract generated key */
                if (stmt->ret_col_count > 0 && !stmt->has_generated_key) {
                    CS_DATAFMT fmt;
                    memset(&fmt, 0, sizeof(fmt));
                    fmt.datatype = CS_CHAR_TYPE;
                    fmt.maxlength = 64;
                    fmt.format = CS_FMT_NULLTERM;
                    char buf[64] = "";
                    CS_INT datalen;
                    CS_SMALLINT ind;
                    p_ct_bind(sd->exec_cmd, 1, &fmt, buf, &datalen, &ind);
                    CS_INT rows_read;
                    if (p_ct_fetch(sd->exec_cmd, CS_UNUSED, CS_UNUSED, CS_UNUSED,
                                   &rows_read) == CS_SUCCEED) {
                        if (ind != -1 && buf[0] != '\0') {
                            long long k = 0;
                            sscanf(buf, "%lld", &k);
                            stmt->generated_key = k;
                            stmt->has_generated_key = 1;
                        }
                    }
                }
                /* Consume remaining rows */
                CS_INT rows_read;
                while (p_ct_fetch(sd->exec_cmd, CS_UNUSED, CS_UNUSED, CS_UNUSED,
                                  &rows_read) == CS_SUCCEED) {}
                break;
            }
            case CS_STATUS_RESULT: {
                CS_INT rows_read;
                while (p_ct_fetch(sd->exec_cmd, CS_UNUSED, CS_UNUSED, CS_UNUSED,
                                  &rows_read) == CS_SUCCEED) {}
                break;
            }
            default:
                break;
        }
    }

    /* Generated keys now handled via OUTPUT INSERTED in the SQL itself */

    return affected;
}

/* ========================================================================
 * Result set structures
 * ======================================================================== */

/* (tds_col_data and tds_result_set defined earlier in the file) */

static void *tds_execute_query(kdbc_stmt *stmt, int *out_col_count,
                               char *err, size_t err_size) {
    tds_stmt_data *sd = (tds_stmt_data *)stmt->native;

    if (tds_send_execute(sd, err, err_size) != KDBC_OK)
        return NULL;

    /* Find the ROW_RESULT */
    CS_INT res_type;
    while (p_ct_results(sd->exec_cmd, &res_type) == CS_SUCCEED) {
        if (res_type == CS_ROW_RESULT)
            break;
    }

    if (res_type != CS_ROW_RESULT) {
        snprintf(err, err_size, "FreeTDS: no result set");
        return NULL;
    }

    /* Get column count */
    CS_INT ncols = 0;
    p_ct_res_info(sd->exec_cmd, CS_NUMDATA, &ncols, sizeof(ncols), NULL);

    tds_result_set *trs = (tds_result_set *)calloc(1, sizeof(tds_result_set));
    if (!trs) { snprintf(err, err_size, "Out of memory"); return NULL; }

    trs->cmd = sd->exec_cmd;
    trs->col_count = ncols;
    trs->cols = (tds_col_data *)calloc(ncols, sizeof(tds_col_data));
    trs->col_names = (char **)calloc(ncols, sizeof(char *));

    if (!trs->cols || !trs->col_names) {
        free(trs->cols);
        free(trs->col_names);
        free(trs);
        snprintf(err, err_size, "Out of memory");
        return NULL;
    }

    /* Describe and bind each column */
    for (int i = 0; i < ncols; i++) {
        CS_DATAFMT desc;
        memset(&desc, 0, sizeof(desc));
        p_ct_describe(trs->cmd, i + 1, &desc);

        /* Cache column name */
        if (desc.namelen > 0) {
            trs->col_names[i] = (char *)malloc(desc.namelen + 1);
            if (trs->col_names[i]) {
                memcpy(trs->col_names[i], desc.name, desc.namelen);
                trs->col_names[i][desc.namelen] = '\0';
            }
        }

        tds_col_data *c = &trs->cols[i];
        c->datatype = desc.datatype;

        /* Bind based on type */
        CS_DATAFMT bind_fmt;
        memset(&bind_fmt, 0, sizeof(bind_fmt));
        bind_fmt.count = 1;

        switch (desc.datatype) {
            case CS_INT_TYPE:
            case CS_SMALLINT_TYPE:
            case CS_TINYINT_TYPE:
            case CS_BIT_TYPE:
                bind_fmt.datatype = CS_INT_TYPE;
                bind_fmt.maxlength = sizeof(CS_INT);
                p_ct_bind(trs->cmd, i + 1, &bind_fmt, &c->num.i32,
                          &c->datalen, &c->indicator);
                c->datatype = CS_INT_TYPE;
                break;

            case CS_BIGINT_TYPE:
                bind_fmt.datatype = CS_BIGINT_TYPE;
                bind_fmt.maxlength = 8;
                p_ct_bind(trs->cmd, i + 1, &bind_fmt, &c->num.i64,
                          &c->datalen, &c->indicator);
                c->datatype = CS_BIGINT_TYPE;
                break;

            case CS_FLOAT_TYPE:
                bind_fmt.datatype = CS_FLOAT_TYPE;
                bind_fmt.maxlength = sizeof(double);
                p_ct_bind(trs->cmd, i + 1, &bind_fmt, &c->num.dbl,
                          &c->datalen, &c->indicator);
                c->datatype = CS_FLOAT_TYPE;
                break;

            case CS_REAL_TYPE:
                bind_fmt.datatype = CS_REAL_TYPE;
                bind_fmt.maxlength = sizeof(float);
                p_ct_bind(trs->cmd, i + 1, &bind_fmt, &c->num.flt,
                          &c->datalen, &c->indicator);
                c->datatype = CS_REAL_TYPE;
                break;

            default: {
                /* Everything else as string. Cap buffer at 64KB for MAX types
                 * (NVARCHAR(MAX) reports maxlength = 2GB which would OOM) */
                /* Min 256 bytes: binary types (DATETIME=8, BINARY=N) need
                 * room for string conversion; max 64K for MAX types */
                CS_INT maxlen = desc.maxlength;
                if (maxlen < 256) maxlen = 256;
                if (maxlen > 65536) maxlen = 65536;
                bind_fmt.datatype = CS_CHAR_TYPE;
                c->buf_cap = maxlen + 1;
                c->buf = (char *)malloc(c->buf_cap);
                bind_fmt.maxlength = c->buf_cap;
                bind_fmt.format = CS_FMT_NULLTERM;
                p_ct_bind(trs->cmd, i + 1, &bind_fmt, c->buf,
                          &c->datalen, &c->indicator);
                c->datatype = CS_CHAR_TYPE;
                break;
            }
        }
    }

    *out_col_count = ncols;
    return trs;
}

static int tds_get_gen_key(kdbc_stmt *stmt, int64_t *out_key) {
    if (stmt->has_generated_key) {
        *out_key = stmt->generated_key;
        return KDBC_OK;
    }
    STMT_ERR(stmt, "No generated key available");
    return KDBC_ERROR;
}

/* ========================================================================
 * Result set
 * ======================================================================== */

static int tds_rs_next(kdbc_result *rs) {
    tds_result_set *trs = (tds_result_set *)rs->native;
    CS_INT rows_read;
    CS_RETCODE rc = p_ct_fetch(trs->cmd, CS_UNUSED, CS_UNUSED, CS_UNUSED,
                                &rows_read);
    if (rc == CS_SUCCEED) return 1;
    return 0; /* CS_END_DATA or error */
}

static const char *tds_rs_col_name(void *native_rs, int col) {
    tds_result_set *trs = (tds_result_set *)native_rs;
    if (col < 1 || col > trs->col_count) return NULL;
    return trs->col_names[col - 1];
}

static int tds_rs_is_null(kdbc_result *rs, int col) {
    tds_result_set *trs = (tds_result_set *)rs->native;
    rs->last_null = (trs->cols[col - 1].indicator == -1);
    return rs->last_null;
}

static int64_t tds_rs_get_long(kdbc_result *rs, int col) {
    tds_result_set *trs = (tds_result_set *)rs->native;
    tds_col_data *c = &trs->cols[col - 1];
    if (c->indicator == -1) { rs->last_null = 1; return 0; }
    rs->last_null = 0;

    switch (c->datatype) {
        case CS_INT_TYPE:    return (int64_t)c->num.i32;
        case CS_BIGINT_TYPE: return c->num.i64;
        case CS_FLOAT_TYPE:  return (int64_t)c->num.dbl;
        case CS_REAL_TYPE:   return (int64_t)c->num.flt;
        default:
            if (c->buf) {
                long long v = 0;
                sscanf(c->buf, "%lld", &v);
                return v;
            }
            return 0;
    }
}

static double tds_rs_get_double(kdbc_result *rs, int col) {
    tds_result_set *trs = (tds_result_set *)rs->native;
    tds_col_data *c = &trs->cols[col - 1];
    if (c->indicator == -1) { rs->last_null = 1; return 0.0; }
    rs->last_null = 0;

    switch (c->datatype) {
        case CS_FLOAT_TYPE:  return c->num.dbl;
        case CS_REAL_TYPE:   return (double)c->num.flt;
        case CS_INT_TYPE:    return (double)c->num.i32;
        case CS_BIGINT_TYPE: return (double)c->num.i64;
        default:
            if (c->buf) return strtod(c->buf, NULL);
            return 0.0;
    }
}

static const char *tds_rs_get_string(kdbc_result *rs, int col) {
    tds_result_set *trs = (tds_result_set *)rs->native;
    tds_col_data *c = &trs->cols[col - 1];
    if (c->indicator == -1) { rs->last_null = 1; return NULL; }
    rs->last_null = 0;

    switch (c->datatype) {
        case CS_INT_TYPE:
            snprintf(trs->conv_buf, sizeof(trs->conv_buf), "%d", (int)c->num.i32);
            return trs->conv_buf;
        case CS_BIGINT_TYPE:
            snprintf(trs->conv_buf, sizeof(trs->conv_buf), "%lld", (long long)c->num.i64);
            return trs->conv_buf;
        case CS_FLOAT_TYPE:
            snprintf(trs->conv_buf, sizeof(trs->conv_buf), "%.17g", c->num.dbl);
            return trs->conv_buf;
        case CS_REAL_TYPE:
            snprintf(trs->conv_buf, sizeof(trs->conv_buf), "%.7g", (double)c->num.flt);
            return trs->conv_buf;
        default:
            return c->buf; /* already null-terminated by CS_FMT_NULLTERM */
    }
}

static const void *tds_rs_get_blob(kdbc_result *rs, int col, size_t *out_len) {
    tds_result_set *trs = (tds_result_set *)rs->native;
    tds_col_data *c = &trs->cols[col - 1];
    if (c->indicator == -1) { rs->last_null = 1; *out_len = 0; return NULL; }
    rs->last_null = 0;
    *out_len = (size_t)c->datalen;
    return c->buf;
}

static void tds_rs_close(void *native_rs) {
    tds_result_set *trs = (tds_result_set *)native_rs;
    if (!trs) return;
    /* Drain remaining rows and results before closing */
    CS_INT rows_read;
    while (p_ct_fetch(trs->cmd, CS_UNUSED, CS_UNUSED, CS_UNUSED, &rows_read) == CS_SUCCEED) {}
    CS_INT res_type;
    while (p_ct_results(trs->cmd, &res_type) == CS_SUCCEED) {
        if (res_type == CS_ROW_RESULT || res_type == CS_STATUS_RESULT) {
            while (p_ct_fetch(trs->cmd, CS_UNUSED, CS_UNUSED, CS_UNUSED, &rows_read) == CS_SUCCEED) {}
        }
    }
    if (trs->cols) {
        for (int i = 0; i < trs->col_count; i++)
            free(trs->cols[i].buf);
        free(trs->cols);
    }
    if (trs->col_names) {
        for (int i = 0; i < trs->col_count; i++)
            free(trs->col_names[i]);
        free(trs->col_names);
    }
    /* If we own the cmd handle (direct query), drop it */
    if (trs->owns_cmd && trs->cmd)
        p_ct_cmd_drop(trs->cmd);
    free(trs);
}

/* ========================================================================
 * Date/time result retrieval
 * MSSQL DATETIME as string can be in various formats depending on locale:
 *   "Jun 15 2024  2:30PM"  or  "2024-06-15 14:30:45.000"
 * We try multiple parse patterns.
 * ======================================================================== */

static const char *month_names[] = {
    "Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec"
};

static int parse_month_name(const char *s) {
    for (int i = 0; i < 12; i++)
        if (strncasecmp(s, month_names[i], 3) == 0) return i + 1;
    return 0;
}

static int tds_rs_get_timestamp(kdbc_result *rs, int col,
                                int *year, int *month, int *day,
                                int *hour, int *minute, int *second, int *usec) {
    const char *txt = tds_rs_get_string(rs, col);
    if (!txt) { RS_ERR(rs, "FreeTDS: NULL timestamp value"); return KDBC_ERROR; }
    *usec = 0;
    /* Try ISO format first: 2024-06-15 14:30:45.000 */
    if (sscanf(txt, "%d-%d-%d %d:%d:%d.%d", year, month, day, hour, minute, second, usec) >= 6)
        return KDBC_OK;
    if (sscanf(txt, "%d-%d-%dT%d:%d:%d.%d", year, month, day, hour, minute, second, usec) >= 6)
        return KDBC_OK;
    /* Try MSSQL US format: "Jun 15 2024  2:30:45:000PM" */
    char mon[4] = ""; int h12 = 0; char ampm[3] = "";
    int ms = 0;
    if (sscanf(txt, "%3s %d %d %d:%d:%d:%d%2s", mon, day, year, &h12, minute, second, &ms, ampm) >= 6) {
        *month = parse_month_name(mon);
        *hour = h12;
        if ((ampm[0] == 'P' || ampm[0] == 'p') && h12 != 12) *hour += 12;
        if ((ampm[0] == 'A' || ampm[0] == 'a') && h12 == 12) *hour = 0;
        *usec = ms * 1000;
        if (*month > 0) return KDBC_OK;
    }
    RS_ERR(rs, "FreeTDS: cannot parse timestamp '%s'", txt);
    return KDBC_ERROR;
}

static int tds_rs_get_date(kdbc_result *rs, int col, int *year, int *month, int *day) {
    int h, m, s, us;
    /* Try as full timestamp first */
    if (tds_rs_get_timestamp(rs, col, year, month, day, &h, &m, &s, &us) == KDBC_OK)
        return KDBC_OK;
    /* Try date-only */
    const char *txt = tds_rs_get_string(rs, col);
    if (!txt) { RS_ERR(rs, "FreeTDS: NULL date value"); return KDBC_ERROR; }
    if (sscanf(txt, "%d-%d-%d", year, month, day) >= 3) return KDBC_OK;
    RS_ERR(rs, "FreeTDS: cannot parse date '%s'", txt);
    return KDBC_ERROR;
}

static int tds_rs_get_time(kdbc_result *rs, int col,
                           int *hour, int *minute, int *second, int *usec) {
    /* MSSQL TIME comes as full datetime string ("Jan  1 1900 11:59:58:000000PM")
     * Parse as timestamp and extract time part */
    int y, mo, d;
    if (tds_rs_get_timestamp(rs, col, &y, &mo, &d, hour, minute, second, usec) == KDBC_OK)
        return KDBC_OK;
    /* Try plain time formats */
    const char *txt = tds_rs_get_string(rs, col);
    if (!txt) return KDBC_ERROR;
    *usec = 0;
    if (sscanf(txt, "%d:%d:%d.%d", hour, minute, second, usec) >= 3)
        return KDBC_OK;
    return KDBC_ERROR;
}

/* ========================================================================
 * Driver vtable
 * ======================================================================== */

static const kdbc_driver_vtable freetds_vtable = {
    .name               = "FreeTDS",
    .gk_strategy        = KDBC_GK_BY_INDEX,
    .supports_release_savepoint = 0,
    .load               = tds_load,
    .loaded             = tds_loaded,
    .connect            = tds_connect,
    .close              = tds_close,
    .exec_direct        = tds_exec_direct,
    .query_direct       = tds_query_direct,
    .set_autocommit     = tds_set_autocommit,
    .commit             = tds_commit,
    .rollback           = tds_rollback,
    .get_product_name   = tds_get_product_name,
    .get_product_version = tds_get_product_version,
    .get_major_version  = tds_get_major_version,
    .get_minor_version  = tds_get_minor_version,
    .prepare            = tds_prepare_fn,
    .stmt_close         = tds_stmt_close_fn,
    .bind_null          = tds_bind_null,
    .bind_int           = tds_bind_int,
    .bind_long          = tds_bind_long,
    .bind_double        = tds_bind_double,
    .bind_string        = tds_bind_string,
    .bind_blob          = tds_bind_blob,
    .bind_timestamp     = tds_bind_timestamp,
    .bind_date          = tds_bind_date,
    .bind_time          = tds_bind_time,
    .execute_update     = tds_execute_update,
    .execute_query      = tds_execute_query,
    .get_generated_key  = tds_get_gen_key,
    .rs_next            = tds_rs_next,
    .rs_col_name        = tds_rs_col_name,
    .rs_col_label       = NULL,
    .rs_is_null         = tds_rs_is_null,
    .rs_get_long        = tds_rs_get_long,
    .rs_get_double      = tds_rs_get_double,
    .rs_get_string      = tds_rs_get_string,
    .rs_get_blob        = tds_rs_get_blob,
    .rs_get_timestamp   = tds_rs_get_timestamp,
    .rs_get_date        = tds_rs_get_date,
    .rs_get_time        = tds_rs_get_time,
    .rs_close           = tds_rs_close,
    .stmt_reset         = NULL,
    .conn_gk_strategy   = NULL,
    .prepare_call       = NULL,
    .call_execute       = NULL,
    .call_get_out       = NULL,
};

void kdbc_register_freetds(void) {
    kdbc_register_driver(KDBC_FREETDS, &freetds_vtable);
}
