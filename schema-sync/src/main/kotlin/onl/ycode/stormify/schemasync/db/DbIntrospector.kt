package onl.ycode.stormify.schemasync.db

import onl.ycode.stormify.Stormify
import onl.ycode.stormify.schemasync.model.ColumnRef
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream

/** Reads the target schema via [Stormify]. All dialect-specific branches
 *  are routed through [Dialect]; this class never compares dialects.
 *
 *  When constructed via [connect], holds a reference to the underlying
 *  [DriverManagerDataSource] so the TUI cancel path can abort an
 *  in-flight introspection through [cancelInflight]. */
class DbIntrospector internal constructor(
    private val stormify: Stormify,
    private val cancelHook: (() -> Unit)? = null,
) {

    constructor(stormify: Stormify) : this(stormify, cancelHook = null)

    val dialect: Dialect = Dialect.detect(stormify)

    /** Best-effort cancellation: closes any JDBC connections held by the
     *  data source, raising on any in-flight statement so the worker
     *  thread exits. No-op when the introspector was built from an
     *  externally-supplied [Stormify]. */
    fun cancelInflight() = cancelHook?.invoke() ?: Unit

    data class TableId(val schema: String?, val name: String, val kind: Kind = Kind.TABLE) {
        val key: String = listOfNotNull(schema, name).joinToString(".")
        enum class Kind { TABLE, VIEW }
    }

    /** All user tables AND views in the database. Views are surfaced too because
     *  many JPA / stormify entities are mapped to views rather than tables — the
     *  diff would otherwise flag every view-mapped entity as `entity-only`. */
    fun listTables(): List<TableId> = timed("listTables") {
        dialect.listTablesViaDictionary(stormify)
            ?: throw IllegalStateException("Dialect $dialect has no introspection support")
    }

    /** All columns in user tables. Deterministic-type columns (Boolean, Date, UUID, …)
     *  carry `category = null`; classifier UI filters them. */
    fun listColumns(onProgress: ((Int, Int) -> Unit)? = null): List<ColumnRef> = timed("listColumns") {
        val tables = listTables()
        onProgress?.invoke(0, tables.size)
        val cols = dialect.listColumnsViaDictionary(stormify, tables, onProgress)
            ?: throw IllegalStateException("Dialect $dialect has no introspection support")
        onProgress?.invoke(tables.size, tables.size)
        decorateWithFks(cols, tables)
    }

    /** Read all FK edges via the dialect's bulk catalog query. */
    private fun readFks(@Suppress("UNUSED_PARAMETER") tables: List<TableId>): List<FkEdge> = timed("readFks") {
        dialect.listForeignKeys(stormify) ?: emptyList()
    }

    private fun decorateWithFks(cols: List<ColumnRef>, tables: List<TableId>): List<ColumnRef> {
        val edges = readFks(tables)
        if (edges.isEmpty()) return cols
        val byKey: Map<Triple<String, String, String>, FkEdge> = edges
            .associateBy { Triple(it.schema ?: "", it.table.lowercase(), it.column.lowercase()) }
        return cols.map { c ->
            val edge = byKey[Triple(c.schema ?: "", c.table.lowercase(), c.name.lowercase())] ?: return@map c
            c.copy(referencedTable = edge.refTable, referencedColumn = edge.refColumn)
        }
    }

    private inline fun <T> timed(label: String, block: () -> T): T {
        if (!TIMING_ENABLED) return block()
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            val ms = (System.nanoTime() - start) / 1_000_000
            timingOut.println("[schema-sync timing] ${dialect.name.lowercase()}.$label: ${ms} ms")
            timingOut.flush()
        }
    }

    companion object {
        /** Enable per-introspection-step timing logs by setting
         *  `SCHEMA_SYNC_TIMING=1`. Output goes directly to the underlying
         *  stderr file descriptor, bypassing the TUI's `System.err`
         *  capture, so it shows in the terminal that launched the app. */
        private val TIMING_ENABLED: Boolean =
            System.getenv("SCHEMA_SYNC_TIMING")?.let { it == "1" || it.equals("true", ignoreCase = true) } == true

        private val timingOut: PrintStream by lazy {
            PrintStream(FileOutputStream(FileDescriptor.err), true, Charsets.UTF_8)
        }

        /** Convenience constructor: builds a [DbIntrospector] over a
         *  `DriverManager`-backed [Stormify] for the given coordinates. JDBC
         *  driver discovery happens through the standard SPI mechanism — the
         *  fatJar is built with merged `META-INF/services/java.sql.Driver`
         *  entries so every shipped driver is registered automatically. */
        fun connect(url: String, user: String?, password: String?): DbIntrospector {
            val ds = DriverManagerDataSource(url, user, password)
            return DbIntrospector(Stormify(ds), cancelHook = { ds.closeAll() })
        }
    }
}
