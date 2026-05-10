/*
 * KDBC Native — MSSQL driver (via FreeTDS db-lib)
 *
 * Uses dlopen/dlsym to load libsybdb (FreeTDS's db-lib) at runtime.
 *
 * Why db-lib instead of ct-lib:
 *   ct-lib's `_ct_get_server_type()` (FreeTDS src/ctlib/ct.c:2269) has no
 *   mapping from any CS_ type to SYBNVARCHAR / XSYBNVARCHAR — NVARCHAR
 *   parameters are listed as TODO in `_ct_get_client_type()` (ct.c:2246)
 *   and never implemented. This made it impossible to call sp_executesql
 *   via RPC because SQL Server rejects a non-NVARCHAR @statement parameter.
 *
 *   db-lib's `dbrpcparam` (FreeTDS src/dblib/rpc.c:153) accepts any TDS type
 *   directly AND auto-promotes SYBVARCHAR → XSYBNVARCHAR for TDS 7+ when
 *   maxlen and datalen are ≤ 4000. This gives us proper NVARCHAR binding
 *   without manual UCS-2 conversion — db-lib handles UTF-8 → UCS-2 on the
 *   wire using the connection's client charset setting.
 *
 * Wire strategy:
 *   - Parameterized queries use sp_executesql via dbrpcinit/dbrpcparam/dbrpcsend,
 *     mirroring what go-mssqldb, tedious and the Microsoft JDBC driver do.
 *   - Non-parameterized queries use dbcmd/dbsqlexec directly (language batch).
 *   - Generated keys are retrieved via OUTPUT INSERTED clause injected into the SQL.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
#include "../include/kdbc_internal.h"
#include "../include/kdbc_dl.h"
#define MSDBLIB  /* Use Microsoft-style DBDATEREC field names (year/month/…) */
#include <sybfront.h>
#include <sybdb.h>

/* ========================================================================
 * db-lib function pointer types
 * ======================================================================== */

typedef RETCODE      (*fn_dbinit)(void);
typedef void         (*fn_dbexit)(void);
typedef EHANDLEFUNC  (*fn_dberrhandle)(EHANDLEFUNC);
typedef MHANDLEFUNC  (*fn_dbmsghandle)(MHANDLEFUNC);
typedef RETCODE      (*fn_dbsetversion)(DBINT);
typedef LOGINREC    *(*fn_dblogin)(void);
typedef void         (*fn_dbloginfree)(LOGINREC *);
typedef RETCODE      (*fn_dbsetlname)(LOGINREC *, const char *, int);
/* FreeTDS ships only tdsdbopen — dbopen is a header macro, not a symbol. */
typedef DBPROCESS   *(*fn_tdsdbopen)(LOGINREC *, const char *, int);
typedef void         (*fn_dbclose)(DBPROCESS *);
typedef RETCODE      (*fn_dbuse)(DBPROCESS *, const char *);
typedef RETCODE      (*fn_dbcmd)(DBPROCESS *, const char *);
typedef RETCODE      (*fn_dbsqlexec)(DBPROCESS *);
typedef RETCODE      (*fn_dbsqlok)(DBPROCESS *);
typedef RETCODE      (*fn_dbresults)(DBPROCESS *);
typedef STATUS       (*fn_dbnextrow)(DBPROCESS *);
typedef int          (*fn_dbnumcols)(DBPROCESS *);
typedef char        *(*fn_dbcolname)(DBPROCESS *, int);
typedef int          (*fn_dbcoltype)(DBPROCESS *, int);
typedef DBINT        (*fn_dbcollen)(DBPROCESS *, int);
typedef BYTE        *(*fn_dbdata)(DBPROCESS *, int);
typedef DBINT        (*fn_dbdatlen)(DBPROCESS *, int);
typedef DBINT        (*fn_dbcount)(DBPROCESS *);
typedef RETCODE      (*fn_dbrpcinit)(DBPROCESS *, const char *, DBSMALLINT);
typedef RETCODE      (*fn_dbrpcparam)(DBPROCESS *, const char *, BYTE, int, DBINT, DBINT, BYTE *);
typedef RETCODE      (*fn_dbrpcsend)(DBPROCESS *);
typedef int          (*fn_dbnumrets)(DBPROCESS *);
typedef BYTE        *(*fn_dbretdata)(DBPROCESS *, int);
typedef int          (*fn_dbretlen)(DBPROCESS *, int);
typedef int          (*fn_dbrettype)(DBPROCESS *, int);
typedef char        *(*fn_dbretname)(DBPROCESS *, int);
typedef RETCODE      (*fn_dbcancel)(DBPROCESS *);
typedef DBINT        (*fn_dbconvert)(DBPROCESS *, int, const BYTE *, DBINT, int, BYTE *, DBINT);
typedef RETCODE      (*fn_dbsetopt)(DBPROCESS *, int, const char *, int);
typedef RETCODE      (*fn_dbdatecrack)(DBPROCESS *, DBDATEREC *, DBDATETIME *);
typedef RETCODE      (*fn_dbanydatecrack)(DBPROCESS *, DBDATEREC2 *, int, const void *);
typedef int          (*fn_dbtds)(DBPROCESS *);

/* Bulk-copy (bcp_*) — TDS-native bulk INSERT path. Bypasses sp_executesql
 * entirely: bcp streams rows directly into a target table. Available since the
 * earliest FreeTDS db-lib releases. Loaded best-effort; absence falls the
 * batch path back to the sp_executesql per-row sender. */
typedef RETCODE      (*fn_bcp_init)(DBPROCESS *, const char *, const char *,
                                    const char *, int);
typedef RETCODE      (*fn_bcp_bind)(DBPROCESS *, BYTE *, int, DBINT, BYTE *,
                                    int, int, int);
typedef RETCODE      (*fn_bcp_sendrow)(DBPROCESS *);
typedef DBINT        (*fn_bcp_batch)(DBPROCESS *);
typedef DBINT        (*fn_bcp_done)(DBPROCESS *);

/* ========================================================================
 * Loaded function pointers
 * ======================================================================== */

static kdbc_lib_handle lib_handle = NULL;

static fn_dbinit        p_dbinit;
static fn_dbexit        p_dbexit;
static fn_dberrhandle   p_dberrhandle;
static fn_dbmsghandle   p_dbmsghandle;
static fn_dbsetversion  p_dbsetversion;
static fn_dblogin       p_dblogin;
static fn_dbloginfree   p_dbloginfree;
static fn_dbsetlname    p_dbsetlname;
static fn_tdsdbopen     p_tdsdbopen;
static fn_dbclose       p_dbclose;
static fn_dbuse         p_dbuse;
static fn_dbcmd         p_dbcmd;
static fn_dbsqlexec     p_dbsqlexec;
static fn_dbsqlok       p_dbsqlok;
static fn_dbresults     p_dbresults;
static fn_dbnextrow     p_dbnextrow;
static fn_dbnumcols     p_dbnumcols;
static fn_dbcolname     p_dbcolname;
static fn_dbcoltype     p_dbcoltype;
static fn_dbcollen      p_dbcollen;
static fn_dbdata        p_dbdata;
static fn_dbdatlen      p_dbdatlen;
static fn_dbcount       p_dbcount;
static fn_dbrpcinit     p_dbrpcinit;
static fn_dbrpcparam    p_dbrpcparam;
static fn_dbrpcsend     p_dbrpcsend;
static fn_dbnumrets     p_dbnumrets;
static fn_dbretdata     p_dbretdata;
static fn_dbretlen      p_dbretlen;
static fn_dbrettype     p_dbrettype;
static fn_dbretname     p_dbretname;
static fn_dbcancel      p_dbcancel;
static fn_dbconvert     p_dbconvert;
static fn_dbsetopt      p_dbsetopt;
static fn_dbdatecrack   p_dbdatecrack;
static fn_dbanydatecrack p_dbanydatecrack;
static fn_dbtds         p_dbtds;

/* bcp_* — optional. NULL if FreeTDS was built without bulk-copy support. */
static fn_bcp_init      p_bcp_init;
static fn_bcp_bind      p_bcp_bind;
static fn_bcp_sendrow   p_bcp_sendrow;
static fn_bcp_batch     p_bcp_batch;
static fn_bcp_done      p_bcp_done;

/* Thread-local buffer where message handlers stash the last server error
 * along with its numeric `msgno` (used as the kdbc errcode). FreeTDS db-lib
 * does not surface SQLSTATE, so the corresponding kdbc field stays empty. */
#if defined(TARGET_OS_IPHONE) && TARGET_OS_IPHONE
  typedef struct { char buf[1024]; long msgno; } _g_msg_block;
  static pthread_key_t  _g_last_msg_key;
  static pthread_once_t _g_last_msg_once = PTHREAD_ONCE_INIT;
  static void _g_last_msg_init(void) { pthread_key_create(&_g_last_msg_key, free); }
  static inline _g_msg_block *_g_msg_block_get(void) {
      pthread_once(&_g_last_msg_once, _g_last_msg_init);
      _g_msg_block *blk = (_g_msg_block *)pthread_getspecific(_g_last_msg_key);
      if (!blk) { blk = (_g_msg_block *)calloc(1, sizeof(*blk)); pthread_setspecific(_g_last_msg_key, blk); }
      return blk;
  }
  #define g_last_msg   (_g_msg_block_get()->buf)
  #define g_last_msgno (_g_msg_block_get()->msgno)
#else
  static _Thread_local char _g_last_msg_storage[1024] = "";
  static _Thread_local long _g_last_msgno_storage = 0;
  #define g_last_msg   _g_last_msg_storage
  #define g_last_msgno _g_last_msgno_storage
#endif

static int tds_server_msg_handler(DBPROCESS *dbproc, DBINT msgno, int msgstate,
                                  int severity, char *msgtext, char *srvname,
                                  char *procname, int line) {
    (void)dbproc; (void)msgstate; (void)srvname; (void)procname; (void)line;
    /* Severity ≤ 10 is informational (PRINT, etc.); ignore. */
    if (severity > 10 && msgtext) {
        snprintf(g_last_msg, sizeof(g_last_msg),
                 "SQL Server msg %ld, level %d: %s",
                 (long)msgno, severity, msgtext);
        g_last_msgno = (long)msgno;
    }
    return 0;
}

static int tds_err_handler(DBPROCESS *dbproc, int severity, int dberr,
                           int oserr, char *dberrstr, char *oserrstr) {
    (void)dbproc; (void)oserr; (void)oserrstr;
    /* The server message handler typically fires first and populates
     * g_last_msg with the specific SQL Server error. Don't overwrite it with
     * db-lib's generic "check server messages" wrapper. */
    if (g_last_msg[0] == '\0' && dberrstr && dberrstr[0]) {
        snprintf(g_last_msg, sizeof(g_last_msg),
                 "db-lib error %d (sev %d): %s", dberr, severity, dberrstr);
        g_last_msgno = (long)dberr;
    }
    return INT_CANCEL;
}

/* ========================================================================
 * Library loading
 * ======================================================================== */

