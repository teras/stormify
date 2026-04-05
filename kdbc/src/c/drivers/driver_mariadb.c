/*
 * KDBC Native - MariaDB/MySQL driver (binary protocol)
 *
 * Uses dlopen/dlsym to load libmariadb (or libmysqlclient) at runtime.
 * Parameters and results use the MySQL binary prepared statement protocol
 * (mysql_stmt_* API) for type safety and performance.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
#include "../include/kdbc_internal.h"
#include "../include/kdbc_dl.h"
#include <mysql/mysql.h>
#include <pthread.h>

/* Aliases for our code (real types come from mysql.h) */
typedef MYSQL_BIND  MY_BIND;
typedef MYSQL_FIELD MY_FIELD;
typedef my_bool     my_bool_t; /* may already be defined */

/* ========================================================================
 * Function pointer types
 * ======================================================================== */

typedef int          (*fn_mysql_library_init)(int, char **, char **);
typedef MYSQL       *(*fn_mysql_init)(MYSQL *);
typedef int          (*fn_mariadb_cancel)(MYSQL *);
typedef MYSQL       *(*fn_mysql_real_connect)(MYSQL *, const char *, const char *,
                                              const char *, const char *,
                                              unsigned int, const char *, unsigned long);
typedef void         (*fn_mysql_close)(MYSQL *);
typedef const char  *(*fn_mysql_error)(MYSQL *);
typedef unsigned int (*fn_mysql_errno)(MYSQL *);
typedef my_bool      (*fn_mysql_autocommit)(MYSQL *, my_bool);
typedef my_bool      (*fn_mysql_commit)(MYSQL *);
typedef my_bool      (*fn_mysql_rollback)(MYSQL *);
typedef const char  *(*fn_mysql_get_server_info)(MYSQL *);
typedef unsigned long(*fn_mysql_get_server_version)(MYSQL *);

typedef MYSQL_STMT  *(*fn_mysql_stmt_init)(MYSQL *);
typedef int          (*fn_mysql_stmt_prepare)(MYSQL_STMT *, const char *, unsigned long);
typedef int          (*fn_mysql_stmt_execute)(MYSQL_STMT *);
typedef int          (*fn_mysql_stmt_fetch)(MYSQL_STMT *);
typedef int          (*fn_mysql_stmt_fetch_column)(MYSQL_STMT *, MY_BIND *, unsigned int, unsigned long);
typedef unsigned long(*fn_mysql_stmt_param_count)(MYSQL_STMT *);
typedef my_bool      (*fn_mysql_stmt_bind_param)(MYSQL_STMT *, MY_BIND *);
typedef my_bool      (*fn_mysql_stmt_bind_result)(MYSQL_STMT *, MY_BIND *);
typedef int          (*fn_mysql_stmt_store_result)(MYSQL_STMT *);
typedef MYSQL_RES   *(*fn_mysql_stmt_result_metadata)(MYSQL_STMT *);
typedef my_bool      (*fn_mysql_stmt_close)(MYSQL_STMT *);
typedef unsigned int (*fn_mysql_stmt_errno)(MYSQL_STMT *);
typedef const char  *(*fn_mysql_stmt_error)(MYSQL_STMT *);
typedef unsigned long long (*fn_mysql_stmt_affected_rows)(MYSQL_STMT *);
typedef unsigned long long (*fn_mysql_stmt_insert_id)(MYSQL_STMT *);
typedef unsigned int (*fn_mysql_num_fields)(MYSQL_RES *);
typedef MY_FIELD    *(*fn_mysql_fetch_fields)(MYSQL_RES *);
typedef void         (*fn_mysql_free_result)(MYSQL_RES *);
typedef int          (*fn_mysql_query)(MYSQL *, const char *);
typedef int          (*fn_mysql_options)(MYSQL *, int, const void *);

/* ========================================================================
 * Loaded function pointers
 * ======================================================================== */

static kdbc_lib_handle lib_handle = NULL;

static fn_mysql_library_init          p_library_init;
static fn_mariadb_cancel              p_mariadb_cancel; /* optional - NULL on MySQL libmysqlclient */
static fn_mysql_init                  p_init;
static fn_mysql_real_connect          p_real_connect;
static fn_mysql_close                 p_close;
static fn_mysql_error                 p_error;
static fn_mysql_errno                 p_errno_;
static fn_mysql_autocommit            p_autocommit;
static fn_mysql_commit                p_commit;
static fn_mysql_rollback              p_rollback;
static fn_mysql_get_server_info       p_get_server_info;
static fn_mysql_get_server_version    p_get_server_version;
static fn_mysql_stmt_init             p_stmt_init;
static fn_mysql_stmt_prepare          p_stmt_prepare;
static fn_mysql_stmt_execute          p_stmt_execute;
static fn_mysql_stmt_fetch            p_stmt_fetch;
static fn_mysql_stmt_fetch_column     p_stmt_fetch_column;
static fn_mysql_stmt_param_count      p_stmt_param_count;
static fn_mysql_stmt_bind_param       p_stmt_bind_param;
static fn_mysql_stmt_bind_result      p_stmt_bind_result;
static fn_mysql_stmt_store_result     p_stmt_store_result;
static fn_mysql_stmt_result_metadata  p_stmt_result_metadata;
static fn_mysql_stmt_close            p_stmt_close;
static fn_mysql_stmt_errno            p_stmt_errno;
static fn_mysql_stmt_error            p_stmt_error;
static fn_mysql_stmt_affected_rows    p_stmt_affected_rows;
static fn_mysql_stmt_insert_id        p_stmt_insert_id;
static fn_mysql_num_fields            p_num_fields;
static fn_mysql_fetch_fields          p_fetch_fields;
static fn_mysql_free_result           p_free_result;
static fn_mysql_query                 p_query;
static fn_mysql_options               p_options;

/* ========================================================================
 * Library loading
 * ======================================================================== */

#define MY_LOAD(name) do { \
    p_##name = (fn_mysql_##name)kdbc_dl_sym(lib_handle, "mysql_" #name); \
    if (!p_##name) { kdbc_dl_close(lib_handle); lib_handle = NULL; return; } \
} while (0)

