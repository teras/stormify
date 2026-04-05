package test

import onl.ycode.stormify.Stormify
import kotlin.test.*

class TypesTest {
    private fun withDb(name: String, test: (Stormify) -> Unit) = TestHelper.withDb(name, test)

    @Test
    fun testNumericTypes() = withDb("NUMERIC") { s ->
        TestDDL.dropTable("all_types")
        s.executeUpdate(TestDDL.createTable("all_types",
            "${TestDDL.intPrimaryKey("id")}, byte_val ${TestDDL.smallIntType()}, short_val ${TestDDL.smallIntType()}, " +
                    "int_val ${TestDDL.intType()}, long_val ${TestDDL.bigIntType()}, " +
                    "float_val ${TestDDL.floatType()}, double_val ${TestDDL.doubleType()}, " +
                    "bool_val ${TestDDL.smallIntType()}, string_val ${TestDDL.textType()}"))

        val e = AllTypesEntity(id = 1, byteVal = 42, shortVal = 1000, intVal = 123456,
            longVal = 9876543210L, floatVal = 3.14f, doubleVal = 2.718281828,
            boolVal = true, stringVal = "hello")
        s.create(e)

        val found = s.findById<AllTypesEntity>(1)!!
        assertEquals(42.toByte(), found.byteVal)
        assertEquals(1000.toShort(), found.shortVal)
        assertEquals(123456, found.intVal)
        assertEquals(9876543210L, found.longVal)
        assertEquals(3.14f, found.floatVal, 0.01f)
        assertEquals(2.718281828, found.doubleVal, 0.000001)
        assertTrue(found.boolVal)
        assertEquals("hello", found.stringVal)
    }

    @Test
    fun testBoundaryValues() = withDb("BOUNDARY") { s ->
        TestDDL.dropTable("all_types")
        s.executeUpdate(TestDDL.createTable("all_types",
            "${TestDDL.intPrimaryKey("id")}, byte_val ${TestDDL.smallIntType()}, short_val ${TestDDL.smallIntType()}, " +
                    "int_val ${TestDDL.intType()}, long_val ${TestDDL.bigIntType()}, " +
                    "float_val ${TestDDL.floatType()}, double_val ${TestDDL.doubleType()}, " +
                    "bool_val ${TestDDL.smallIntType()}, string_val ${TestDDL.textType()}"))
        s.create(AllTypesEntity(id = 2, intVal = Int.MAX_VALUE, longVal = Long.MAX_VALUE, stringVal = ""))
        val found = s.findById<AllTypesEntity>(2)!!
        assertEquals(Int.MAX_VALUE, found.intVal)
        assertEquals(Long.MAX_VALUE, found.longVal)
        // Oracle stores empty strings as NULL, so we accept either — dedicated
        // empty-string semantics live in testEmptyStringVsNull.
        val sv = found.stringVal
        assertTrue(sv == null || sv.isEmpty(), "Expected null or empty, got: $sv")

        s.create(AllTypesEntity(id = 3, intVal = Int.MIN_VALUE, longVal = Long.MIN_VALUE, doubleVal = -999.999))
        val neg = s.findById<AllTypesEntity>(3)!!
        assertEquals(Int.MIN_VALUE, neg.intVal)
        assertEquals(Long.MIN_VALUE, neg.longVal)
        assertEquals(-999.999, neg.doubleVal, 0.001)
    }