#define DB_LOAD(var, name) do { \
    var = kdbc_dl_sym(lib_handle, name); \
    if (!var) { kdbc_dl_close(lib_handle); lib_handle = NULL; return 0; } \
} while (0)

static int tds_load(void) {
    if (lib_handle) return 1;

    lib_handle = kdbc_dl_open(KDBC_LIBNAME("sybdb", "5"), RTLD_LAZY);
    if (!lib_handle) lib_handle = kdbc_dl_open(KDBC_LIBNAME_NOVER("sybdb"), RTLD_LAZY);
    if (!lib_handle) lib_handle = kdbc_dl_open(KDBC_LIBNAME_LIBPREFIX("sybdb"), RTLD_LAZY);
    if (!lib_handle) lib_handle = kdbc_dl_open(KDBC_LIBNAME_LIBVER("sybdb", "5"), RTLD_LAZY);
    if (!lib_handle) return 0;

    DB_LOAD(p_dbinit,        "dbinit");
    DB_LOAD(p_dbexit,        "dbexit");
    DB_LOAD(p_dberrhandle,   "dberrhandle");
    DB_LOAD(p_dbmsghandle,   "dbmsghandle");
    DB_LOAD(p_dbsetversion,  "dbsetversion");
    DB_LOAD(p_dblogin,       "dblogin");
    DB_LOAD(p_dbloginfree,   "dbloginfree");
    DB_LOAD(p_dbsetlname,    "dbsetlname");
    DB_LOAD(p_tdsdbopen,     "tdsdbopen");
    DB_LOAD(p_dbclose,       "dbclose");
    DB_LOAD(p_dbuse,         "dbuse");
    DB_LOAD(p_dbcmd,         "dbcmd");
    DB_LOAD(p_dbsqlexec,     "dbsqlexec");
    DB_LOAD(p_dbsqlok,       "dbsqlok");
    DB_LOAD(p_dbresults,     "dbresults");
    DB_LOAD(p_dbnextrow,     "dbnextrow");
    DB_LOAD(p_dbnumcols,     "dbnumcols");
    DB_LOAD(p_dbcolname,     "dbcolname");
    DB_LOAD(p_dbcoltype,     "dbcoltype");
    DB_LOAD(p_dbcollen,      "dbcollen");
    DB_LOAD(p_dbdata,        "dbdata");
    DB_LOAD(p_dbdatlen,      "dbdatlen");
    DB_LOAD(p_dbcount,       "dbcount");
    DB_LOAD(p_dbrpcinit,     "dbrpcinit");
    DB_LOAD(p_dbrpcparam,    "dbrpcparam");
    DB_LOAD(p_dbrpcsend,     "dbrpcsend");
    DB_LOAD(p_dbnumrets,     "dbnumrets");
    DB_LOAD(p_dbretdata,     "dbretdata");
    DB_LOAD(p_dbretlen,      "dbretlen");
    DB_LOAD(p_dbrettype,     "dbrettype");
    DB_LOAD(p_dbretname,     "dbretname");
    DB_LOAD(p_dbcancel,      "dbcancel");
    DB_LOAD(p_dbconvert,     "dbconvert");
    DB_LOAD(p_dbsetopt,      "dbsetopt");
    DB_LOAD(p_dbdatecrack,   "dbdatecrack");
    /* dbanydatecrack is optional (added in later FreeTDS for DATETIMEOFFSET). */
    p_dbanydatecrack = kdbc_dl_sym(lib_handle, "dbanydatecrack");
    DB_LOAD(p_dbtds,         "dbtds");

    /* bcp_* — best-effort. FreeTDS builds with bulk-copy support are universal in
     * practice but the symbols are loaded optionally so we degrade gracefully on
     * stripped builds. tds_bcp_available() gates the batch fast path. */
    p_bcp_init    = kdbc_dl_sym(lib_handle, "bcp_init");
    p_bcp_bind    = kdbc_dl_sym(lib_handle, "bcp_bind");
    p_bcp_sendrow = kdbc_dl_sym(lib_handle, "bcp_sendrow");
    p_bcp_batch   = kdbc_dl_sym(lib_handle, "bcp_batch");
    p_bcp_done    = kdbc_dl_sym(lib_handle, "bcp_done");

    if (p_dbinit() == FAIL) {
        kdbc_dl_close(lib_handle);
        lib_handle = NULL;
        return 0;
    }

    /* Global message / error handlers — installed once. */
    p_dberrhandle(tds_err_handler);
    p_dbmsghandle(tds_server_msg_handler);

    return 1;
}

static int tds_loaded(void) { return lib_handle != NULL; }

/* ========================================================================
 * Connection struct
 * ======================================================================== */

typedef struct {
    DBPROCESS *dbproc;
    int in_transaction;
    int major_version;
    int minor_version;
    char product_version[128];
} tds_conn;

/* ========================================================================
 * Direct SQL execution helper
 * ======================================================================== */

static int tds_exec_simple(DBPROCESS *dbproc, const char *sql,
                           char *err, size_t err_size) {
    g_last_msg[0] = '\0';
    g_last_msgno = 0;
    p_dbcancel(dbproc);
    if (p_dbcmd(dbproc, sql) == FAIL) {
        snprintf(err, err_size, "MSSQL dbcmd: %s",
                 g_last_msg[0] ? g_last_msg : "failed");
        return KDBC_ERROR;
    }
    if (p_dbsqlexec(dbproc) == FAIL) {
        snprintf(err, err_size, "MSSQL dbsqlexec: %s",
                 g_last_msg[0] ? g_last_msg : "failed");
        return KDBC_ERROR;
    }
    while (p_dbresults(dbproc) != NO_MORE_RESULTS) {
        while (p_dbnextrow(dbproc) != NO_MORE_ROWS) {}
    }
    return KDBC_OK;
}

static int tds_exec_direct(kdbc_conn *conn, const char *sql) {
    tds_conn *tc = (tds_conn *)conn->native;
    int rc = tds_exec_simple(tc->dbproc, sql, conn->error, KDBC_ERR_SIZE);
    return rc;
}

/* ========================================================================
 * Connection open / close
 * ======================================================================== */

static void *tds_connect(const char *url, const char *user, const char *password,
                         char *err, size_t err_size) {
    /* URL format: "host:port/database" */
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

    /* Target TDS 7.4 (SQL Server 2012+) — compatible with all modern MS SQL
     * and Azure SQL instances. Older servers will negotiate down automatically. */
    p_dbsetversion(DBVERSION_74);

    LOGINREC *login = p_dblogin();
    if (!login) {
        snprintf(err, err_size, "MSSQL dblogin: out of memory");
        return NULL;
    }

    if (user) p_dbsetlname(login, user, DBSETUSER);
    if (password) p_dbsetlname(login, password, DBSETPWD);
    p_dbsetlname(login, "KDBC", DBSETAPP);
    /* UTF-8 client charset so SYBVARCHAR params auto-promote to XSYBNVARCHAR
     * with correct UTF-8 → UCS-2 transcoding on the wire. See FreeTDS
     * src/dblib/rpc.c:207-213 for the promotion logic. */
    p_dbsetlname(login, "UTF-8", DBSETCHARSET);

    g_last_msg[0] = '\0';
    g_last_msgno = 0;
    /* tdsdbopen(..., 1) selects MS-compatible behaviour (SYBVARCHAR→NVARCHAR
     * auto-promotion, ANSI NULL handling, etc.). */
    DBPROCESS *dbproc = p_tdsdbopen(login, server, 1);
    p_dbloginfree(login);

    if (!dbproc) {
        snprintf(err, err_size, "MSSQL dbopen(%s): %s",
                 server, g_last_msg[0] ? g_last_msg : "connection failed");
        return NULL;
    }

    if (db[0]) {
        if (p_dbuse(dbproc, db) == FAIL) {
            snprintf(err, err_size, "MSSQL dbuse(%s): %s",
                     db, g_last_msg[0] ? g_last_msg : "failed");
            p_dbclose(dbproc);
            return NULL;
        }
    }

    tds_conn *tc = (tds_conn *)calloc(1, sizeof(tds_conn));
    if (!tc) {
        p_dbclose(dbproc);
        snprintf(err, err_size, "Out of memory");
        return NULL;
    }
    tc->dbproc = dbproc;
    tc->in_transaction = 0;

    /* Default TEXTSIZE is 4096 bytes on db-lib, truncating NVARCHAR(MAX) /
     * VARBINARY(MAX) results. Raise to 2GB — the SQL Server max. */
    p_dbsetopt(dbproc, DBTEXTSIZE, "2147483647", 0);

    /* Match the JDBC / ODBC defaults so that:
     *   - Columns without an explicit NULL/NOT NULL clause default to NULLable
     *     (matches PostgreSQL / MySQL / SQLite test DDL behaviour).
     *   - String comparison follows ANSI rules (N'' is empty, not NULL).
     *   - NULL-on-both-sides equality returns NULL, not true. */
    if (p_dbcmd(dbproc,
                "SET ANSI_NULL_DFLT_ON ON; "
                "SET ANSI_NULLS ON; "
                "SET ANSI_PADDING ON; "
                "SET CONCAT_NULL_YIELDS_NULL ON; "
                "SET QUOTED_IDENTIFIER ON; "
                /* ARITHABORT is required by indexed views, filtered indexes,
                 * and computed-column indexes on modern SQL Server; without
                 * it, DML on such tables can error at runtime. Matches the
                 * JDBC driver default. */
                "SET ARITHABORT ON; "
                /* XACT_ABORT ON turns runtime errors (constraint violations,
                 * deadlocks, etc.) into automatic full-transaction rollbacks,
                 * which matches stormify's "exception → rollback" expectation
                 * from the other drivers. Without it, MSSQL can leave the
                 * transaction in a doomed state that requires an explicit
                 * ROLLBACK before any new statement can run. */
                "SET XACT_ABORT ON") != FAIL
        && p_dbsqlexec(dbproc) != FAIL) {
        while (p_dbresults(dbproc) != NO_MORE_RESULTS) {
            while (p_dbnextrow(dbproc) != NO_MORE_ROWS) {}
        }
    }

    /* Query server version once so metadata getters are cheap. */
    if (p_dbcmd(dbproc, "SELECT CAST(SERVERPROPERTY('ProductVersion') AS VARCHAR(128))") != FAIL
        && p_dbsqlexec(dbproc) != FAIL
        && p_dbresults(dbproc) == SUCCEED
        && p_dbnextrow(dbproc) == REG_ROW) {
        BYTE *data = p_dbdata(dbproc, 1);
        DBINT len = p_dbdatlen(dbproc, 1);
        if (data && len > 0) {
            int n = len < (int)sizeof(tc->product_version) - 1 ? len : (int)sizeof(tc->product_version) - 1;
            memcpy(tc->product_version, data, n);
            tc->product_version[n] = '\0';
            sscanf(tc->product_version, "%d.%d", &tc->major_version, &tc->minor_version);
        }
    }
    while (p_dbresults(dbproc) != NO_MORE_RESULTS) {
        while (p_dbnextrow(dbproc) != NO_MORE_ROWS) {}
    }

    return tc;
}

