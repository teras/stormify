// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package test

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The bookkeeping an entity carries for the library (the owning instance, the loaded
 * flag, the remembered dangling keys, the update snapshot) must never be mapped as a
 * column, on either discovery path. Here the metadata is the one the annotation
 * processor generated; the reflection path is checked in the JVM tests.
 */
open class BookkeepingColumnsTest {
    @Test
    fun bookkeepingIsNotAColumn() = TestHelper.withDb("BOOKKEEPING-COLUMNS") { s ->
        val columns = s.getTableInfo(AutoParentEntity::class).fieldInfos.map { it.dbName }.sorted()
        assertEquals(listOf("data", "id", "other"), columns)
    }
}