static pthread_once_t my_load_once = PTHREAD_ONCE_INIT;
static int            my_load_ok   = 0;

static void my_load_impl(void) {
    lib_handle = kdbc_dl_open(KDBC_LIBNAME("mariadb", "3"), RTLD_LAZY);
    if (!lib_handle) lib_handle = kdbc_dl_open(KDBC_LIBNAME_NOVER("mariadb"), RTLD_LAZY);
    if (!lib_handle) lib_handle = kdbc_dl_open(KDBC_LIBNAME("mysqlclient", "21"), RTLD_LAZY);
    if (!lib_handle) lib_handle = kdbc_dl_open(KDBC_LIBNAME_NOVER("mysqlclient"), RTLD_LAZY);
    if (!lib_handle) return;

    /* mysql_library_init: required once per process before any threading.
     * Resolved via dlsym; present in both libmariadb and libmysqlclient. */
    p_library_init = (fn_mysql_library_init)kdbc_dl_sym(lib_handle, "mysql_library_init");
    if (p_library_init && p_library_init(0, NULL, NULL) != 0) {
        kdbc_dl_close(lib_handle);
        lib_handle = NULL;
        return;
    }

    MY_LOAD(init);
    MY_LOAD(real_connect);
    MY_LOAD(close);
    MY_LOAD(error);

    /* mysql_errno */
    p_errno_ = (fn_mysql_errno)kdbc_dl_sym(lib_handle, "mysql_errno");
    if (!p_errno_) { kdbc_dl_close(lib_handle); lib_handle = NULL; return; }

    MY_LOAD(autocommit);
    MY_LOAD(commit);
    MY_LOAD(rollback);
    MY_LOAD(get_server_info);
    MY_LOAD(get_server_version);
    MY_LOAD(stmt_init);
    MY_LOAD(stmt_prepare);
    MY_LOAD(stmt_execute);
    MY_LOAD(stmt_fetch);
    MY_LOAD(stmt_fetch_column);
    MY_LOAD(stmt_param_count);
    MY_LOAD(stmt_bind_param);
    MY_LOAD(stmt_bind_result);
    MY_LOAD(stmt_store_result);
    MY_LOAD(stmt_result_metadata);
    MY_LOAD(stmt_close);
    MY_LOAD(stmt_errno);
    MY_LOAD(stmt_error);
    MY_LOAD(stmt_affected_rows);
    MY_LOAD(stmt_insert_id);
    MY_LOAD(num_fields);
    MY_LOAD(fetch_fields);
    MY_LOAD(free_result);
    MY_LOAD(query);
    MY_LOAD(options);

    /* mariadb_cancel is present in libmariadb 3.x+ but NOT in Oracle's
     * libmysqlclient. Resolve via dlsym and allow it to be NULL — the
     * cancel wrapper checks before calling. */
    p_mariadb_cancel = (fn_mariadb_cancel)kdbc_dl_sym(lib_handle, "mariadb_cancel");

    my_load_ok = 1;
}

static int my_load(void) {
    pthread_once(&my_load_once, my_load_impl);
    return my_load_ok;
}

static int my_loaded(void) { return lib_handle != NULL; }

/* ========================================================================
 * Driver-specific structures
 * ======================================================================== */

/* Per-parameter data for prepared statements */
typedef struct {
    unsigned int type;        /* MYSQL_TYPE_* */
    union {
        int8_t   i8;
        int16_t  i16;
        int32_t  i32;
        int64_t  i64;
        float    f;
        double   d;
    } num;
    char        *str;        /* owned string/blob copy */
    unsigned long str_len;
    MYSQL_TIME   time_val;   /* native binary date/time/timestamp */
    my_bool      is_null;
    unsigned long length;
} my_param;

typedef struct {
    MYSQL      *mysql;
    MYSQL_STMT *stmt;
    int         param_count;
    my_param   *params;
    MY_BIND    *bind_params;
} my_stmt_data;

/* Per-column data for result set fetching */
typedef struct {
    unsigned int type;
    union {
        int8_t   i8;
        int16_t  i16;
        int32_t  i32;
        int64_t  i64;
        float    f;
        double   d;
    } num;
    char        *buf;
    unsigned long buf_cap;
    MYSQL_TIME   time_val;   /* native binary date/time/timestamp */
    unsigned long length;
    my_bool      is_null;
    my_bool      error;
} my_col_data;

typedef struct {
    MYSQL_STMT  *stmt;         /* not owned - belongs to my_stmt_data */
    MYSQL_RES   *metadata;
    int          col_count;
    my_col_data *cols;
    MY_BIND     *bind_result;
    MY_FIELD    *fields;
    char       **col_names;    /* cached from metadata */
    char         conv_buf[64];
} my_result_set;

/* ========================================================================
 * Connection
 * ======================================================================== */

static void *my_connect(const char *url, const char *user, const char *password,
                        char *err, size_t err_size) {
    MYSQL *mysql = p_init(NULL);
    if (!mysql) {
        snprintf(err, err_size, "MariaDB: failed to init");
        return NULL;
    }

    /* Parse url: host:port/database
     * Use 127.0.0.1 default instead of "localhost" to force TCP
     * (MySQL/MariaDB treats "localhost" as unix socket) */
    char host[256] = "127.0.0.1";
    unsigned int port = 3306;
    char db[256] = "";

    const char *slash = strchr(url, '/');
    if (slash) {
        size_t hp_len = slash - url;
        if (hp_len > 0 && hp_len < sizeof(host)) {
            char hp[256];
            memcpy(hp, url, hp_len);
            hp[hp_len] = '\0';
            const char *colon = strchr(hp, ':');
            if (colon) {
                size_t h_len = colon - hp;
                memcpy(host, hp, h_len);
                host[h_len] = '\0';
                port = (unsigned int)atoi(colon + 1);
            } else {
                snprintf(host, sizeof(host), "%s", hp);
            }
        }
        snprintf(db, sizeof(db), "%s", slash + 1);
    } else {
        snprintf(db, sizeof(db), "%s", url);
    }
    /* Force TCP: MySQL/MariaDB treats "localhost" as unix socket */
    if (strcmp(host, "localhost") == 0) {
        strcpy(host, "127.0.0.1");
    }

    /* Force utf8mb4 for the connection BEFORE connecting so the handshake
     * charset is UTF-8 and the server treats subsequent strings consistently.
     * MYSQL_SET_CHARSET_NAME (enum value 7) is honored by both libmariadb and
     * libmysqlclient. Without this the session falls back to the server
     * default, which may be latin1 and would silently corrupt non-ASCII. */
    p_options(mysql, 7 /* MYSQL_SET_CHARSET_NAME */, "utf8mb4");

    MYSQL *result = p_real_connect(mysql, host, user, password, db, port, NULL, 0);
    if (!result) {
        snprintf(err, err_size, "MariaDB: %s", p_error(mysql));
        p_close(mysql);
        return NULL;
    }

    /* Default autocommit on */
    p_autocommit(mysql, 1);
    return mysql;
}