static void tds_close(void *native) {
    tds_conn *tc = (tds_conn *)native;
    if (!tc) return;
    if (tc->dbproc) p_dbclose(tc->dbproc);
    free(tc);
}

/* ========================================================================
 * Transactions — SQL Server needs explicit BEGIN/COMMIT/ROLLBACK.
 * ======================================================================== */

static int tds_set_autocommit(kdbc_conn *conn, int enabled) {
    tds_conn *tc = (tds_conn *)conn->native;
    if (enabled && !conn->autocommit) {
        /* Only COMMIT if a transaction is actually open. */
        if (tc->in_transaction) {
            int rc = tds_exec_direct(conn, "COMMIT TRANSACTION");
            if (rc != KDBC_OK) return rc;
            tc->in_transaction = 0;
        }
    } else if (!enabled && conn->autocommit) {
        int rc = tds_exec_direct(conn, "BEGIN TRANSACTION");
        if (rc != KDBC_OK) return rc;
        tc->in_transaction = 1;
    }
    return KDBC_OK;
}

static int tds_commit(kdbc_conn *conn) {
    tds_conn *tc = (tds_conn *)conn->native;
    if (!tc->in_transaction) return KDBC_OK;
    int rc = tds_exec_direct(conn, "COMMIT TRANSACTION");
    if (rc != KDBC_OK) return rc;
    tc->in_transaction = 0;
    return KDBC_OK;
}

static int tds_rollback(kdbc_conn *conn) {
    tds_conn *tc = (tds_conn *)conn->native;
    if (!tc->in_transaction) return KDBC_OK;
    int rc = tds_exec_direct(conn, "ROLLBACK TRANSACTION");
    if (rc != KDBC_OK) return rc;
    tc->in_transaction = 0;
    return KDBC_OK;
}

/* ========================================================================
 * Metadata
 * ======================================================================== */

static void tds_get_product_name(void *native, char *buf, size_t sz) {
    (void)native;
    snprintf(buf, sz, "Microsoft SQL Server");
}

static void tds_get_product_version(void *native, char *buf, size_t sz) {
    tds_conn *tc = (tds_conn *)native;
    snprintf(buf, sz, "%s", tc->product_version);
}

static int tds_get_major_version(void *native) {
    return ((tds_conn *)native)->major_version;
}

static int tds_get_minor_version(void *native) {
    return ((tds_conn *)native)->minor_version;
}

/* ========================================================================
 * Statement model — we cache the SQL text and bound parameters in the
 * native stmt struct and build a fresh sp_executesql RPC on each execution.
 * ======================================================================== */

typedef struct tds_param {
    int sybtype;            /* SYB* type for dbrpcparam */
    int is_null;
    union {
        int32_t i32;
        int64_t i64;
        double  dbl;
    } num;
    /* Heap-owned bytes for CHAR / BINARY / text-encoded datetime parameters. */
    char *bytes;
    int   bytes_len;
    /* Stringified declaration type used inside the @params block. */
    const char *decl_type;
} tds_param;

typedef struct {
    tds_conn  *tc;
    char      *sql;           /* SQL with ? placeholders (possibly with OUTPUT INSERTED) */
    int        param_count;
    tds_param *params;
    /*
     * Server-side prepared-statement handle returned by sp_prepare. Filled lazily
     * on first execute (because parameter types — needed for the @params declaration
     * — are not known until the first bind cycle completes). 0 means "not prepared
     * yet". Released with sp_unprepare on stmt close.
     *
     * With this handle, subsequent executes call sp_execute @h, @p0, @p1, ...
     * which sends ~100 bytes of params per call instead of the ~400 bytes of
     * full SQL + @params declaration sent by the legacy sp_executesql path.
     */
    int        prepared_handle;
    /* Cached, normalized SQL text used by sp_prepare. Owned. */
    char      *prepared_sql;
    /* Cached @params declaration string used by sp_prepare. Owned. */
    char      *prepared_params_decl;
} tds_stmt_data;

static void tds_param_reset(tds_param *p) {
    free(p->bytes);
    memset(p, 0, sizeof(*p));
    p->is_null = 1;
}

static void *tds_prepare_fn(kdbc_conn *conn, const char *native_sql,
                            const char **ret_cols, int n_ret_cols,
                            int generated_keys_requested,
                            char *err, size_t err_size) {
    tds_conn *tc = (tds_conn *)conn->native;

    /* Inject OUTPUT INSERTED.<col> into INSERT statements when generated keys
     * are requested. SQL Server syntax: INSERT ... OUTPUT INSERTED.id VALUES (...).
     *
     * When the caller requested generated keys but did not specify columns
     * (BY_INDEX path), inject OUTPUT INSERTED.$IDENTITY — SQL Server's pseudo-
     * column referring to the table's IDENTITY column. This works for any
     * table with an identity and keeps the generated key fetch in the same
     * RPC as the INSERT, avoiding a second roundtrip and the @@IDENTITY
     * trigger-contamination pitfall. */
    char *final_sql = NULL;
    int inject_output = (ret_cols && n_ret_cols > 0) ||
                        (generated_keys_requested && n_ret_cols == 0);
    if (inject_output) {
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
            size_t len = strlen(native_sql) + 64;
            for (int i = 0; i < n_ret_cols; i++)
                len += strlen(ret_cols[i]) + 12;
            final_sql = (char *)malloc(len);
            if (final_sql) {
                memcpy(final_sql, native_sql, prefix_len);
                char *dst = final_sql + prefix_len;
                dst += sprintf(dst, "OUTPUT ");
                if (n_ret_cols > 0) {
                    for (int i = 0; i < n_ret_cols; i++) {
                        if (i > 0) dst += sprintf(dst, ", ");
                        dst += sprintf(dst, "INSERTED.%s", ret_cols[i]);
                    }
                } else {
                    dst += sprintf(dst, "INSERTED.$IDENTITY");
                }
                sprintf(dst, " %s", values_pos);
            }
        }
    }

    tds_stmt_data *sd = (tds_stmt_data *)calloc(1, sizeof(tds_stmt_data));
    if (!sd) {
        free(final_sql);
        snprintf(err, err_size, "Out of memory");
        return NULL;
    }
    sd->tc = tc;
    sd->sql = final_sql ? final_sql : strdup(native_sql);
    if (!sd->sql) {
        free(sd);
        snprintf(err, err_size, "Out of memory");
        return NULL;
    }

    sd->param_count = kdbc_count_params(native_sql);
    if (sd->param_count > 0) {
        sd->params = (tds_param *)calloc(sd->param_count, sizeof(tds_param));
        if (!sd->params) {
            free(sd->sql);
            free(sd);
            snprintf(err, err_size, "Out of memory");
            return NULL;
        }
        for (int i = 0; i < sd->param_count; i++) sd->params[i].is_null = 1;
    }
    return sd;
}

static void tds_send_sp_unprepare(tds_stmt_data *sd);

static void tds_stmt_close_fn(void *native_stmt, void *native_conn) {
    (void)native_conn;
    tds_stmt_data *sd = (tds_stmt_data *)native_stmt;
    if (!sd) return;
    /* Best-effort: tell the server to drop the prepared plan. Errors swallowed
     * because the connection may already be broken (in which case the server
     * has already cleaned up). */
    if (sd->prepared_handle != 0 && sd->tc) tds_send_sp_unprepare(sd);
    free(sd->sql);
    free(sd->prepared_sql);
    free(sd->prepared_params_decl);
    if (sd->params) {
        for (int i = 0; i < sd->param_count; i++) free(sd->params[i].bytes);
        free(sd->params);
    }
    free(sd);
}

/* ========================================================================
 * Parameter binding — populates tds_param entries for later use in
 * sp_executesql RPC. No db-lib calls happen here.
 * ======================================================================== */

static int tds_bind_null(kdbc_stmt *stmt, int idx) {
    tds_stmt_data *sd = (tds_stmt_data *)stmt->native;
    tds_param *p = &sd->params[idx - 1];
    tds_param_reset(p);
    p->sybtype = SYBVARCHAR;
    p->decl_type = "NVARCHAR(4000)";
    return KDBC_OK;
}

static int tds_bind_int(kdbc_stmt *stmt, int idx, int val) {
    tds_stmt_data *sd = (tds_stmt_data *)stmt->native;
    tds_param *p = &sd->params[idx - 1];
    tds_param_reset(p);
    p->sybtype = SYBINT4;
    p->decl_type = "INT";
    p->is_null = 0;
    p->num.i32 = val;
    return KDBC_OK;
}

static int tds_bind_long(kdbc_stmt *stmt, int idx, int64_t val) {
    tds_stmt_data *sd = (tds_stmt_data *)stmt->native;
    tds_param *p = &sd->params[idx - 1];
    tds_param_reset(p);
    p->sybtype = SYBINT8;
    p->decl_type = "BIGINT";
    p->is_null = 0;
    p->num.i64 = val;
    return KDBC_OK;
}

static int tds_bind_double(kdbc_stmt *stmt, int idx, double val) {
    tds_stmt_data *sd = (tds_stmt_data *)stmt->native;
    tds_param *p = &sd->params[idx - 1];
    tds_param_reset(p);
    p->sybtype = SYBFLT8;
    p->decl_type = "FLOAT(53)";
    p->is_null = 0;
    p->num.dbl = val;
    return KDBC_OK;
}

static int tds_bind_bool(kdbc_stmt *stmt, int idx, int val) {
    return tds_bind_int(stmt, idx, val);
}

static int tds_bind_string(kdbc_stmt *stmt, int idx, const char *val) {
    tds_stmt_data *sd = (tds_stmt_data *)stmt->native;
    tds_param *p = &sd->params[idx - 1];
    tds_param_reset(p);
    int len = (int)strlen(val);
    /* db-lib auto-promotes SYBVARCHAR → XSYBNVARCHAR only for maxlen and
     * datalen ≤ 4000 UTF-8 bytes. Longer strings must be bound as SYBNTEXT
     * which maps to NVARCHAR(MAX) on the server. */
    if (len > 4000) {
        p->sybtype = SYBNTEXT;
        p->decl_type = "NVARCHAR(MAX)";
    } else {
        p->sybtype = SYBVARCHAR;
        p->decl_type = "NVARCHAR(4000)";
    }
    p->bytes = (char *)malloc(len + 1);
    if (!p->bytes) { STMT_ERR(stmt, "Out of memory"); return KDBC_ERROR; }
    memcpy(p->bytes, val, len);
    p->bytes[len] = '\0';
    p->bytes_len = len;
    p->is_null = 0;
    return KDBC_OK;
}

static int tds_bind_blob(kdbc_stmt *stmt, int idx, const void *data, size_t len) {
    tds_stmt_data *sd = (tds_stmt_data *)stmt->native;
    tds_param *p = &sd->params[idx - 1];
    tds_param_reset(p);
    /* SYBIMAGE for unlimited-length binary — maps to VARBINARY(MAX). */
    p->sybtype = SYBIMAGE;
    p->decl_type = "VARBINARY(MAX)";
    p->bytes = (char *)malloc(len > 0 ? len : 1);
    if (!p->bytes) { STMT_ERR(stmt, "Out of memory"); return KDBC_ERROR; }
    if (len > 0) memcpy(p->bytes, data, len);
    p->bytes_len = (int)len;
    p->is_null = 0;
    return KDBC_OK;
}