    @Test
    fun testNullHandling() = withDb("NULL") { s ->
        TestDDL.dropTable("nullable_test")
        s.executeUpdate(TestDDL.createTable("nullable_test",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}, nullable_int INT, nullable_string ${TestDDL.textType()}"))

        s.create(NullableEntity(id = 1, name = "test"))
        val found = s.findById<NullableEntity>(1)!!
        assertEquals("test", found.name)
        assertNull(found.nullableInt)
        assertNull(found.nullableString)

        found.nullableInt = 42; found.nullableString = "now set"
        s.update(found)
        val updated = s.findById<NullableEntity>(1)!!
        assertEquals(42, updated.nullableInt)
        assertEquals("now set", updated.nullableString)

        updated.nullableInt = null; updated.nullableString = null
        s.update(updated)
        val nulled = s.findById<NullableEntity>(1)!!
        assertNull(nulled.nullableInt)
        assertNull(nulled.nullableString)
    }

    @Test
    fun testEmptyStringVsNull() = withDb("EMPTY-VS-NULL") { s ->
        TestDDL.dropTable("nullable_test")
        s.executeUpdate(TestDDL.createTable("nullable_test",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}, nullable_int INT, nullable_string ${TestDDL.textType()}"))

        s.create(NullableEntity(id = 10, name = "empty_test", nullableString = ""))
        val found = s.findById<NullableEntity>(10)!!
        val v = found.nullableString
        assertTrue(v == null || v.isEmpty(), "Should be null or empty, got: $v")
    }

    @Test
    fun testUnicodeAndSpecialChars() = withDb("UNICODE") { s ->
        TestDDL.dropTable("unicode_test")
        s.executeUpdate(TestDDL.createTable("unicode_test",
            "${TestDDL.intPrimaryKey("id")}, value ${TestDDL.textType()}"))

        s.executeUpdate("INSERT INTO unicode_test (id, value) VALUES (?, ?)", 1, "Ελληνικά")
        s.executeUpdate("INSERT INTO unicode_test (id, value) VALUES (?, ?)", 2, "日本語")
        s.executeUpdate("INSERT INTO unicode_test (id, value) VALUES (?, ?)", 3, "O'Brien")

        assertEquals("Ελληνικά", s.readOne<String>("SELECT value FROM unicode_test WHERE id = ?", 1))
        assertEquals("日本語", s.readOne<String>("SELECT value FROM unicode_test WHERE id = ?", 2))
        assertEquals("O'Brien", s.readOne<String>("SELECT value FROM unicode_test WHERE id = ?", 3))
    }

    @Test
    fun testLargeStrings() = withDb("LARGE-STRINGS") { s ->
        TestDDL.dropTable("large_test")
        s.executeUpdate(TestDDL.createTable("large_test",
            "${TestDDL.intPrimaryKey("id")}, value ${TestDDL.largeTextType()}"))

        val large = "X".repeat(10000)
        s.executeUpdate("INSERT INTO large_test (id, value) VALUES (?, ?)", 1, large)
        val found = s.readOne<String>("SELECT value FROM large_test WHERE id = ?", 1)!!
        assertEquals(10000, found.length)
    }

    @Test
    fun testCustomTypeConversion() = withDb("CUSTOM-CONV") { s ->
        onl.ycode.stormify.TypeUtils.register(String::class, Int::class) { (it as String).toInt() }

        TestDDL.dropTable("conversion_test")
        s.executeUpdate(TestDDL.createTable("conversion_test",
            "${TestDDL.intPrimaryKey("id")}, value ${TestDDL.textType()}"))
        s.executeUpdate("INSERT INTO conversion_test (id, value) VALUES (?, ?)", 1, "42")

        val v = s.readOne<Int>("SELECT value FROM conversion_test WHERE id = ?", 1)
        assertEquals(42, v)
    }

    @Test
    fun testBlobAndClob() = withDb("BLOB-CLOB") { s ->
        TestDDL.dropTable("blob_test")
        s.executeUpdate(TestDDL.createTable("blob_test",
            "${TestDDL.intPrimaryKey("id")}, blob_data ${TestDDL.blobType()}, clob_as_chars ${TestDDL.textType()}, clob_as_string ${TestDDL.textType()}"))

        val binaryData = byteArrayOf(0, 1, 2, -1, 127, -128, 42)
        val charData = "Hello char[] CLOB Ελληνικά".toCharArray()
        val stringData = "Hello String CLOB 日本語"

        s.create(BlobEntity(id = 1, blobData = binaryData, clobAsChars = charData, clobAsString = stringData))
        val found = s.findById<BlobEntity>(1)!!
        assertTrue(binaryData.contentEquals(found.blobData!!))
        assertTrue(charData.contentEquals(found.clobAsChars!!))
        assertEquals(stringData, found.clobAsString)

        // Null
        s.create(BlobEntity(id = 2))
        val nulled = s.findById<BlobEntity>(2)!!
        assertNull(nulled.blobData)
        assertNull(nulled.clobAsChars)
        assertNull(nulled.clobAsString)
    }
}