static void my_close_conn(void *native) {
    if (native) p_close((MYSQL *)native);
}

/* Async cancel of the currently-executing query on this connection.
 * mariadb_cancel sends an interrupt through a separate channel and is
 * safe to call from any thread. Only available with libmariadb — with
 * Oracle's libmysqlclient the symbol is absent and cancel is a no-op. */
static int my_cancel(kdbc_conn *conn) {
    if (!conn || !conn->native) return KDBC_ERROR;
    if (!p_mariadb_cancel) {
        CONN_ERR(conn, "MariaDB cancel not supported by this client library");
        return KDBC_ERROR;
    }
    if (p_mariadb_cancel((MYSQL *)conn->native) != 0) {
        CONN_ERR(conn, "MariaDB cancel failed: %s", p_error((MYSQL *)conn->native));
        return KDBC_ERROR;
    }
    return KDBC_OK;
}

/* ========================================================================
 * Direct SQL execution (for administrative commands: SAVEPOINT, BEGIN, etc.)
 * MySQL/MariaDB can't prepare these via mysql_stmt_prepare.
 * ======================================================================== */

static int my_exec_direct(kdbc_conn *conn, const char *sql) {
    MYSQL *mysql = (MYSQL *)conn->native;
    if (p_query(mysql, sql)) {
        CONN_ERR(conn, "MariaDB: %s", p_error(mysql));
        return KDBC_ERROR;
    }
    return KDBC_OK;
}

/* ========================================================================
 * Transactions
 * ======================================================================== */

static int my_set_autocommit(kdbc_conn *conn, int enabled) {
    MYSQL *mysql = (MYSQL *)conn->native;
    if (p_autocommit(mysql, enabled ? 1 : 0)) {
        CONN_ERR(conn, "MariaDB: %s", p_error(mysql));
        return KDBC_ERROR;
    }
    return KDBC_OK;
}

static int my_commit_tx(kdbc_conn *conn) {
    MYSQL *mysql = (MYSQL *)conn->native;
    if (p_commit(mysql)) {
        CONN_ERR(conn, "MariaDB: %s", p_error(mysql));
        return KDBC_ERROR;
    }
    return KDBC_OK;
}

static int my_rollback_tx(kdbc_conn *conn) {
    MYSQL *mysql = (MYSQL *)conn->native;
    if (p_rollback(mysql)) {
        CONN_ERR(conn, "MariaDB: %s", p_error(mysql));
        return KDBC_ERROR;
    }
    return KDBC_OK;
}

/* ========================================================================
 * Metadata
 * ======================================================================== */

static void my_get_product_name(void *native, char *buf, size_t sz) {
    MYSQL *m = (MYSQL *)native;
    const char *info = p_get_server_info(m);
    /* MariaDB includes "MariaDB" in version string; MySQL doesn't */
    if (info && strstr(info, "MariaDB"))
        snprintf(buf, sz, "MariaDB");
    else
        snprintf(buf, sz, "MySQL");
}

static void my_get_product_version(void *native, char *buf, size_t sz) {
    snprintf(buf, sz, "%s", p_get_server_info((MYSQL *)native));
}

static int my_get_major_version(void *native) {
    unsigned long v = p_get_server_version((MYSQL *)native);
    return (int)(v / 10000);
}

static int my_get_minor_version(void *native) {
    unsigned long v = p_get_server_version((MYSQL *)native);
    return (int)((v % 10000) / 100);
}

/* ========================================================================
 * Statement preparation
 * ======================================================================== */

static void *my_prepare(kdbc_conn *conn, const char *native_sql,
                        const char **ret_cols, int n_ret_cols,
                        int generated_keys_requested,
                        char *err, size_t err_size) {
    (void)ret_cols; (void)n_ret_cols; (void)generated_keys_requested;
    /* MariaDB uses mysql_stmt_insert_id — no per-statement handling needed. */
    MYSQL *mysql = (MYSQL *)conn->native;

    my_stmt_data *sd = (my_stmt_data *)calloc(1, sizeof(my_stmt_data));
    if (!sd) { snprintf(err, err_size, "Out of memory"); return NULL; }

    sd->mysql = mysql;
    sd->stmt = p_stmt_init(mysql);
    if (!sd->stmt) {
        snprintf(err, err_size, "MariaDB: %s", p_error(mysql));
        free(sd);
        return NULL;
    }

    if (p_stmt_prepare(sd->stmt, native_sql, (unsigned long)strlen(native_sql))) {
        snprintf(err, err_size, "MariaDB prepare: %s", p_stmt_error(sd->stmt));
        p_stmt_close(sd->stmt);
        free(sd);
        return NULL;
    }

    sd->param_count = (int)p_stmt_param_count(sd->stmt);
    if (sd->param_count > 0) {
        sd->params = (my_param *)calloc(sd->param_count, sizeof(my_param));
        sd->bind_params = (MY_BIND *)calloc(sd->param_count, sizeof(MY_BIND));
        if (!sd->params || !sd->bind_params) {
            p_stmt_close(sd->stmt);
            free(sd->params);
            free(sd->bind_params);
            free(sd);
            snprintf(err, err_size, "Out of memory");
            return NULL;
        }
        for (int i = 0; i < sd->param_count; i++) {
            sd->params[i].is_null = 1;
            sd->bind_params[i].buffer_type = MYSQL_TYPE_NULL;
            sd->bind_params[i].is_null = &sd->params[i].is_null;
        }
    }

    return sd;
}