/* Datetime / date / time are bound as ISO text and cast server-side via the
 * declared `DATETIME2(7)` / `DATE` / `TIME(7)` @params type.
 *
 * Why not native binary (SYBMSDATETIME2/SYBMSDATE/SYBMSTIME, TDS type codes
 * 40-43)? db-lib has no public struct or packing helper for these types —
 * `dbdatecrack`/`dbanydatecrack` go the other way (binary → DBDATEREC on
 * receive), and there is no `dbdate*make*` companion. DATETIME2's TDS 7.3+
 * wire format is variable-length (6-8 bytes depending on fractional-second
 * scale) with a custom date encoding (days since 0001-01-01) and time
 * encoding (100-ns ticks since midnight); implementing it here would mean
 * hand-rolling the TDS protocol, which is exactly what FreeTDS is supposed
 * to abstract away.
 *
 * The legacy `SYBDATETIME` / `DBDATETIME` struct is 8 bytes binary and IS
 * exposed by db-lib, but caps at millisecond precision and year ≥ 1753 —
 * we'd lose microseconds and pre-1753 dates that DATETIME2 supports.
 *
 * The text path is lossless at our API level (kdbc_bind_timestamp takes
 * `int usec` = microsecond precision, which is below DATETIME2(7)'s 100-ns
 * precision), adds only ~20 extra bytes on the wire per value, and lets the
 * server's dedicated type coercer handle the parse — which is exactly what
 * tedious, pymssql and the Microsoft ODBC driver do internally as well. */
static int tds_bind_timestamp(kdbc_stmt *stmt, int idx,
                              int year, int month, int day,
                              int hour, int minute, int second, int usec) {
    char buf[40];
    snprintf(buf, sizeof(buf), "%04d-%02d-%02d %02d:%02d:%02d.%06d",
             year, month, day, hour, minute, second, usec);
    int rc = tds_bind_string(stmt, idx, buf);
    if (rc == KDBC_OK) {
        tds_stmt_data *sd = (tds_stmt_data *)stmt->native;
        sd->params[idx - 1].decl_type = "DATETIME2(7)";
    }
    return rc;
}

static int tds_bind_date(kdbc_stmt *stmt, int idx, int year, int month, int day) {
    char buf[16];
    snprintf(buf, sizeof(buf), "%04d-%02d-%02d", year, month, day);
    int rc = tds_bind_string(stmt, idx, buf);
    if (rc == KDBC_OK) {
        tds_stmt_data *sd = (tds_stmt_data *)stmt->native;
        sd->params[idx - 1].decl_type = "DATE";
    }
    return rc;
}

static int tds_bind_time(kdbc_stmt *stmt, int idx,
                         int hour, int minute, int second, int usec) {
    char buf[20];
    snprintf(buf, sizeof(buf), "%02d:%02d:%02d.%06d", hour, minute, second, usec);
    int rc = tds_bind_string(stmt, idx, buf);
    if (rc == KDBC_OK) {
        tds_stmt_data *sd = (tds_stmt_data *)stmt->native;
        sd->params[idx - 1].decl_type = "TIME(7)";
    }
    return rc;
}

/* ========================================================================
 * sp_executesql RPC — the heart of parameterized execution
 * ======================================================================== */

/* Translate SQL with `?` placeholders into `@p1`, `@p2`, ... form so that
 * sp_executesql / sp_execute's inner statement references the named parameters.
 * Uses the shared sql_scan_char state machine so it respects single-quoted
 * strings (including escaped `''`), double-quoted identifiers, `-- line
 * comments`, and C-style block comments.
 *
 * Two modes (selected by [inline_nulls]):
 *
 *  - inline_nulls=1 (legacy sp_executesql path): NULL bindings are inlined as
 *    literal `NULL` instead of being declared in the @params list. This lets
 *    MSSQL resolve the type from context — useful when sp_executesql can't
 *    figure out implicit conversions (the documented NVARCHAR→VARBINARY case).
 *
 *  - inline_nulls=0 (sp_prepare path): every `?` becomes an explicit @pN. The
 *    @params declaration covers every slot with a concrete type, so NULL
 *    values are passed via dbrpcparam with datalen=-1 and the server uses the
 *    declared type. Required because sp_prepare bakes the SQL once — we cannot
 *    rewrite `?` per execute based on which slot happens to be NULL this time.
 */
static char *tds_translate_placeholders_ex(const char *sql, tds_param *params,
                                           int param_count, int inline_nulls) {
    size_t src_len = strlen(sql);
    size_t cap = src_len * 6 + 16;
    char *dst = (char *)malloc(cap);
    if (!dst) return NULL;
    size_t di = 0;
    int param_idx = 0;

    sql_scan_state scan;
    sql_scan_init(&scan);

    for (const char *p = sql; *p; p++) {
        const char *before = p;
        int live = sql_scan_char(&p, &scan);
        /* Copy every char in [before, p] — sql_scan_char may advance p by 1
         * for two-character tokens like `--`, `/​*`, `*​/`, `''`. */
        for (const char *q = before; q <= p; q++) {
            if (di + 16 >= cap) {
                cap *= 2;
                char *n = realloc(dst, cap);
                if (!n) { free(dst); return NULL; }
                dst = n;
            }
            if (live && *q == '?') {
                param_idx++;
                if (inline_nulls && param_idx <= param_count && params[param_idx - 1].is_null) {
                    memcpy(dst + di, "NULL", 4);
                    di += 4;
                } else {
                    di += snprintf(dst + di, cap - di, "@p%d", param_idx);
                }
                /* live is only true for the first char of the (before..p) run,
                 * and for a ? it's a single-char token anyway. */
            } else {
                dst[di++] = *q;
            }
        }
    }
    dst[di] = '\0';
    return dst;
}

/* Backwards-compatible wrapper around the original sp_executesql translator. */
static char *tds_translate_placeholders(const char *sql, tds_param *params,
                                        int param_count) {
    return tds_translate_placeholders_ex(sql, params, param_count, 1 /* inline NULLs */);
}

/* Build the @params declaration string like "@p1 INT, @p2 NVARCHAR(4000)".
 * NULL params are inlined as literal NULL by tds_translate_placeholders and
 * are skipped here so their (unknown) type is never declared. */
static char *tds_build_params_decl(tds_stmt_data *sd) {
    size_t cap = (size_t)sd->param_count * 48 + 16;
    char *decl = (char *)malloc(cap);
    if (!decl) return NULL;
    size_t di = 0;
    int emitted = 0;
    for (int i = 0; i < sd->param_count; i++) {
        if (sd->params[i].is_null) continue;
        if (emitted > 0) { decl[di++] = ','; decl[di++] = ' '; }
        const char *t = sd->params[i].decl_type ? sd->params[i].decl_type : "NVARCHAR(MAX)";
        int n = snprintf(decl + di, cap - di, "@p%d %s", i + 1, t);
        di += n;
        emitted++;
        if (di + 64 >= cap) {
            cap *= 2;
            char *new_decl = realloc(decl, cap);
            if (!new_decl) { free(decl); return NULL; }
            decl = new_decl;
        }
    }
    decl[di] = '\0';
    return decl;
}

/* Build a @params declaration that covers EVERY parameter slot (no skipping
 * NULLs). Used by the sp_prepare path where the SQL is baked once and every
 * placeholder must have a declared type. Slots that are NULL on first execute
 * default to NVARCHAR(MAX); callers can avoid the documented VARBINARY/NULL
 * coercion failure by binding ByteArray(0) instead of null. */
static char *tds_build_params_decl_all(tds_stmt_data *sd) {
    size_t cap = (size_t)sd->param_count * 48 + 16;
    char *decl = (char *)malloc(cap);
    if (!decl) return NULL;
    size_t di = 0;
    for (int i = 0; i < sd->param_count; i++) {
        if (i > 0) { decl[di++] = ','; decl[di++] = ' '; }
        const char *t = sd->params[i].decl_type ? sd->params[i].decl_type : "NVARCHAR(MAX)";
        int n = snprintf(decl + di, cap - di, "@p%d %s", i + 1, t);
        di += n;
        if (di + 64 >= cap) {
            cap *= 2;
            char *new_decl = realloc(decl, cap);
            if (!new_decl) { free(decl); return NULL; }
            decl = new_decl;
        }
    }
    decl[di] = '\0';
    return decl;
}

/*
 * Build (or rebuild, if param types changed) the cached SQL and @params
 * declaration used by sp_prepare. Called on first execute (sd->prepared_handle
 * == 0) or when [tds_unprepare] has reset the cache.
 *
 * The cached strings are owned by sd and freed in tds_stmt_close_fn or by a
 * subsequent re-prepare.
 */
static int tds_build_prepared_strings(tds_stmt_data *sd) {
    free(sd->prepared_sql);
    free(sd->prepared_params_decl);
    sd->prepared_sql = tds_translate_placeholders_ex(sd->sql, sd->params,
                                                     sd->param_count, 0 /* no NULL inline */);
    sd->prepared_params_decl = tds_build_params_decl_all(sd);
    if (!sd->prepared_sql || !sd->prepared_params_decl) {
        free(sd->prepared_sql);    sd->prepared_sql = NULL;
        free(sd->prepared_params_decl); sd->prepared_params_decl = NULL;
        return KDBC_ERROR;
    }
    return KDBC_OK;
}

/*
 * Issue sp_prepare on the server, parsing back the OUT @handle. Cache the
 * resulting handle on sd. Subsequent executes go through sp_execute with the
 * cached handle, sending only the param values (not the SQL text) over the
 * wire — matches what mssql-jdbc does by default and what the other four
 * kdbc drivers (PG/MariaDB/Oracle/SQLite) have always done via their native
 * prepare/execute split.
 */
