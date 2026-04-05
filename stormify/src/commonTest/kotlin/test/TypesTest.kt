package test

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import com.ionspin.kotlin.bignum.integer.BigInteger
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

    @Test
    fun testBigDecimalPrecision() = withDb("BIGDECIMAL") { s ->
        TestDDL.dropTable("bd_test")
        // precision 38 = Oracle's max NUMBER; scale 10 leaves 28 integer digits.
        s.executeUpdate(TestDDL.createTable("bd_test",
            "${TestDDL.intPrimaryKey("id")}, val ${TestDDL.decimalType(38, 10)}"))

        // Values chosen to stress different paths:
        //  - small values that DOUBLE would mangle (0.1 binary representation)
        //  - 18-digit integer (Snowflake-class ID, within Long but beyond Float precision)
        //  - value just above Long.MAX_VALUE that forces the string-bind path
        //  - 28-digit integer (well beyond Long, exercises full NUMBER precision)
        //  - negative high-precision
        val values = listOf(
            "0.1000000000",
            "123456789012345678.0000000000",                // 18-digit integer
            "9223372036854775808.0000000000",               // Long.MAX + 1
            "1234567890123456789012345678.0000000000",      // 28-digit integer
            "-99999999999999999999.1234567890",
            "0.0000000001",                                  // tiny fraction
        )
        for ((i, str) in values.withIndex()) {
            val id = 10 + i
            val bd = BigDecimal.parseString(str)
            s.executeUpdate("INSERT INTO bd_test (id, val) VALUES (?, ?)", id, bd)
            val back = s.readOne<BigDecimal>("SELECT val FROM bd_test WHERE id = ?", id)
            assertNotNull(back, "no row for $str")
            assertEquals(0, bd.compareTo(back),
                "precision lost for $str: expected $bd, got $back")
        }
    }

    @Test
    fun testBigIntegerBeyondLong() = withDb("BIGINT-XL") { s ->
        TestDDL.dropTable("bi_test")
        s.executeUpdate(TestDDL.createTable("bi_test",
            "${TestDDL.intPrimaryKey("id")}, val ${TestDDL.decimalType(38, 0)}"))

        val values = listOf(
            BigInteger.parseString("9223372036854775807"),            // Long.MAX
            BigInteger.parseString("9223372036854775808"),            // Long.MAX + 1
            BigInteger.parseString("99999999999999999999999999999999999999"), // 38-digit max
            BigInteger.parseString("-9223372036854775808"),           // Long.MIN
            BigInteger.parseString("-99999999999999999999999999999999999999"), // -38-digit max
        )
        for ((i, bi) in values.withIndex()) {
            val id = 20 + i
            s.executeUpdate("INSERT INTO bi_test (id, val) VALUES (?, ?)", id, bi)
            val back = s.readOne<BigInteger>("SELECT val FROM bi_test WHERE id = ?", id)
            assertNotNull(back, "no row for $bi")
            assertEquals(bi, back, "BigInteger round-trip failed")
        }
    }

    @Test
    fun testLargeBlobs() = withDb("LARGE-BLOB") { s ->
        TestDDL.dropTable("blob_test")
        s.executeUpdate(TestDDL.createTable("blob_test",
            "${TestDDL.intPrimaryKey("id")}, blob_data ${TestDDL.blobType()}, clob_as_chars ${TestDDL.textType()}, clob_as_string ${TestDDL.textType()}"))

        // Exercise different size buckets: below/around/above the typical VARBINARY
        // inline-vs-LOB threshold (8000 bytes on SQL Server) and well into MB range.
        val sizes = listOf(1_024, 7_999, 8_000, 8_001, 100_000, 1_048_576, 10_485_760)
        for ((i, size) in sizes.withIndex()) {
            val id = 100 + i
            // Deterministic non-repeating pattern so any single-byte corruption is
            // detected by contentEquals below. Covers full 0..255 range.
            val data = ByteArray(size) { idx -> ((idx * 31 + 7) and 0xFF).toByte() }
            s.create(BlobEntity(id = id, blobData = data))
            val found = s.findById<BlobEntity>(id)!!
            assertEquals(size, found.blobData!!.size, "size for $size-byte blob")
            assertTrue(data.contentEquals(found.blobData!!), "content for $size-byte blob")
        }
    }
}