static void my_stmt_close_fn(void *native_stmt, void *native_conn) {
    (void)native_conn;
    my_stmt_data *sd = (my_stmt_data *)native_stmt;
    if (!sd) return;
    p_stmt_close(sd->stmt);
    for (int i = 0; i < sd->param_count; i++)
        free(sd->params[i].str);
    free(sd->params);
    free(sd->bind_params);
    free(sd);
}

/* ========================================================================
 * Parameter binding (binary protocol)
 * ======================================================================== */

static void my_setup_bind(my_stmt_data *sd, int idx) {
    int i = idx - 1;
    my_param *p = &sd->params[i];
    MY_BIND *b = &sd->bind_params[i];
    memset(b, 0, sizeof(MY_BIND));
    b->is_null = &p->is_null;
    b->length = &p->length;

    switch (p->type) {
        case MYSQL_TYPE_NULL:
            b->buffer_type = MYSQL_TYPE_NULL;
            break;
        case MYSQL_TYPE_TINY:
            b->buffer_type = MYSQL_TYPE_TINY;
            b->buffer = &p->num.i8;
            break;
        case MYSQL_TYPE_LONG:
            b->buffer_type = MYSQL_TYPE_LONG;
            b->buffer = &p->num.i32;
            break;
        case MYSQL_TYPE_LONGLONG:
            b->buffer_type = MYSQL_TYPE_LONGLONG;
            b->buffer = &p->num.i64;
            break;
        case MYSQL_TYPE_FLOAT:
            b->buffer_type = MYSQL_TYPE_FLOAT;
            b->buffer = &p->num.f;
            break;
        case MYSQL_TYPE_DOUBLE:
            b->buffer_type = MYSQL_TYPE_DOUBLE;
            b->buffer = &p->num.d;
            break;
        case MYSQL_TYPE_STRING:
            b->buffer_type = MYSQL_TYPE_STRING;
            b->buffer = p->str;
            b->buffer_length = p->str_len;
            p->length = p->str_len;
            break;
        case MYSQL_TYPE_BLOB:
            b->buffer_type = MYSQL_TYPE_BLOB;
            b->buffer = p->str;
            b->buffer_length = p->str_len;
            p->length = p->str_len;
            break;
        case MYSQL_TYPE_DATETIME:
        case MYSQL_TYPE_TIMESTAMP:
        case MYSQL_TYPE_DATE:
        case MYSQL_TYPE_TIME:
            b->buffer_type = p->type;
            b->buffer = &p->time_val;
            b->buffer_length = sizeof(MYSQL_TIME);
            break;
    }
}

static int my_bind_null(kdbc_stmt *stmt, int idx) {
    my_stmt_data *sd = (my_stmt_data *)stmt->native;
    sd->params[idx - 1].is_null = 1;
    sd->params[idx - 1].type = MYSQL_TYPE_NULL;
    my_setup_bind(sd, idx);
    return KDBC_OK;
}

static int my_bind_int(kdbc_stmt *stmt, int idx, int val) {
    my_stmt_data *sd = (my_stmt_data *)stmt->native;
    my_param *p = &sd->params[idx - 1];
    p->is_null = 0;
    p->type = MYSQL_TYPE_LONG;
    p->num.i32 = (int32_t)val;
    my_setup_bind(sd, idx);
    return KDBC_OK;
}

static int my_bind_long(kdbc_stmt *stmt, int idx, int64_t val) {
    my_stmt_data *sd = (my_stmt_data *)stmt->native;
    my_param *p = &sd->params[idx - 1];
    p->is_null = 0;
    p->type = MYSQL_TYPE_LONGLONG;
    p->num.i64 = val;
    my_setup_bind(sd, idx);
    return KDBC_OK;
}

static int my_bind_double(kdbc_stmt *stmt, int idx, double val) {
    my_stmt_data *sd = (my_stmt_data *)stmt->native;
    my_param *p = &sd->params[idx - 1];
    p->is_null = 0;
    p->type = MYSQL_TYPE_DOUBLE;
    p->num.d = val;
    my_setup_bind(sd, idx);
    return KDBC_OK;
}

static int my_bind_string(kdbc_stmt *stmt, int idx, const char *val) {
    my_stmt_data *sd = (my_stmt_data *)stmt->native;
    my_param *p = &sd->params[idx - 1];
    p->is_null = 0;
    p->type = MYSQL_TYPE_STRING;
    free(p->str);
    p->str_len = (unsigned long)strlen(val);
    p->str = (char *)malloc(p->str_len + 1);
    if (!p->str) { STMT_ERR(stmt, "Out of memory"); return KDBC_ERROR; }
    memcpy(p->str, val, p->str_len + 1);
    my_setup_bind(sd, idx);
    return KDBC_OK;
}

static int my_bind_blob_fn(kdbc_stmt *stmt, int idx, const void *data, size_t len) {
    my_stmt_data *sd = (my_stmt_data *)stmt->native;
    my_param *p = &sd->params[idx - 1];
    p->is_null = 0;
    p->type = MYSQL_TYPE_BLOB;
    free(p->str);
    p->str_len = (unsigned long)len;
    p->str = (char *)malloc(len);
    if (!p->str) { STMT_ERR(stmt, "Out of memory"); return KDBC_ERROR; }
    memcpy(p->str, data, len);
    my_setup_bind(sd, idx);
    return KDBC_OK;
}

/* Date/time binding via native MYSQL_TIME struct — the binary protocol
 * transmits these as 7-11 bytes on the wire (vs ~20-27 bytes as text) and
 * avoids a server-side parse. second_part holds microseconds directly so
 * DATETIME(6)/TIMESTAMP(6)/TIME(6) round-trip at full precision. */
