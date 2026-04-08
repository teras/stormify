package test

import onl.ycode.stormify.Stormify
import kotlin.test.*

class EnumTest {
    private fun withDb(name: String, test: (Stormify) -> Unit) = TestHelper.withDb(name, test)

    @Test
    fun testEnumCrud() = withDb("ENUM-CRUD") { s ->
        TestDDL.dropTable("enum_test")
        s.executeUpdate(
            TestDDL.createTable(
                "enum_test",
                "${TestDDL.intPrimaryKey("id")}, ${TestDDL.intColumn("plain_status")}, ${TestDDL.intColumn("custom_status")}"
            )
        )

        // Create with enum values
        s.create(EnumEntity(1, PlainStatus.ACTIVE, CustomStatus.BANNED))
        s.create(EnumEntity(2, PlainStatus.BANNED, CustomStatus.ACTIVE))
        s.create(EnumEntity(3, null, null))

        // Read back and verify
        val e1 = s.findById<EnumEntity>(1)
        assertNotNull(e1)
        assertEquals(PlainStatus.ACTIVE, e1.plainStatus)
        assertEquals(CustomStatus.BANNED, e1.customStatus)

        val e2 = s.findById<EnumEntity>(2)
        assertNotNull(e2)
        assertEquals(PlainStatus.BANNED, e2.plainStatus)
        assertEquals(CustomStatus.ACTIVE, e2.customStatus)

        val e3 = s.findById<EnumEntity>(3)
        assertNotNull(e3)
        assertNull(e3.plainStatus)
        assertNull(e3.customStatus)

        // Update
        e1.plainStatus = PlainStatus.INACTIVE
        e1.customStatus = CustomStatus.INACTIVE
        s.update(e1)
        val updated = s.findById<EnumEntity>(1)
        assertNotNull(updated)
        assertEquals(PlainStatus.INACTIVE, updated.plainStatus)
        assertEquals(CustomStatus.INACTIVE, updated.customStatus)
    }

    @Test
    fun testEnumDbValues() = withDb("ENUM-DBVALUES") { s ->
        TestDDL.dropTable("enum_test")
        s.executeUpdate(
            TestDDL.createTable(
                "enum_test",
                "${TestDDL.intPrimaryKey("id")}, ${TestDDL.intColumn("plain_status")}, ${TestDDL.intColumn("custom_status")}"
            )
        )

        // Verify ordinal storage for plain enum
        s.create(EnumEntity(1, PlainStatus.BANNED, null))
        val ordinalValue = s.readOne<Int>("SELECT plain_status FROM enum_test WHERE id = ?", 1)
        assertEquals(2, ordinalValue) // BANNED is ordinal 2

        // Verify custom dbValue storage
        s.create(EnumEntity(2, null, CustomStatus.BANNED))
        val customValue = s.readOne<Int>("SELECT custom_status FROM enum_test WHERE id = ?", 2)
        assertEquals(99, customValue) // BANNED has dbValue 99
    }

    @Test
    fun testEnumAsQueryParam() = withDb("ENUM-QUERY-PARAM") { s ->
        TestDDL.dropTable("enum_test")
        s.executeUpdate(
            TestDDL.createTable(
                "enum_test",
                "${TestDDL.intPrimaryKey("id")}, ${TestDDL.intColumn("plain_status")}, ${TestDDL.intColumn("custom_status")}"
            )
        )

        s.create(EnumEntity(1, PlainStatus.ACTIVE, CustomStatus.ACTIVE))
        s.create(EnumEntity(2, PlainStatus.INACTIVE, CustomStatus.INACTIVE))
        s.create(EnumEntity(3, PlainStatus.BANNED, CustomStatus.BANNED))

        // Single enum param
        val active = s.read<EnumEntity>("SELECT * FROM enum_test WHERE plain_status = ?", PlainStatus.ACTIVE)
        assertEquals(1, active.size)
        assertEquals(1, active[0].id)

        // Enum in list (IN clause)
        val filtered = s.read<EnumEntity>(
            "SELECT * FROM enum_test WHERE plain_status IN ? ORDER BY id",
            listOf(PlainStatus.ACTIVE, PlainStatus.BANNED)
        )
        assertEquals(2, filtered.size)
        assertEquals(1, filtered[0].id)
        assertEquals(3, filtered[1].id)

        // Custom enum param
        val banned = s.read<EnumEntity>("SELECT * FROM enum_test WHERE custom_status = ?", CustomStatus.BANNED)
        assertEquals(1, banned.size)
        assertEquals(3, banned[0].id)
    }