static int tds_send_sp_prepare(tds_stmt_data *sd, char *err, size_t err_size) {
    DBPROCESS *dbproc = sd->tc->dbproc;
    g_last_msg[0] = '\0'; g_last_msgno = 0;

    if (tds_build_prepared_strings(sd) != KDBC_OK) {
        snprintf(err, err_size, "MSSQL: failed to build prepare SQL");
        return KDBC_ERROR;
    }

    if (p_dbrpcinit(dbproc, "sp_prepare", 0) == FAIL) {
        snprintf(err, err_size, "MSSQL dbrpcinit sp_prepare: %s",
                 g_last_msg[0] ? g_last_msg : "failed");
        return KDBC_ERROR;
    }

    /* Arg 1: @handle INT OUTPUT — server fills in the handle. */
    int handle = 0;
    if (p_dbrpcparam(dbproc, "@handle", DBRPCRETURN, SYBINT4, -1, sizeof(handle),
                     (BYTE *)&handle) == FAIL) {
        snprintf(err, err_size, "MSSQL dbrpcparam @handle: %s",
                 g_last_msg[0] ? g_last_msg : "failed");
        return KDBC_ERROR;
    }

    /* Arg 2: @params declaration. */
    {
        int dlen = (int)strlen(sd->prepared_params_decl);
        const char *buf = dlen > 0 ? sd->prepared_params_decl : " ";
        if (dlen == 0) dlen = 1;
        if (p_dbrpcparam(dbproc, "@params", 0, SYBVARCHAR, -1, dlen,
                         (BYTE *)buf) == FAIL) {
            snprintf(err, err_size, "MSSQL dbrpcparam @params: %s",
                     g_last_msg[0] ? g_last_msg : "failed");
            return KDBC_ERROR;
        }
    }

    /* Arg 3: @stmt — the prepared SQL. */
    {
        int slen = (int)strlen(sd->prepared_sql);
        int type = (slen <= 4000) ? SYBVARCHAR : SYBNTEXT;
        if (p_dbrpcparam(dbproc, "@stmt", 0, type, -1, slen,
                         (BYTE *)sd->prepared_sql) == FAIL) {
            snprintf(err, err_size, "MSSQL dbrpcparam @stmt: %s",
                     g_last_msg[0] ? g_last_msg : "failed");
            return KDBC_ERROR;
        }
    }

    if (p_dbrpcsend(dbproc) == FAIL) {
        snprintf(err, err_size, "MSSQL dbrpcsend sp_prepare: %s",
                 g_last_msg[0] ? g_last_msg : "failed");
        return KDBC_ERROR;
    }
    if (p_dbsqlok(dbproc) == FAIL) {
        snprintf(err, err_size, "MSSQL dbsqlok sp_prepare: %s",
                 g_last_msg[0] ? g_last_msg : "failed");
        return KDBC_ERROR;
    }

    /* Drain any result sets (sp_prepare returns none, but be defensive). */
    while (p_dbresults(dbproc) != NO_MORE_RESULTS) {
        while (p_dbnextrow(dbproc) != NO_MORE_ROWS) { /* skip */ }
    }

    /* Read back the OUT handle. dbnumrets returns the number of "return" parameters
     * (excluding the procedure's RETURN_VALUE). We look for the OUT named @handle. */
    int n = p_dbnumrets(dbproc);
    int found = 0;
    for (int i = 1; i <= n; i++) {
        const char *name = p_dbretname(dbproc, i);
        if (!name) continue;
        if (strcasecmp(name, "@handle") != 0 && strcasecmp(name, "handle") != 0) continue;
        BYTE *data = p_dbretdata(dbproc, i);
        int dlen = p_dbretlen(dbproc, i);
        int type = p_dbrettype(dbproc, i);
        if (data && dlen >= (int)sizeof(int) && type == SYBINT4) {
            memcpy(&handle, data, sizeof(int));
            found = 1;
            break;
        }
    }
    if (!found || handle <= 0) {
        /* Some FreeTDS versions surface OUT params unnamed — fall back to scanning
         * for the first SYBINT4 return value. */
        for (int i = 1; i <= n; i++) {
            BYTE *data = p_dbretdata(dbproc, i);
            int dlen = p_dbretlen(dbproc, i);
            int type = p_dbrettype(dbproc, i);
            if (data && dlen >= (int)sizeof(int) && type == SYBINT4) {
                memcpy(&handle, data, sizeof(int));
                if (handle > 0) { found = 1; break; }
            }
        }
    }
    if (!found || handle <= 0) {
        snprintf(err, err_size, "MSSQL sp_prepare: did not return a valid handle");
        return KDBC_ERROR;
    }
    sd->prepared_handle = handle;
    return KDBC_OK;
}

/* Tell the server to drop the prepared plan. Best-effort: errors are swallowed
 * because the connection may have been broken between prepare and close, in
 * which case the server already cleaned up. */
static void tds_send_sp_unprepare(tds_stmt_data *sd) {
    if (!sd || sd->prepared_handle == 0 || !sd->tc) return;
    DBPROCESS *dbproc = sd->tc->dbproc;
    if (!dbproc) return;
    int handle = sd->prepared_handle;
    sd->prepared_handle = 0;
    if (p_dbrpcinit(dbproc, "sp_unprepare", 0) == FAIL) return;
    if (p_dbrpcparam(dbproc, "@handle", 0, SYBINT4, -1, sizeof(handle),
                     (BYTE *)&handle) == FAIL) return;
    if (p_dbrpcsend(dbproc) == FAIL) return;
    if (p_dbsqlok(dbproc) == FAIL) return;
    while (p_dbresults(dbproc) != NO_MORE_RESULTS) {
        while (p_dbnextrow(dbproc) != NO_MORE_ROWS) { /* skip */ }
    }
}

/*
 * Send the user-bound parameters as @p1, @p2, ... arguments to either
 * sp_executesql (legacy path) or sp_execute (server-side prepared path). The
 * dbrpcinit call happens in the caller — this function only emits the user
 * params, with NULL-handling driven by `null_via_datalen`:
 *
 *   - sp_executesql path: NULL params already inlined into @stmt; skip them
 *     here so we don't emit a typeless @pN that the server would reject.
 *   - sp_execute path: every @pN must be sent (the SQL text is fixed); NULL
 *     is conveyed by datalen=-1 in dbrpcparam. The declared type lives on the
 *     server-side prepared plan.
 */
static int tds_send_user_params(tds_stmt_data *sd, int null_via_datalen,
                                char *err, size_t err_size) {
    DBPROCESS *dbproc = sd->tc->dbproc;
    for (int i = 0; i < sd->param_count; i++) {
        tds_param *p = &sd->params[i];
        if (p->is_null && !null_via_datalen) continue;

        char name[12];
        snprintf(name, sizeof(name), "@p%d", i + 1);

        DBINT  maxlen = -1;
        DBINT  datalen;
        BYTE  *value;

        if (p->is_null) {
            /* db-lib NULL signal: value pointer NULL with datalen=0. Passing
             * datalen=-1 here would trip dblib error 20113 for SYBCHAR /
             * SYBVARCHAR / SYBBINARY / SYBVARBINARY (variable-length types
             * require a non-negative datalen even when value is NULL). */
            value = NULL;
            datalen = 0;
            maxlen = -1;
        } else {
            switch (p->sybtype) {
                case SYBINT4:
                    value = (BYTE *)&p->num.i32; datalen = 4;
                    break;
                case SYBINT8:
                    value = (BYTE *)&p->num.i64; datalen = 8;
                    break;
                case SYBFLT8:
                    value = (BYTE *)&p->num.dbl; datalen = 8;
                    break;
                case SYBVARCHAR:
                    value = p->bytes_len > 0 ? (BYTE *)p->bytes : (BYTE *)"";
                    datalen = p->bytes_len;
                    break;
                case SYBNTEXT:
                case SYBIMAGE:
                    maxlen = 0;
                    value = p->bytes_len > 0 ? (BYTE *)p->bytes : (BYTE *)"";
                    datalen = p->bytes_len;
                    break;
                default:
                    snprintf(err, err_size, "MSSQL: unsupported param type %d", p->sybtype);
                    return KDBC_ERROR;
            }
        }

        if (p_dbrpcparam(dbproc, name, 0, p->sybtype, maxlen, datalen, value) == FAIL) {
            snprintf(err, err_size, "MSSQL dbrpcparam %s: %s", name,
                     g_last_msg[0] ? g_last_msg : "failed");
            return KDBC_ERROR;
        }
    }
    return KDBC_OK;
}

/*
 * Eligibility for the sp_prepare/sp_execute fast path.
 *
 * Returns 1 when every parameter slot has either a non-null bound value (with
 * implicit type from p->sybtype + p->decl_type) or a previously-recorded
 * decl_type. Returns 0 when at least one slot is bound NULL with no type
 * recorded — we can't generate a meaningful @params declaration in that case
 * and must fall back to the sp_executesql path which can inline the NULL.
 */
static int tds_can_use_sp_prepare(tds_stmt_data *sd) {
    for (int i = 0; i < sd->param_count; i++) {
        if (sd->params[i].is_null && sd->params[i].decl_type == NULL)
            return 0;
    }
    return 1;
}

/*
 * sp_execute @handle, @p0, @p1, ... — once the statement has been prepared
 * server-side, subsequent executes flow through this path and only send param
 * values over the wire (no SQL text). Mirrors what mssql-jdbc and ojdbc do
 * for repeated executes of the same SQL.
 */
static int tds_send_via_sp_execute(tds_stmt_data *sd, char *err, size_t err_size) {
    DBPROCESS *dbproc = sd->tc->dbproc;

    if (p_dbrpcinit(dbproc, "sp_execute", 0) == FAIL) {
        snprintf(err, err_size, "MSSQL dbrpcinit sp_execute: %s",
                 g_last_msg[0] ? g_last_msg : "failed");
        return KDBC_ERROR;
    }
    /* Arg 1: @handle (input). */
    if (p_dbrpcparam(dbproc, "@handle", 0, SYBINT4, -1, sizeof(int),
                     (BYTE *)&sd->prepared_handle) == FAIL) {
        snprintf(err, err_size, "MSSQL dbrpcparam @handle: %s",
                 g_last_msg[0] ? g_last_msg : "failed");
        return KDBC_ERROR;
    }
    /* Args 2..N+1: user params, NULL via datalen=-1 (server uses declared type). */
    if (tds_send_user_params(sd, 1 /* null_via_datalen */, err, err_size) != KDBC_OK)
        return KDBC_ERROR;

    if (p_dbrpcsend(dbproc) == FAIL) {
        snprintf(err, err_size, "MSSQL dbrpcsend sp_execute: %s",
                 g_last_msg[0] ? g_last_msg : "failed");
        return KDBC_ERROR;
    }
    if (p_dbsqlok(dbproc) == FAIL) {
        snprintf(err, err_size, "MSSQL dbsqlok sp_execute: %s",
                 g_last_msg[0] ? g_last_msg : "failed");
        return KDBC_ERROR;
    }
    return KDBC_OK;
}

