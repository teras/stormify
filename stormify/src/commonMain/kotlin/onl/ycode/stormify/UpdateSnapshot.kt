// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import onl.ycode.kdbc.Connection

/**
 * The values an entity's updatable columns had when it was loaded (or last updated),
 * in the order of [TableInfo]'s updatable columns. Taken only when
 * [Stormify.dynamicUpdate] is on; [Stormify.update] compares against it and writes
 * only the columns that differ.
 */
internal class UpdateSnapshot(val info: TableInfo<*>, val values: Array<Any?>, val ids: Array<Any?>)

/**
 * Returns a copy of [value] that later in-place changes of the entity's value cannot
 * reach: mutable values (byte arrays, and on the JVM `java.util.Date` and `Calendar`)
 * are copied, immutable ones are returned as they are.
 */
internal fun snapshotCopy(value: Any?): Any? = when (value) {
    null -> null
    is ByteArray -> value.copyOf()
    else -> platformSnapshotCopy(value)
}

/** Copies the platform's mutable value types; returns other values as they are. */
internal expect fun platformSnapshotCopy(value: Any): Any

/** Whether [current] still equals the snapshot value [saved]; byte arrays compare by content. */
internal fun sameSnapshotValue(current: Any?, saved: Any?): Boolean =
    if (current is ByteArray && saved is ByteArray) current.contentEquals(saved) else current == saved

/**
 * The entities whose [UpdateSnapshot] was replaced by an update inside a still open
 * transaction, per transaction connection. The new snapshot describes values that are
 * only in the database once the transaction commits: on rollback the snapshots are
 * dropped, so the next update of these entities writes every column again. Every
 * transaction registers here, whatever [Stormify.dynamicUpdate] says when it starts.
 */
internal class TxSnapshotLog {
    private val lock = reentrantLock()
    private val open = HashMap<Connection, MutableList<StormifyEntity>>()

    /** A transaction starts on [conn]. */
    fun begin(conn: Connection) = lock.withLock { open[conn] = mutableListOf() }

    /** The transaction on [conn] committed: its snapshots stand. */
    fun commit(conn: Connection) = lock.withLock { open.remove(conn) }

    /** The transaction on [conn] rolled back: drops the snapshots it replaced. */
    fun rollback(conn: Connection) {
        val entities = lock.withLock { open.remove(conn) } ?: return
        for (entity in entities) entity._updateSnapshot = null
    }

    /** Position to roll back to for a savepoint opened now on [conn], or -1 outside a transaction. */
    fun mark(conn: Connection): Int = lock.withLock { open[conn]?.size ?: -1 }

    /** A savepoint opened at [mark] rolled back: drops the snapshots replaced since. */
    fun rollbackTo(conn: Connection, mark: Int) {
        if (mark < 0) return
        val entities = lock.withLock {
            val list = open[conn] ?: return
            if (mark >= list.size) return
            val since = list.subList(mark, list.size)
            val copy = since.toList()
            since.clear()
            copy
        }
        for (entity in entities) entity._updateSnapshot = null
    }

    /**
     * Records that [entity] got a new snapshot through [conn]. Returns `false` when no
     * transaction of this instance is open on [conn] (the statement ran in auto-commit).
     */
    fun record(conn: Connection, entity: StormifyEntity): Boolean =
        lock.withLock { open[conn]?.add(entity) ?: false }
}
