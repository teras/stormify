// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify

import onl.ycode.kdbc.Connection

internal actual object ActiveTxRegistry {
    private val stacks = ThreadLocal.withInitial { ArrayDeque<Entry>() }

    private class Entry(val owner: Stormify, val conn: Connection)

    actual fun push(owner: Stormify, conn: Connection) {
        stacks.get().addLast(Entry(owner, conn))
    }

    actual fun pop(owner: Stormify, conn: Connection) {
        val stack = stacks.get()
        // Fast path: the matching entry is almost always the top, since push/pop
        // is bracketed in try/finally. Fall back to a reversed scan only if the
        // top doesn't match (e.g. exception unwinding left frames out of order).
        val top = stack.lastOrNull()
        if (top != null && top.owner === owner && top.conn === conn) {
            stack.removeLast()
        } else {
            for (i in stack.indices.reversed()) {
                val e = stack[i]
                if (e.owner === owner && e.conn === conn) {
                    stack.removeAt(i)
                    break
                }
            }
        }
        if (stack.isEmpty()) stacks.remove()
    }

    actual fun currentFor(owner: Stormify): Connection? {
        val stack = stacks.get()
        // Fast path for the common case: one active transaction on this thread,
        // or nested calls all on the same Stormify instance — the top matches.
        val size = stack.size
        if (size == 0) return null
        val top = stack[size - 1]
        if (top.owner === owner) return top.conn
        for (i in size - 2 downTo 0) {
            val e = stack[i]
            if (e.owner === owner) return e.conn
        }
        return null
    }
}