static int tds_send_execute(tds_stmt_data *sd, char *err, size_t err_size) {
    DBPROCESS *dbproc = sd->tc->dbproc;
    g_last_msg[0] = '\0';
    g_last_msgno = 0;
    p_dbcancel(dbproc);

    /* No params → plain language batch. */
    if (sd->param_count == 0) {
        if (p_dbcmd(dbproc, sd->sql) == FAIL) {
            snprintf(err, err_size, "MSSQL dbcmd: %s",
                     g_last_msg[0] ? g_last_msg : "failed");
            return KDBC_ERROR;
        }
        if (p_dbsqlexec(dbproc) == FAIL) {
            snprintf(err, err_size, "MSSQL dbsqlexec: %s",
                     g_last_msg[0] ? g_last_msg : "failed");
            return KDBC_ERROR;
        }
        return KDBC_OK;
    }

    /*
     * Server-side prepared path (matches the other 4 kdbc drivers and what
     * mssql-jdbc does by default). We lazily prepare on first execute because
     * parameter types — needed for the @params declaration — are only known
     * once bind_* has been called.
     */
    if (tds_can_use_sp_prepare(sd)) {
        if (sd->prepared_handle == 0) {
            if (tds_send_sp_prepare(sd, err, err_size) != KDBC_OK)
                return KDBC_ERROR;
        }
        return tds_send_via_sp_execute(sd, err, err_size);
    }

    /*
     * Legacy sp_executesql fallback for the case where at least one bound NULL
     * has no type hint — we inline NULL into the SQL text so the server picks
     * the type from context. (See tds_translate_placeholders comment.)
     */
    char *translated_sql = tds_translate_placeholders(sd->sql, sd->params, sd->param_count);
    if (!translated_sql) { snprintf(err, err_size, "Out of memory"); return KDBC_ERROR; }

    int non_null_count = 0;
    for (int i = 0; i < sd->param_count; i++)
        if (!sd->params[i].is_null) non_null_count++;
    if (non_null_count == 0) {
        if (p_dbcmd(dbproc, translated_sql) == FAIL) {
            free(translated_sql);
            snprintf(err, err_size, "MSSQL dbcmd: %s",
                     g_last_msg[0] ? g_last_msg : "failed");
            return KDBC_ERROR;
        }
        free(translated_sql);
        if (p_dbsqlexec(dbproc) == FAIL) {
            snprintf(err, err_size, "MSSQL dbsqlexec: %s",
                     g_last_msg[0] ? g_last_msg : "failed");
            return KDBC_ERROR;
        }
        return KDBC_OK;
    }
    char *params_decl = tds_build_params_decl(sd);
    if (!params_decl) {
        free(translated_sql);
        snprintf(err, err_size, "Out of memory");
        return KDBC_ERROR;
    }

    if (p_dbrpcinit(dbproc, "sp_executesql", 0) == FAIL) {
        free(translated_sql); free(params_decl);
        snprintf(err, err_size, "MSSQL dbrpcinit: %s",
                 g_last_msg[0] ? g_last_msg : "failed");
        return KDBC_ERROR;
    }

    /* Arg 1: @stmt as NVARCHAR — db-lib auto-promotes SYBVARCHAR → XSYBNVARCHAR
     * for TDS 7+ when maxlen/datalen ≤ 4000 UTF-8 bytes. */
    {
        int slen = (int)strlen(translated_sql);
        int type = (slen <= 4000) ? SYBVARCHAR : SYBNTEXT;
        if (p_dbrpcparam(dbproc, "@stmt", 0, type, -1, slen,
                         (BYTE *)translated_sql) == FAIL) {
            free(translated_sql); free(params_decl);
            snprintf(err, err_size, "MSSQL dbrpcparam @stmt: %s",
                     g_last_msg[0] ? g_last_msg : "failed");
            return KDBC_ERROR;
        }
    }

    /* Arg 2: @params declaration. */
    {
        int dlen = (int)strlen(params_decl);
        const char *buf = dlen > 0 ? params_decl : " ";
        if (dlen == 0) dlen = 1;
        if (p_dbrpcparam(dbproc, "@params", 0, SYBVARCHAR, -1, dlen,
                         (BYTE *)buf) == FAIL) {
            free(translated_sql); free(params_decl);
            snprintf(err, err_size, "MSSQL dbrpcparam @params: %s",
                     g_last_msg[0] ? g_last_msg : "failed");
            return KDBC_ERROR;
        }
    }

    /* Args 3+: user parameters.
     *
     * db-lib's dbrpcparam semantics for NULL vs empty:
     *   - NULL parameter  → datalen = -1, value = NULL
     *   - Empty string    → maxlen > 0, datalen = 0, value = non-NULL
     *   - Normal value    → maxlen = -1 (auto), datalen = byte length
     *
     * The SYBVARCHAR → XSYBNVARCHAR auto-promotion in src/dblib/rpc.c:207-213
     * requires maxlen ≤ 4000 AND datalen ≤ 4000, otherwise the parameter is
     * sent as plain SYBVARCHAR which SQL Server NVARCHAR columns reject. */
    for (int i = 0; i < sd->param_count; i++) {
        tds_param *p = &sd->params[i];
        if (p->is_null) continue;  /* NULLs were inlined into the SQL text */
        char name[16];
        snprintf(name, sizeof(name), "@p%d", i + 1);

        BYTE *value = NULL;
        DBINT datalen = 0;
        DBINT maxlen  = -1;  /* -1 = auto-derive from datalen */

        {
            switch (p->sybtype) {
                case SYBINT4:
                    value = (BYTE *)&p->num.i32; datalen = 4;
                    break;
                case SYBINT8:
                    value = (BYTE *)&p->num.i64; datalen = 8;
                    break;
                case SYBFLT8:
                    value = (BYTE *)&p->num.dbl; datalen = 8;
                    break;
                case SYBVARCHAR:
                    value = p->bytes_len > 0 ? (BYTE *)p->bytes : (BYTE *)"";
                    datalen = p->bytes_len;
                    break;
                case SYBNTEXT:
                case SYBIMAGE:
                    /* LOB types: maxlen must be 0 (meaning "MAX"). */
                    maxlen = 0;
                    value = p->bytes_len > 0 ? (BYTE *)p->bytes : (BYTE *)"";
                    datalen = p->bytes_len;
                    break;
                default:
                    free(translated_sql); free(params_decl);
                    snprintf(err, err_size, "MSSQL: unsupported param type %d", p->sybtype);
                    return KDBC_ERROR;
            }
        }

        if (p_dbrpcparam(dbproc, name, 0, p->sybtype, maxlen, datalen, value) == FAIL) {
            free(translated_sql); free(params_decl);
            snprintf(err, err_size, "MSSQL dbrpcparam %s: %s", name,
                     g_last_msg[0] ? g_last_msg : "failed");
            return KDBC_ERROR;
        }
    }

    if (p_dbrpcsend(dbproc) == FAIL) {
        free(translated_sql); free(params_decl);
        snprintf(err, err_size, "MSSQL dbrpcsend: %s",
                 g_last_msg[0] ? g_last_msg : "failed");
        return KDBC_ERROR;
    }
    if (p_dbsqlok(dbproc) == FAIL) {
        free(translated_sql); free(params_decl);
        snprintf(err, err_size, "MSSQL dbsqlok: %s",
                 g_last_msg[0] ? g_last_msg : "failed");
        return KDBC_ERROR;
    }

    free(translated_sql);
    free(params_decl);
    return KDBC_OK;
}

/* ========================================================================
 * Result set — dbdata/dbdatlen/dbcoltype are called on the current row.
 * ======================================================================== */

typedef struct {
    DBPROCESS *dbproc;
    int col_count;
    /* Per-column string cache so getters can return stable pointers. */
    char **str_cache;
    size_t *str_cache_cap;
} tds_result_set;

static tds_result_set *tds_rs_alloc(DBPROCESS *dbproc, int ncols) {
    tds_result_set *rs = (tds_result_set *)calloc(1, sizeof(tds_result_set));
    if (!rs) return NULL;
    rs->dbproc = dbproc;
    rs->col_count = ncols;
    rs->str_cache = (char **)calloc(ncols, sizeof(char *));
    rs->str_cache_cap = (size_t *)calloc(ncols, sizeof(size_t));
    if (!rs->str_cache || !rs->str_cache_cap) {
        free(rs->str_cache); free(rs->str_cache_cap); free(rs);
        return NULL;
    }
    return rs;
}

static void tds_rs_free(tds_result_set *rs) {
    if (!rs) return;
    if (rs->str_cache) {
        for (int i = 0; i < rs->col_count; i++) free(rs->str_cache[i]);
        free(rs->str_cache);
    }
    free(rs->str_cache_cap);
    free(rs);
}

static int tds_rs_ensure_cache(tds_result_set *rs, int col, size_t need) {
    int i = col - 1;
    if (rs->str_cache_cap[i] >= need) return 0;
    size_t newcap = rs->str_cache_cap[i] ? rs->str_cache_cap[i] * 2 : 128;
    while (newcap < need) newcap *= 2;
    char *nb = (char *)realloc(rs->str_cache[i], newcap);
    if (!nb) return -1;
    rs->str_cache[i] = nb;
    rs->str_cache_cap[i] = newcap;
    return 0;
}

static int tds_execute_update(kdbc_stmt *stmt) {
    tds_stmt_data *sd = (tds_stmt_data *)stmt->native;
    DBPROCESS *dbproc = sd->tc->dbproc;

    if (tds_send_execute(sd, stmt->error, KDBC_ERR_SIZE) != KDBC_OK) {
        /* tds_send_execute writes the message into stmt->error via the err buffer
         * but cannot reach the structured fields. The server message handler
         * captured msgno in the thread-local g_last_msgno; surface it here. */
        stmt->sqlstate[0] = '\0';
        stmt->errcode = (int)g_last_msgno;
        return KDBC_ERROR;
    }

    int total = 0;
    int failed = 0;
    while (1) {
        RETCODE rc = p_dbresults(dbproc);
        if (rc == NO_MORE_RESULTS) break;
        if (rc == FAIL) { failed = 1; break; }

        int ncols = p_dbnumcols(dbproc);
        int rows_read = 0;
        int captured_this_result = 0;

        /* Drain rows and capture the generated key from the first row of an
         * OUTPUT INSERTED result set — either explicit ret_cols or the
         * auto-injected $IDENTITY pseudo-column when generated_keys_requested. */
        while (p_dbnextrow(dbproc) == REG_ROW) {
            rows_read++;
            if (stmt->generated_keys_requested && !stmt->has_generated_key
                && !captured_this_result && ncols >= 1) {
                captured_this_result = 1;
                int ctype = p_dbcoltype(dbproc, 1);
                BYTE *data = p_dbdata(dbproc, 1);
                DBINT len = p_dbdatlen(dbproc, 1);
                if (data && len > 0) {
                    long long k = 0;
                    int ok = 1;
                    switch (ctype) {
                        case SYBINT1: k = *(int8_t *)data; break;
                        case SYBINT2: k = *(int16_t *)data; break;
                        case SYBINT4: k = *(int32_t *)data; break;
                        case SYBINT8: k = *(int64_t *)data; break;
                        default: {
                            char buf[128];
                            DBINT n = p_dbconvert(dbproc, ctype, data, len,
                                                   SYBCHAR, (BYTE *)buf, sizeof(buf) - 1);
                            if (n > 0) {
                                buf[n] = '\0';
                                /* Keep text form for non-numeric PKs (GUID, etc.) */
                                free(stmt->generated_key_str);
                                stmt->generated_key_str = (char *)malloc((size_t)n + 1);
                                if (stmt->generated_key_str) {
                                    memcpy(stmt->generated_key_str, buf, (size_t)n + 1);
                                }
                                sscanf(buf, "%lld", &k);
                            }
                            else ok = 0;
                        }
                    }
                    if (ok) {
                        stmt->generated_key = k;
                        stmt->has_generated_key = 1;
                    }
                }
            }
        }

        /* Prefer dbcount (true affected-row count); fall back to rows_read for
         * OUTPUT INSERTED-style result sets where the row count is implicit. */
        DBINT cnt = p_dbcount(dbproc);
        if (cnt > 0) total += cnt;
        else if (rows_read > 0) total += rows_read;
    }

    if (failed) {
        STMT_ERR_V(stmt, NULL, (int)g_last_msgno,
                   "MSSQL: %.1000s", g_last_msg[0] ? g_last_msg : "command failed");
        return KDBC_ERROR;
    }

    return total;
}