static int my_bind_timestamp(kdbc_stmt *stmt, int idx,
                             int year, int month, int day,
                             int hour, int minute, int second, int usec) {
    my_stmt_data *sd = (my_stmt_data *)stmt->native;
    my_param *p = &sd->params[idx - 1];
    p->is_null = 0;
    p->type = MYSQL_TYPE_DATETIME;
    memset(&p->time_val, 0, sizeof(p->time_val));
    p->time_val.year   = (unsigned int)year;
    p->time_val.month  = (unsigned int)month;
    p->time_val.day    = (unsigned int)day;
    p->time_val.hour   = (unsigned int)hour;
    p->time_val.minute = (unsigned int)minute;
    p->time_val.second = (unsigned int)second;
    p->time_val.second_part = (unsigned long)usec;
    p->time_val.time_type = MYSQL_TIMESTAMP_DATETIME;
    my_setup_bind(sd, idx);
    return KDBC_OK;
}

static int my_bind_date(kdbc_stmt *stmt, int idx, int year, int month, int day) {
    my_stmt_data *sd = (my_stmt_data *)stmt->native;
    my_param *p = &sd->params[idx - 1];
    p->is_null = 0;
    p->type = MYSQL_TYPE_DATE;
    memset(&p->time_val, 0, sizeof(p->time_val));
    p->time_val.year  = (unsigned int)year;
    p->time_val.month = (unsigned int)month;
    p->time_val.day   = (unsigned int)day;
    p->time_val.time_type = MYSQL_TIMESTAMP_DATE;
    my_setup_bind(sd, idx);
    return KDBC_OK;
}

static int my_bind_time(kdbc_stmt *stmt, int idx,
                        int hour, int minute, int second, int usec) {
    my_stmt_data *sd = (my_stmt_data *)stmt->native;
    my_param *p = &sd->params[idx - 1];
    p->is_null = 0;
    p->type = MYSQL_TYPE_TIME;
    memset(&p->time_val, 0, sizeof(p->time_val));
    p->time_val.hour   = (unsigned int)hour;
    p->time_val.minute = (unsigned int)minute;
    p->time_val.second = (unsigned int)second;
    p->time_val.second_part = (unsigned long)usec;
    p->time_val.time_type = MYSQL_TIMESTAMP_TIME;
    my_setup_bind(sd, idx);
    return KDBC_OK;
}

/* Date/time result retrieval: forward-declared, defined after result set */
static int my_rs_get_timestamp(kdbc_result *rs, int col,
                               int *year, int *month, int *day,
                               int *hour, int *minute, int *second, int *usec);
static int my_rs_get_date(kdbc_result *rs, int col, int *year, int *month, int *day);
static int my_rs_get_time(kdbc_result *rs, int col,
                          int *hour, int *minute, int *second, int *usec);

/* ========================================================================
 * Execution
 * ======================================================================== */

static int my_execute_update(kdbc_stmt *stmt) {
    my_stmt_data *sd = (my_stmt_data *)stmt->native;

    if (sd->param_count > 0) {
        if (p_stmt_bind_param(sd->stmt, sd->bind_params)) {
            STMT_ERR(stmt, "MariaDB bind: %s", p_stmt_error(sd->stmt));
            return KDBC_ERROR;
        }
    }

    if (p_stmt_execute(sd->stmt)) {
        STMT_ERR(stmt, "MariaDB execute: %s", p_stmt_error(sd->stmt));
        return KDBC_ERROR;
    }

    /* Capture generated key */
    unsigned long long insert_id = p_stmt_insert_id(sd->stmt);
    if (insert_id > 0) {
        stmt->generated_key = (int64_t)insert_id;
        stmt->has_generated_key = 1;
    }

    return (int)p_stmt_affected_rows(sd->stmt);
}

