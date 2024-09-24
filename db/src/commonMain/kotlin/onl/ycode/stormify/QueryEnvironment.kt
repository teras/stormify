// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify

internal interface QueryEnvironment<T> {
    fun execute(conn: PreparedStatement?): T
}