/* Return the generated key captured during the most recent execute_update. */
static int tds_get_generated_key(kdbc_stmt *stmt, int64_t *out_key) {
    if (!stmt->has_generated_key) return KDBC_ERROR;
    *out_key = stmt->generated_key;
    return KDBC_OK;
}

static void *tds_execute_query(kdbc_stmt *stmt, int *out_col_count,
                               char *err, size_t err_size) {
    tds_stmt_data *sd = (tds_stmt_data *)stmt->native;
    DBPROCESS *dbproc = sd->tc->dbproc;

    if (tds_send_execute(sd, err, err_size) != KDBC_OK) {
        stmt->sqlstate[0] = '\0';
        stmt->errcode = (int)g_last_msgno;
        return NULL;
    }

    int ncols = 0;
    while (1) {
        RETCODE rc = p_dbresults(dbproc);
        if (rc == NO_MORE_RESULTS) {
            snprintf(err, err_size, "MSSQL: %s",
                     g_last_msg[0] ? g_last_msg : "no result set");
            return NULL;
        }
        if (rc == FAIL) {
            snprintf(err, err_size, "MSSQL: %s",
                     g_last_msg[0] ? g_last_msg : "query failed");
            return NULL;
        }
        ncols = p_dbnumcols(dbproc);
        if (ncols > 0) break;
        /* Skip zero-column result sets (affected row counts, etc.) */
        while (p_dbnextrow(dbproc) != NO_MORE_ROWS) {}
    }

    tds_result_set *rs = tds_rs_alloc(dbproc, ncols);
    if (!rs) { snprintf(err, err_size, "Out of memory"); return NULL; }
    *out_col_count = ncols;
    return rs;
}

static int tds_rs_next(kdbc_result *rs) {
    tds_result_set *trs = (tds_result_set *)rs->native;
    STATUS s = p_dbnextrow(trs->dbproc);
    if (s == REG_ROW) return 1;
    if (s == NO_MORE_ROWS) return 0;
    return KDBC_ERROR;
}

static const char *tds_rs_col_name(void *native_rs, int col) {
    tds_result_set *trs = (tds_result_set *)native_rs;
    return p_dbcolname(trs->dbproc, col);
}

static int tds_rs_is_null(kdbc_result *rs, int col) {
    tds_result_set *trs = (tds_result_set *)rs->native;
    int null = (p_dbdatlen(trs->dbproc, col) == 0 &&
                p_dbdata(trs->dbproc, col) == NULL);
    rs->last_null = null;
    return null;
}

/* Materialize the current row's column as a UTF-8 string in the per-column cache. */
static const char *tds_rs_col_as_string(tds_result_set *trs, int col) {
    BYTE *data = p_dbdata(trs->dbproc, col);
    DBINT len = p_dbdatlen(trs->dbproc, col);
    if (!data || len == 0) return NULL;

    int ctype = p_dbcoltype(trs->dbproc, col);
    /* SYBCHAR / SYBVARCHAR / SYBTEXT come back pre-converted to the client
     * charset (UTF-8) — just copy. Other types go through dbconvert. */
    size_t needed;
    if (ctype == SYBCHAR || ctype == SYBVARCHAR || ctype == SYBTEXT) {
        needed = (size_t)len + 1;
        if (tds_rs_ensure_cache(trs, col, needed) != 0) return NULL;
        memcpy(trs->str_cache[col - 1], data, len);
        trs->str_cache[col - 1][len] = '\0';
        return trs->str_cache[col - 1];
    }

    needed = (size_t)len * 2 + 256;
    if (tds_rs_ensure_cache(trs, col, needed) != 0) return NULL;
    DBINT n = p_dbconvert(trs->dbproc, ctype, data, len, SYBCHAR,
                          (BYTE *)trs->str_cache[col - 1], (DBINT)(needed - 1));
    if (n < 0) return NULL;
    /* dbconvert pads with spaces for fixed-width SYBCHAR output — trim. */
    while (n > 0 && trs->str_cache[col - 1][n - 1] == ' ') n--;
    trs->str_cache[col - 1][n] = '\0';
    return trs->str_cache[col - 1];
}

static int64_t tds_rs_get_long(kdbc_result *rs, int col) {
    tds_result_set *trs = (tds_result_set *)rs->native;
    BYTE *data = p_dbdata(trs->dbproc, col);
    DBINT len = p_dbdatlen(trs->dbproc, col);
    if (!data || len == 0) { rs->last_null = 1; return 0; }
    rs->last_null = 0;
    int ctype = p_dbcoltype(trs->dbproc, col);
    switch (ctype) {
        case SYBINT1: return (int64_t)*(int8_t *)data;
        case SYBINT2: return (int64_t)*(int16_t *)data;
        case SYBINT4: return (int64_t)*(int32_t *)data;
        case SYBINT8: return *(int64_t *)data;
        case SYBBIT:  return (int64_t)*(uint8_t *)data;
        case SYBFLT8: return (int64_t)*(double *)data;
        case SYBREAL: return (int64_t)*(float *)data;
        default: {
            const char *txt = tds_rs_col_as_string(trs, col);
            if (!txt) return 0;
            long long v = 0;
            sscanf(txt, "%lld", &v);
            return v;
        }
    }
}

static double tds_rs_get_double(kdbc_result *rs, int col) {
    tds_result_set *trs = (tds_result_set *)rs->native;
    BYTE *data = p_dbdata(trs->dbproc, col);
    DBINT len = p_dbdatlen(trs->dbproc, col);
    if (!data || len == 0) { rs->last_null = 1; return 0.0; }
    rs->last_null = 0;
    int ctype = p_dbcoltype(trs->dbproc, col);
    switch (ctype) {
        case SYBFLT8: return *(double *)data;
        case SYBREAL: return (double)*(float *)data;
        case SYBINT1: return (double)*(int8_t *)data;
        case SYBINT2: return (double)*(int16_t *)data;
        case SYBINT4: return (double)*(int32_t *)data;
        case SYBINT8: return (double)*(int64_t *)data;
        case SYBBIT:  return (double)*(uint8_t *)data;
        default: {
            const char *txt = tds_rs_col_as_string(trs, col);
            return txt ? strtod(txt, NULL) : 0.0;
        }
    }
}

static const char *tds_rs_get_string(kdbc_result *rs, int col) {
    tds_result_set *trs = (tds_result_set *)rs->native;
    rs->last_null = 0;
    const char *s = tds_rs_col_as_string(trs, col);
    if (!s) rs->last_null = 1;
    return s;
}

static const void *tds_rs_get_blob(kdbc_result *rs, int col, size_t *out_len) {
    tds_result_set *trs = (tds_result_set *)rs->native;
    BYTE *data = p_dbdata(trs->dbproc, col);
    DBINT len = p_dbdatlen(trs->dbproc, col);
    if (!data || len == 0) { rs->last_null = 1; if (out_len) *out_len = 0; return NULL; }
    rs->last_null = 0;
    if (out_len) *out_len = (size_t)len;
    return data;
}

static int tds_rs_get_timestamp(kdbc_result *rs, int col,
                                int *year, int *month, int *day,
                                int *hour, int *minute, int *second, int *usec) {
    tds_result_set *trs = (tds_result_set *)rs->native;
    BYTE *data = p_dbdata(trs->dbproc, col);
    DBINT len = p_dbdatlen(trs->dbproc, col);
    if (!data || len == 0) { rs->last_null = 1; return KDBC_ERROR; }
    rs->last_null = 0;
    int ctype = p_dbcoltype(trs->dbproc, col);

    if (ctype == SYBDATETIME || ctype == SYBDATETIME4) {
        DBDATETIME dt;
        memset(&dt, 0, sizeof(dt));
        if (ctype == SYBDATETIME4) {
            p_dbconvert(trs->dbproc, SYBDATETIME4, data, -1,
                        SYBDATETIME, (BYTE *)&dt, sizeof(dt));
        } else {
            memcpy(&dt, data, sizeof(dt));
        }
        DBDATEREC dr;
        memset(&dr, 0, sizeof(dr));
        if (p_dbdatecrack(trs->dbproc, &dr, &dt) != SUCCEED) return KDBC_ERROR;
        *year  = dr.year;
        *month = dr.month;
        *day   = dr.day;
        *hour  = dr.hour;
        *minute = dr.minute;
        *second = dr.second;
        *usec  = dr.millisecond * 1000;
        return KDBC_OK;
    }

    if (p_dbanydatecrack) {
        DBDATEREC2 dr2;
        memset(&dr2, 0, sizeof(dr2));
        if (p_dbanydatecrack(trs->dbproc, &dr2, ctype, data) == SUCCEED) {
            *year  = dr2.year;
            *month = dr2.month;
            *day   = dr2.day;
            *hour  = dr2.hour;
            *minute = dr2.minute;
            *second = dr2.second;
            *usec  = dr2.nanosecond / 1000;
            return KDBC_OK;
        }
    }

    /* Last-ditch text parse (for DATETIME2/DATE/TIME on older db-lib). */
    const char *txt = tds_rs_col_as_string(trs, col);
    if (!txt) return KDBC_ERROR;
    int y = 0, mo = 0, d = 0, h = 0, mi = 0, s = 0, us = 0;
    if (sscanf(txt, "%d-%d-%d %d:%d:%d.%d", &y, &mo, &d, &h, &mi, &s, &us) >= 3) {
        *year = y; *month = mo; *day = d;
        *hour = h; *minute = mi; *second = s; *usec = us;
        return KDBC_OK;
    }
    return KDBC_ERROR;
}

static int tds_rs_get_date(kdbc_result *rs, int col, int *year, int *month, int *day) {
    int h, mi, s, us;
    return tds_rs_get_timestamp(rs, col, year, month, day, &h, &mi, &s, &us);
}

static int tds_rs_get_time(kdbc_result *rs, int col, int *hour, int *minute, int *second, int *usec) {
    int y, mo, d;
    return tds_rs_get_timestamp(rs, col, &y, &mo, &d, hour, minute, second, usec);
}

static void tds_rs_close(void *native_rs) {
    tds_result_set *trs = (tds_result_set *)native_rs;
    if (!trs) return;
    /* Drain remaining rows/results so the connection is ready for the next command. */
    DBPROCESS *dbproc = trs->dbproc;
    while (p_dbnextrow(dbproc) != NO_MORE_ROWS) {}
    while (p_dbresults(dbproc) != NO_MORE_RESULTS) {
        while (p_dbnextrow(dbproc) != NO_MORE_ROWS) {}
    }
    tds_rs_free(trs);
}

