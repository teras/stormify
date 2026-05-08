package onl.ycode.stormify.schemasync.db

import java.io.PrintWriter
import java.sql.Connection
import java.sql.DriverManager
import java.util.Collections
import java.util.concurrent.Executors
import java.util.logging.Logger
import javax.sql.DataSource

/**
 * Minimal `javax.sql.DataSource` over `DriverManager` for the schema-sync
 * one-shot connection. Schema-sync is a CLI tool: it opens a single
 * connection per session, runs introspection, then exits. A heavyweight
 * connection pool would just delay shutdown — this wrapper hands every
 * `getConnection()` call a fresh `DriverManager` connection so Stormify
 * can manage them through its own short-lived pool.
 *
 * Tracks live connections in a thread-safe set so the TUI's cancel path
 * can interrupt an in-flight introspection by [closeAll]ing them — the
 * cancel-via-close pattern that the JDBC spec requires drivers to honour.
 */
internal class DriverManagerDataSource(
    private val url: String,
    private val user: String?,
    private val password: String?,
) : DataSource {

    private val live: MutableSet<Connection> = Collections.synchronizedSet(HashSet())

    override fun getConnection(): Connection = track(
        if (user != null) DriverManager.getConnection(url, user, password)
        else DriverManager.getConnection(url),
    )

    override fun getConnection(username: String?, password: String?): Connection =
        track(DriverManager.getConnection(url, username, password))

    private fun track(c: Connection): Connection {
        live.add(c)
        return TrackedConnection(c) { live.remove(c) }
    }

    /** Best-effort cancel: aborts every live connection so any in-flight
     *  statement raises and the worker thread exits. Uses
     *  [Connection.abort] (JDBC 4.1) which the spec mandates as
     *  thread-safe and non-blocking — Oracle's driver in particular
     *  ignores [Connection.close] from a foreign thread while a socket
     *  read is in flight, but honours `abort` by sending an OOB break to
     *  the server. Falls back to `close` for drivers that reject `abort`. */
    fun closeAll() {
        synchronized(live) {
            for (c in live.toList()) {
                runCatching { c.abort(ABORT_EXECUTOR) }
                    .recoverCatching { c.close() }
            }
            live.clear()
        }
    }

    override fun getLogWriter(): PrintWriter? = null
    override fun setLogWriter(out: PrintWriter?) = Unit
    override fun setLoginTimeout(seconds: Int) = Unit
    override fun getLoginTimeout(): Int = 0
    override fun getParentLogger(): Logger = Logger.getLogger("global")
    override fun <T : Any?> unwrap(iface: Class<T>?): T = throw UnsupportedOperationException()
    override fun isWrapperFor(iface: Class<*>?): Boolean = false

    private companion object {
        private val ABORT_EXECUTOR = Executors.newCachedThreadPool { r ->
            Thread(r, "schema-sync-abort").apply { isDaemon = true }
        }
    }
}

/** Connection wrapper that removes itself from the tracker on close. */
private class TrackedConnection(
    private val delegate: Connection,
    private val onClose: () -> Unit,
) : Connection by delegate {
    override fun close() {
        try {
            delegate.close()
        } finally {
            onClose()
        }
    }
}
