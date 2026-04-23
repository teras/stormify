// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify

import onl.ycode.kdbc.Connection

private class Entry(val owner: Stormify, val conn: Connection)

// Under the Kotlin/Native new memory model, @ThreadLocal on a top-level mutable
// property gives each thread its own instance, isolated from other threads —
// same semantics as java.lang.ThreadLocal on the JVM.
@kotlin.native.concurrent.ThreadLocal
private val stack: ArrayDeque<Entry> = ArrayDeque()

internal actual object ActiveTxRegistry {
    actual fun push(owner: Stormify, conn: Connection) {
        stack.addLast(Entry(owner, conn))
    }

    actual fun pop(owner: Stormify, conn: Connection) {
        val top = stack.lastOrNull()
        if (top != null && top.owner === owner && top.conn === conn) {
            stack.removeLast()
            return
        }
        for (i in stack.indices.reversed()) {
            val e = stack[i]
            if (e.owner === owner && e.conn === conn) {
                stack.removeAt(i)
                return
            }
        }
    }

    actual fun currentFor(owner: Stormify): Connection? {
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