/* ========================================================================
 * Callable statements
 *
 * SQL Server procedures with OUT and INOUT parameters are invoked through
 * the native RPC path (dbrpcinit / dbrpcparam / dbrpcsend) rather than via
 * sp_executesql. Each OUT / INOUT parameter is flagged with the DBRPCRETURN
 * status bit when bound; after dbsqlok and dbresults, db-lib exposes the
 * returned values via dbnumrets / dbretdata / dbretlen / dbrettype.
 *
 * The `EXEC proc ?, ?, ?, ?` SQL template the core produces is only used to
 * extract the procedure name. We don't actually ship that text to the server
 * — the RPC call takes the name directly.
 * ======================================================================== */

/* dbrpcparam wrapper that flags the slot as DBRPCRETURN (OUT or INOUT) and
 * supplies a generous receive buffer. For pure OUT params (is_null=1 at call
 * time) we send a NULL value; for INOUT we send the caller's value. The
 * `maxlen` parameter is the max output buffer the server may populate. */
static RETCODE tds_rpc_send_param(DBPROCESS *dbproc, const char *name,
                                  int is_return, tds_param *p) {
    BYTE status = is_return ? DBRPCRETURN : 0;
    int sybtype = p->sybtype;
    BYTE *value = NULL;
    DBINT datalen = 0;
    DBINT maxlen = -1;

    /* Pure OUT slot the caller never touched — sybtype is 0, treat as
     * SYBVARCHAR with a 4000-byte receive buffer for flexibility. */
    if (sybtype == 0) {
        sybtype = SYBVARCHAR;
    }

    if (p->is_null) {
        /* No input value bound. For pure OUT, we must still reserve an
         * output buffer via maxlen. */
        if (is_return) {
            /* db-lib requires maxlen > 0 for OUT string types. 4000 matches
             * typical NVARCHAR(4000) return values. */
            maxlen = (sybtype == SYBVARCHAR || sybtype == SYBCHAR ||
                      sybtype == SYBIMAGE  || sybtype == SYBBINARY ||
                      sybtype == SYBTEXT   || sybtype == SYBNTEXT) ? 4000 : 8;
        }
    } else {
        /* Bound value — same encoding as sp_executesql path. */
        switch (sybtype) {
            case SYBINT4:
                value = (BYTE *)&p->num.i32; datalen = 4; break;
            case SYBINT8:
                value = (BYTE *)&p->num.i64; datalen = 8; break;
            case SYBFLT8:
                value = (BYTE *)&p->num.dbl; datalen = 8; break;
            case SYBVARCHAR:
                value = p->bytes_len > 0 ? (BYTE *)p->bytes : (BYTE *)"";
                datalen = p->bytes_len;
                if (is_return && datalen < 4000) maxlen = 4000;
                break;
            case SYBNTEXT:
            case SYBIMAGE:
                value = p->bytes_len > 0 ? (BYTE *)p->bytes : (BYTE *)"";
                datalen = p->bytes_len;
                maxlen = is_return ? 0 /* MAX */ : -1;
                break;
            default:
                return FAIL;
        }
    }

    return p_dbrpcparam(dbproc, name, status, sybtype, maxlen, datalen, value);
}

static int tds_call_execute(kdbc_stmt *stmt) {
    tds_stmt_data *sd = (tds_stmt_data *)stmt->native;
    DBPROCESS *dbproc = sd->tc->dbproc;
    g_last_msg[0] = '\0';
    g_last_msgno = 0;
    p_dbcancel(dbproc);

    char proc_name[256];
    static const char *const verbs[] = { "EXECUTE", "EXEC" };
    if (!kdbc_extract_proc_name(sd->sql, verbs, 2, proc_name, sizeof(proc_name))) {
        STMT_ERR(stmt, "MSSQL: cannot parse procedure name from '%s'", sd->sql);
        return KDBC_ERROR;
    }

    if (p_dbrpcinit(dbproc, proc_name, 0) == FAIL) {
        STMT_ERR_V(stmt, NULL, (int)g_last_msgno,
                   "MSSQL dbrpcinit(%s): %s", proc_name,
                   g_last_msg[0] ? g_last_msg : "failed");
        return KDBC_ERROR;
    }

    /* Bind every parameter. We use positional binding (name = NULL-equivalent),
     * but db-lib accepts an empty string to mean "position by order". */
    for (int i = 0; i < sd->param_count; i++) {
        tds_param *p = &sd->params[i];
        int is_return = stmt->out_params && stmt->out_params[i];
        if (tds_rpc_send_param(dbproc, "", is_return, p) == FAIL) {
            STMT_ERR_V(stmt, NULL, (int)g_last_msgno,
                       "MSSQL dbrpcparam[%d]: %s", i + 1,
                       g_last_msg[0] ? g_last_msg : "failed");
            return KDBC_ERROR;
        }
    }

    if (p_dbrpcsend(dbproc) == FAIL) {
        STMT_ERR_V(stmt, NULL, (int)g_last_msgno,
                   "MSSQL dbrpcsend: %s",
                   g_last_msg[0] ? g_last_msg : "failed");
        return KDBC_ERROR;
    }
    if (p_dbsqlok(dbproc) == FAIL) {
        STMT_ERR_V(stmt, NULL, (int)g_last_msgno,
                   "MSSQL dbsqlok: %s",
                   g_last_msg[0] ? g_last_msg : "failed");
        return KDBC_ERROR;
    }

    /* Drain any result sets the procedure may have returned (SELECT ... inside
     * the proc body). We don't expose those here — kdbc's call API only
     * surfaces OUT parameters — but db-lib requires them consumed before
     * return values become available. */
    while (1) {
        RETCODE rc = p_dbresults(dbproc);
        if (rc == NO_MORE_RESULTS) break;
        if (rc == FAIL) {
            STMT_ERR_V(stmt, NULL, (int)g_last_msgno,
                       "MSSQL: %s",
                       g_last_msg[0] ? g_last_msg : "dbresults failed");
            return KDBC_ERROR;
        }
        while (p_dbnextrow(dbproc) != NO_MORE_ROWS) { /* drain rows */ }
    }

    /* Collect return values. dbnumrets gives the count; return slot k
     * corresponds to the k-th parameter we bound with DBRPCRETURN. Map back
     * to the original param index by walking out_params in order. */
    int nrets = p_dbnumrets(dbproc);
    if (nrets > 0 && stmt->out_params && stmt->out_values) {
        int ret_idx = 1;  /* db-lib return slots are 1-based */
        for (int i = 0; i < sd->param_count && ret_idx <= nrets; i++) {
            if (!stmt->out_params[i]) continue;

            BYTE *data = p_dbretdata(dbproc, ret_idx);
            int len = p_dbretlen(dbproc, ret_idx);
            int type = p_dbrettype(dbproc, ret_idx);

            free(stmt->out_values[i]);
            stmt->out_values[i] = NULL;

            if (data && len > 0) {
                /* dbconvert handles every TDS type → SYBCHAR, including
                 * NUMBER/MONEY/DECIMAL that a bespoke switch would have to
                 * duplicate. Allocate a destination sized for the worst-case
                 * text form: 4 × source length plus a 64-byte fudge covers
                 * DECIMAL(38,18) and GUID formatting. */
                size_t dest_cap = (size_t)len * 4 + 64;
                char *dest = (char *)malloc(dest_cap);
                if (dest) {
                    DBINT n = p_dbconvert(dbproc, type, data, len, SYBCHAR,
                                          (BYTE *)dest, (DBINT)(dest_cap - 1));
                    if (n > 0) {
                        dest[n] = '\0';
                        stmt->out_values[i] = dest;
                    } else {
                        free(dest);
                    }
                }
            }
            ret_idx++;
        }
    }

    return 1;
}

/* ========================================================================
 * Driver vtable
 * ======================================================================== */

static const kdbc_driver_vtable mssql_vtable = {
    .name                   = "SQL Server",
    .gk_strategy            = KDBC_GK_BY_INDEX, /* SCOPE_IDENTITY() after INSERT */
    .supports_release_savepoint = 0,
    .load                   = tds_load,
    .loaded                 = tds_loaded,
    .connect                = tds_connect,
    .close                  = tds_close,
    .exec_direct            = tds_exec_direct,
    .query_direct           = NULL,
    .set_autocommit         = tds_set_autocommit,
    .commit                 = tds_commit,
    .rollback               = tds_rollback,
    .get_product_name       = tds_get_product_name,
    .get_product_version    = tds_get_product_version,
    .get_major_version      = tds_get_major_version,
    .get_minor_version      = tds_get_minor_version,
    .prepare                = tds_prepare_fn,
    .stmt_close             = tds_stmt_close_fn,
    .bind_null              = tds_bind_null,
    .bind_bool              = tds_bind_bool,
    .bind_int               = tds_bind_int,
    .bind_long              = tds_bind_long,
    .bind_double            = tds_bind_double,
    .bind_string            = tds_bind_string,
    .bind_blob              = tds_bind_blob,
    .bind_timestamp         = tds_bind_timestamp,
    .bind_date              = tds_bind_date,
    .bind_time              = tds_bind_time,
    .execute_update         = tds_execute_update,
    .execute_query          = tds_execute_query,
    .get_generated_key      = tds_get_generated_key,
    /* No native batch path. db-lib forces a request/response sync per RPC
     * (dbrpcsend + dbsqlok), so we cannot pipeline sp_execute calls.
     * mssql-jdbc beats us on large batches because it writes raw TDS:
     * one PKT_RPC request carrying many sp_execute frames separated by the
     * 0x80 BatchFlag delimiter (MS-TDS §2.2.6.6 RPCRequest), so 100k rows
     * cost one round-trip instead of 100k. Replicating that needs a hand-
     * written TDS writer (bypassing db-lib) — large effort, deferred.
     * useBulkCopyForBatchInsert / bcp_* is a different beast: PKT_BULKLOAD
     * (0x07) skips triggers and CHECK/FK constraints by default, so it
     * belongs behind an explicit bulkLoad() API, not this path. */
    .execute_batch          = NULL,
    .rs_next                = tds_rs_next,
    .rs_col_name            = tds_rs_col_name,
    /* SQL Server returns the alias (e.g. "SELECT x AS y" → "y") via dbcolname,
     * so label and name are the same — just alias the function pointer. */
    .rs_col_label           = tds_rs_col_name,
    .rs_is_null             = tds_rs_is_null,
    .rs_get_long            = tds_rs_get_long,
    .rs_get_double          = tds_rs_get_double,
    .rs_get_string          = tds_rs_get_string,
    .rs_get_blob            = tds_rs_get_blob,
    .rs_get_timestamp       = tds_rs_get_timestamp,
    .rs_get_date            = tds_rs_get_date,
    .rs_get_time            = tds_rs_get_time,
    .rs_close               = tds_rs_close,
    .stmt_reset             = NULL,
    .conn_gk_strategy       = NULL,
    .prepare_call           = NULL,
    .call_execute           = tds_call_execute,
    .call_get_out           = NULL,
};

void kdbc_register_mssql(void) {
    kdbc_register_driver(KDBC_MSSQL, &mssql_vtable);
}