static void *my_execute_query(kdbc_stmt *stmt, int *out_col_count,
                              char *err, size_t err_size) {
    my_stmt_data *sd = (my_stmt_data *)stmt->native;

    if (sd->param_count > 0) {
        if (p_stmt_bind_param(sd->stmt, sd->bind_params)) {
            snprintf(err, err_size, "MariaDB bind: %s", p_stmt_error(sd->stmt));
            return NULL;
        }
    }

    if (p_stmt_execute(sd->stmt)) {
        snprintf(err, err_size, "MariaDB execute: %s", p_stmt_error(sd->stmt));
        return NULL;
    }

    MYSQL_RES *meta = p_stmt_result_metadata(sd->stmt);
    if (!meta) {
        snprintf(err, err_size, "MariaDB: no result set");
        return NULL;
    }

    int ncols = (int)p_num_fields(meta);
    MY_FIELD *fields = p_fetch_fields(meta);

    my_result_set *mrs = (my_result_set *)calloc(1, sizeof(my_result_set));
    if (!mrs) {
        p_free_result(meta);
        snprintf(err, err_size, "Out of memory");
        return NULL;
    }

    mrs->stmt = sd->stmt;
    mrs->metadata = meta;
    mrs->col_count = ncols;
    mrs->fields = fields;

    /* Cache column names */
    mrs->col_names = (char **)calloc(ncols, sizeof(char *));
    if (mrs->col_names) {
        for (int i = 0; i < ncols; i++)
            mrs->col_names[i] = fields[i].name;
    }

    /* Allocate result bindings */
    mrs->cols = (my_col_data *)calloc(ncols, sizeof(my_col_data));
    mrs->bind_result = (MY_BIND *)calloc(ncols, sizeof(MY_BIND));
    if (!mrs->cols || !mrs->bind_result) {
        p_free_result(meta);
        free(mrs->cols);
        free(mrs->bind_result);
        free(mrs->col_names);
        free(mrs);
        snprintf(err, err_size, "Out of memory");
        return NULL;
    }

    /* Set up result bindings based on column types */
    for (int i = 0; i < ncols; i++) {
        MY_BIND *b = &mrs->bind_result[i];
        my_col_data *c = &mrs->cols[i];
        c->type = fields[i].type;
        b->is_null = &c->is_null;
        b->length = &c->length;
        b->error = &c->error;

        switch (fields[i].type) {
            case MYSQL_TYPE_TINY:
                b->buffer_type = MYSQL_TYPE_TINY;
                b->buffer = &c->num.i8;
                b->buffer_length = 1;
                break;
            case MYSQL_TYPE_SHORT:
                b->buffer_type = MYSQL_TYPE_SHORT;
                b->buffer = &c->num.i16;
                b->buffer_length = 2;
                break;
            case MYSQL_TYPE_LONG:
                b->buffer_type = MYSQL_TYPE_LONG;
                b->buffer = &c->num.i32;
                b->buffer_length = 4;
                break;
            case MYSQL_TYPE_LONGLONG:
                b->buffer_type = MYSQL_TYPE_LONGLONG;
                b->buffer = &c->num.i64;
                b->buffer_length = 8;
                break;
            case MYSQL_TYPE_FLOAT:
                b->buffer_type = MYSQL_TYPE_FLOAT;
                b->buffer = &c->num.f;
                b->buffer_length = 4;
                break;
            case MYSQL_TYPE_DOUBLE:
                b->buffer_type = MYSQL_TYPE_DOUBLE;
                b->buffer = &c->num.d;
                b->buffer_length = 8;
                break;
            case MYSQL_TYPE_DATETIME:
            case MYSQL_TYPE_TIMESTAMP:
            case MYSQL_TYPE_DATE:
            case MYSQL_TYPE_TIME:
                /* Bind temporal columns to a native MYSQL_TIME receive buffer.
                 * The binary protocol fills year/month/day/hour/minute/second
                 * plus second_part (microseconds) directly — no text parsing. */
                b->buffer_type = fields[i].type;
                b->buffer = &c->time_val;
                b->buffer_length = sizeof(MYSQL_TIME);
                break;
            default:
                /* Everything else as string with a reasonable buffer */
                b->buffer_type = MYSQL_TYPE_STRING;
                c->buf_cap = fields[i].max_length > 0 ? fields[i].max_length + 1 : 4096;
                c->buf = (char *)malloc(c->buf_cap);
                b->buffer = c->buf;
                b->buffer_length = (unsigned long)c->buf_cap;
                break;
        }
    }

    if (p_stmt_bind_result(sd->stmt, mrs->bind_result)) {
        snprintf(err, err_size, "MariaDB bind_result: %s", p_stmt_error(sd->stmt));
        for (int i = 0; i < ncols; i++) free(mrs->cols[i].buf);
        free(mrs->cols);
        free(mrs->bind_result);
        free(mrs->col_names);
        p_free_result(meta);
        free(mrs);
        return NULL;
    }

    p_stmt_store_result(sd->stmt);

    *out_col_count = ncols;
    return mrs;
}