    @Test
    fun testEnumUnknownOrdinalNullable() = withDb("ENUM-UNKNOWN-NULLABLE") { s ->
        TestDDL.dropTable("enum_test")
        s.executeUpdate(
            TestDDL.createTable(
                "enum_test",
                "${TestDDL.intPrimaryKey("id")}, ${TestDDL.intColumn("plain_status")}, ${TestDDL.intColumn("custom_status")}"
            )
        )

        // Insert a value that doesn't correspond to any enum entry
        s.executeUpdate("INSERT INTO enum_test (id, plain_status, custom_status) VALUES (?, ?, ?)", 1, 99, 999)

        // Nullable field should get null for unknown ordinal
        val entity = s.findById<EnumEntity>(1)
        assertNotNull(entity)
        assertNull(entity.plainStatus)
        assertNull(entity.customStatus)
    }

    @Test
    fun testEnumUnknownOrdinalNonNull() = withDb("ENUM-UNKNOWN-NONNULL") { s ->
        TestDDL.dropTable("enum_notnull_test")
        s.executeUpdate(
            TestDDL.createTable(
                "enum_notnull_test",
                "${TestDDL.intPrimaryKey("id")}, ${TestDDL.intColumn("status")}"
            )
        )

        // Insert a value that doesn't correspond to any enum entry
        s.executeUpdate("INSERT INTO enum_notnull_test (id, status) VALUES (?, ?)", 1, 99)

        // Non-null field should throw
        assertFailsWith<Exception> {
            s.findById<EnumNotNullEntity>(1)
        }
    }

    @Test
    fun testEnumAsString() = withDb("ENUM-AS-STRING") { s ->
        TestDDL.dropTable("enum_string_test")
        s.executeUpdate(
            TestDDL.createTable(
                "enum_string_test",
                "${TestDDL.intPrimaryKey("id")}, status ${TestDDL.textType()}, ${TestDDL.intColumn("priority")}"
            )
        )

        // Create with mixed string/ordinal fields
        s.create(EnumStringEntity(1, PlainStatus.BANNED, PlainStatus.BANNED))

        // Verify string storage
        val statusVal = s.readOne<String>("SELECT status FROM enum_string_test WHERE id = ?", 1)
        assertEquals("BANNED", statusVal)

        // Verify ordinal storage for comparison
        val priorityVal = s.readOne<Int>("SELECT priority FROM enum_string_test WHERE id = ?", 1)
        assertEquals(2, priorityVal)  // BANNED ordinal

        // Read back entity
        val entity = s.findById<EnumStringEntity>(1)
        assertNotNull(entity)
        assertEquals(PlainStatus.BANNED, entity.status)
        assertEquals(PlainStatus.BANNED, entity.priority)

        // Update
        entity.status = PlainStatus.ACTIVE
        entity.priority = PlainStatus.ACTIVE
        s.update(entity)
        val updated = s.findById<EnumStringEntity>(1)!!
        assertEquals(PlainStatus.ACTIVE, updated.status)
        assertEquals(PlainStatus.ACTIVE, updated.priority)

        // Verify updated values in DB
        assertEquals("ACTIVE", s.readOne<String>("SELECT status FROM enum_string_test WHERE id = ?", 1))
        assertEquals(0, s.readOne<Int>("SELECT priority FROM enum_string_test WHERE id = ?", 1))

        // Null handling
        s.create(EnumStringEntity(2, null, null))
        val nullEntity = s.findById<EnumStringEntity>(2)!!
        assertNull(nullEntity.status)
        assertNull(nullEntity.priority)
    }
}