static int my_get_gen_key(kdbc_stmt *stmt, int64_t *out_key) {
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

static int my_rs_next(kdbc_result *rs) {
    my_result_set *mrs = (my_result_set *)rs->native;
    int rc = p_stmt_fetch(mrs->stmt);
    /* MYSQL_DATA_TRUNCATED (101): row fetched but one or more TEXT/BLOB columns
     * exceeded the initial buffer size. Resize affected columns and refetch them. */
    if (rc == 0 || rc == 101) {
        if (rc == 101) {
            for (int i = 0; i < mrs->col_count; i++) {
                my_col_data *c = &mrs->cols[i];
                if (c->is_null) continue;
                /* Temporal columns use a fixed-size MYSQL_TIME buffer and
                 * never need refetch; their c->buf is NULL. */
                if (!c->buf) continue;
                if (c->length > c->buf_cap - 1) {
                    /* Resize and re-fetch this column only */
                    size_t need = (size_t)c->length + 1;
                    char *nb = (char *)realloc(c->buf, need);
                    if (!nb) continue;
                    c->buf = nb;
                    c->buf_cap = need;
                    MY_BIND tmp;
                    memset(&tmp, 0, sizeof(tmp));
                    tmp.buffer_type = MYSQL_TYPE_STRING;
                    tmp.buffer = c->buf;
                    tmp.buffer_length = (unsigned long)c->buf_cap;
                    tmp.length = &c->length;
                    tmp.is_null = &c->is_null;
                    p_stmt_fetch_column(mrs->stmt, &tmp, (unsigned int)i, 0);
                    /* Re-sync bind_result so subsequent fetches on the next row
                     * use the enlarged buffer. */
                    mrs->bind_result[i].buffer = c->buf;
                    mrs->bind_result[i].buffer_length = (unsigned long)c->buf_cap;
                }
            }
            p_stmt_bind_result(mrs->stmt, mrs->bind_result);
        }
        return 1;
    }
    if (rc == 1) {
        RS_ERR(rs, "MariaDB fetch: %s", p_stmt_error(mrs->stmt));
        return KDBC_ERROR;
    }
    return 0; /* MYSQL_NO_DATA (100) */
}

static const char *my_rs_col_name(void *native_rs, int col) {
    my_result_set *mrs = (my_result_set *)native_rs;
    if (mrs->col_names && col >= 1 && col <= mrs->col_count)
        return mrs->col_names[col - 1];
    return NULL;
}

static int my_rs_is_null(kdbc_result *rs, int col) {
    my_result_set *mrs = (my_result_set *)rs->native;
    rs->last_null = mrs->cols[col - 1].is_null;
    return rs->last_null;
}

static int64_t my_rs_get_long(kdbc_result *rs, int col) {
    my_result_set *mrs = (my_result_set *)rs->native;
    my_col_data *c = &mrs->cols[col - 1];
    if (c->is_null) { rs->last_null = 1; return 0; }
    rs->last_null = 0;

    switch (c->type) {
        case MYSQL_TYPE_TINY:     return (int64_t)c->num.i8;
        case MYSQL_TYPE_SHORT:    return (int64_t)c->num.i16;
        case MYSQL_TYPE_LONG:     return (int64_t)c->num.i32;
        case MYSQL_TYPE_LONGLONG: return c->num.i64;
        case MYSQL_TYPE_FLOAT:    return (int64_t)c->num.f;
        case MYSQL_TYPE_DOUBLE:   return (int64_t)c->num.d;
        default:
            if (c->buf) {
                long long v = 0;
                sscanf(c->buf, "%lld", &v);
                return v;
            }
            return 0;
    }
}

static double my_rs_get_double(kdbc_result *rs, int col) {
    my_result_set *mrs = (my_result_set *)rs->native;
    my_col_data *c = &mrs->cols[col - 1];
    if (c->is_null) { rs->last_null = 1; return 0.0; }
    rs->last_null = 0;

    switch (c->type) {
        case MYSQL_TYPE_FLOAT:    return (double)c->num.f;
        case MYSQL_TYPE_DOUBLE:   return c->num.d;
        case MYSQL_TYPE_TINY:     return (double)c->num.i8;
        case MYSQL_TYPE_SHORT:    return (double)c->num.i16;
        case MYSQL_TYPE_LONG:     return (double)c->num.i32;
        case MYSQL_TYPE_LONGLONG: return (double)c->num.i64;
        default:
            if (c->buf) return strtod(c->buf, NULL);
            return 0.0;
    }
}

static const char *my_rs_get_string(kdbc_result *rs, int col) {
    my_result_set *mrs = (my_result_set *)rs->native;
    my_col_data *c = &mrs->cols[col - 1];
    if (c->is_null) { rs->last_null = 1; return NULL; }
    rs->last_null = 0;

    switch (c->type) {
        case MYSQL_TYPE_TINY:
            snprintf(mrs->conv_buf, sizeof(mrs->conv_buf), "%d", (int)c->num.i8);
            return mrs->conv_buf;
        case MYSQL_TYPE_SHORT:
            snprintf(mrs->conv_buf, sizeof(mrs->conv_buf), "%d", (int)c->num.i16);
            return mrs->conv_buf;
        case MYSQL_TYPE_LONG:
            snprintf(mrs->conv_buf, sizeof(mrs->conv_buf), "%d", (int)c->num.i32);
            return mrs->conv_buf;
        case MYSQL_TYPE_LONGLONG:
            snprintf(mrs->conv_buf, sizeof(mrs->conv_buf), "%lld", (long long)c->num.i64);
            return mrs->conv_buf;
        case MYSQL_TYPE_FLOAT:
            snprintf(mrs->conv_buf, sizeof(mrs->conv_buf), "%.7g", (double)c->num.f);
            return mrs->conv_buf;
        case MYSQL_TYPE_DOUBLE:
            snprintf(mrs->conv_buf, sizeof(mrs->conv_buf), "%.17g", c->num.d);
            return mrs->conv_buf;
        case MYSQL_TYPE_DATETIME:
        case MYSQL_TYPE_TIMESTAMP:
            /* Re-format the native MYSQL_TIME into canonical ISO text. */
            if (c->time_val.second_part > 0)
                snprintf(mrs->conv_buf, sizeof(mrs->conv_buf),
                         "%04u-%02u-%02u %02u:%02u:%02u.%06lu",
                         c->time_val.year, c->time_val.month, c->time_val.day,
                         c->time_val.hour, c->time_val.minute, c->time_val.second,
                         c->time_val.second_part);
            else
                snprintf(mrs->conv_buf, sizeof(mrs->conv_buf),
                         "%04u-%02u-%02u %02u:%02u:%02u",
                         c->time_val.year, c->time_val.month, c->time_val.day,
                         c->time_val.hour, c->time_val.minute, c->time_val.second);
            return mrs->conv_buf;
        case MYSQL_TYPE_DATE:
            snprintf(mrs->conv_buf, sizeof(mrs->conv_buf), "%04u-%02u-%02u",
                     c->time_val.year, c->time_val.month, c->time_val.day);
            return mrs->conv_buf;
        case MYSQL_TYPE_TIME:
            if (c->time_val.second_part > 0)
                snprintf(mrs->conv_buf, sizeof(mrs->conv_buf),
                         "%02u:%02u:%02u.%06lu",
                         c->time_val.hour, c->time_val.minute, c->time_val.second,
                         c->time_val.second_part);
            else
                snprintf(mrs->conv_buf, sizeof(mrs->conv_buf),
                         "%02u:%02u:%02u",
                         c->time_val.hour, c->time_val.minute, c->time_val.second);
            return mrs->conv_buf;
        default:
            if (c->buf) {
                /* Ensure null-terminated */
                if (c->length < c->buf_cap)
                    c->buf[c->length] = '\0';
                return c->buf;
            }
            return NULL;
    }
}

static const void *my_rs_get_blob(kdbc_result *rs, int col, size_t *out_len) {
    my_result_set *mrs = (my_result_set *)rs->native;
    my_col_data *c = &mrs->cols[col - 1];
    if (c->is_null) { rs->last_null = 1; *out_len = 0; return NULL; }
    rs->last_null = 0;
    *out_len = (size_t)c->length;
    return c->buf;
}

static void my_rs_close(void *native_rs) {
    my_result_set *mrs = (my_result_set *)native_rs;
    if (!mrs) return;
    if (mrs->cols) {
        for (int i = 0; i < mrs->col_count; i++)
            free(mrs->cols[i].buf);
        free(mrs->cols);
    }
    free(mrs->bind_result);
    free(mrs->col_names);
    if (mrs->metadata) p_free_result(mrs->metadata);
    free(mrs);
}

/* ========================================================================
 * Date/time result retrieval implementation
 * ======================================================================== */

static int my_rs_get_timestamp(kdbc_result *rs, int col,
                               int *year, int *month, int *day,
                               int *hour, int *minute, int *second, int *usec) {
    my_result_set *mrs = (my_result_set *)rs->native;
    my_col_data *c = &mrs->cols[col - 1];
    if (c->is_null) { RS_ERR(rs, "MariaDB: NULL timestamp value"); return KDBC_ERROR; }

    /* Native binary path: column was bound as MYSQL_TYPE_DATETIME/TIMESTAMP/
     * DATE/TIME — fields are filled directly from the wire. */
    if (c->type == MYSQL_TYPE_DATETIME || c->type == MYSQL_TYPE_TIMESTAMP ||
        c->type == MYSQL_TYPE_DATE) {
        *year   = (int)c->time_val.year;
        *month  = (int)c->time_val.month;
        *day    = (int)c->time_val.day;
        *hour   = (int)c->time_val.hour;
        *minute = (int)c->time_val.minute;
        *second = (int)c->time_val.second;
        *usec   = (int)c->time_val.second_part;
        return KDBC_OK;
    }

    /* Text fallback for non-temporal columns (e.g. NEWDECIMAL or VARCHAR
     * holding a stringified timestamp from a user-written SELECT). */
    const char *txt = my_rs_get_string(rs, col);
    if (!txt) { RS_ERR(rs, "MariaDB: NULL timestamp value"); return KDBC_ERROR; }
    *usec = 0;
    int n = sscanf(txt, "%d-%d-%d %d:%d:%d.%d", year, month, day, hour, minute, second, usec);
    if (n < 6)
        n = sscanf(txt, "%d-%d-%dT%d:%d:%d.%d", year, month, day, hour, minute, second, usec);
    if (n >= 6) return KDBC_OK;
    RS_ERR(rs, "MariaDB: cannot parse timestamp '%s'", txt);
    return KDBC_ERROR;
}

static int my_rs_get_date(kdbc_result *rs, int col, int *year, int *month, int *day) {
    my_result_set *mrs = (my_result_set *)rs->native;
    my_col_data *c = &mrs->cols[col - 1];
    if (c->is_null) { RS_ERR(rs, "MariaDB: NULL date value"); return KDBC_ERROR; }

    if (c->type == MYSQL_TYPE_DATE || c->type == MYSQL_TYPE_DATETIME ||
        c->type == MYSQL_TYPE_TIMESTAMP) {
        *year  = (int)c->time_val.year;
        *month = (int)c->time_val.month;
        *day   = (int)c->time_val.day;
        return KDBC_OK;
    }

    const char *txt = my_rs_get_string(rs, col);
    if (!txt) { RS_ERR(rs, "MariaDB: NULL date value"); return KDBC_ERROR; }
    if (sscanf(txt, "%d-%d-%d", year, month, day) >= 3) return KDBC_OK;
    RS_ERR(rs, "MariaDB: cannot parse date '%s'", txt);
    return KDBC_ERROR;
}

static int my_rs_get_time(kdbc_result *rs, int col,
                          int *hour, int *minute, int *second, int *usec) {
    my_result_set *mrs = (my_result_set *)rs->native;
    my_col_data *c = &mrs->cols[col - 1];
    if (c->is_null) { RS_ERR(rs, "MariaDB: NULL time value"); return KDBC_ERROR; }

    if (c->type == MYSQL_TYPE_TIME || c->type == MYSQL_TYPE_DATETIME ||
        c->type == MYSQL_TYPE_TIMESTAMP) {
        *hour   = (int)c->time_val.hour;
        *minute = (int)c->time_val.minute;
        *second = (int)c->time_val.second;
        *usec   = (int)c->time_val.second_part;
        return KDBC_OK;
    }

    const char *txt = my_rs_get_string(rs, col);
    if (!txt) { RS_ERR(rs, "MariaDB: NULL time value"); return KDBC_ERROR; }
    *usec = 0;
    if (sscanf(txt, "%d:%d:%d.%d", hour, minute, second, usec) >= 3) return KDBC_OK;
    RS_ERR(rs, "MariaDB: cannot parse time '%s'", txt);
    return KDBC_ERROR;
}

/* ========================================================================
 * Driver vtable
 * ======================================================================== */

static const kdbc_driver_vtable mariadb_vtable = {
    .name               = "MariaDB",
    .gk_strategy        = KDBC_GK_BY_INDEX,
    .supports_release_savepoint = 1,
    .load               = my_load,
    .loaded             = my_loaded,
    .connect            = my_connect,
    .close              = my_close_conn,
    .cancel             = my_cancel,
    .exec_direct        = my_exec_direct,
    .query_direct       = NULL,
    .set_autocommit     = my_set_autocommit,
    .commit             = my_commit_tx,
    .rollback           = my_rollback_tx,
    .get_product_name   = my_get_product_name,
    .get_product_version = my_get_product_version,
    .get_major_version  = my_get_major_version,
    .get_minor_version  = my_get_minor_version,
    .prepare            = my_prepare,
    .stmt_close         = my_stmt_close_fn,
    .bind_null          = my_bind_null,
    .bind_int           = my_bind_int,
    .bind_long          = my_bind_long,
    .bind_double        = my_bind_double,
    .bind_string        = my_bind_string,
    .bind_blob          = my_bind_blob_fn,
    .bind_timestamp     = my_bind_timestamp,
    .bind_date          = my_bind_date,
    .bind_time          = my_bind_time,
    .execute_update     = my_execute_update,
    .execute_query      = my_execute_query,
    .get_generated_key  = my_get_gen_key,
    .rs_next            = my_rs_next,
    .rs_col_name        = my_rs_col_name,
    .rs_col_label       = NULL,
    .rs_is_null         = my_rs_is_null,
    .rs_get_long        = my_rs_get_long,
    .rs_get_double      = my_rs_get_double,
    .rs_get_string      = my_rs_get_string,
    .rs_get_blob        = my_rs_get_blob,
    .rs_get_timestamp   = my_rs_get_timestamp,
    .rs_get_date        = my_rs_get_date,
    .rs_get_time        = my_rs_get_time,
    .rs_close           = my_rs_close,
    .stmt_reset         = NULL,
    .conn_gk_strategy   = NULL,
    .prepare_call       = NULL,
    .call_execute       = NULL,
    .call_get_out       = NULL,
};

void kdbc_register_mariadb(void) {
    kdbc_register_driver(KDBC_MARIADB, &mariadb_vtable);
}
