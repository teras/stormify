package onl.ycode.stormify;

// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

import onl.ycode.stormify.pojos.*;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Month;

import static onl.ycode.stormify.StormifyManager.stormify;
import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TypesTest extends BaseDbTest {

    @Test
    @Order(1)
    void testNumericTypes() {
        StormifyManager s = stormify();
        TestDDL.dropTable("all_types");
        s.executeUpdate(TestDDL.createTable("all_types",
                TestDDL.intPrimaryKey("id") +
                        ", byte_val SMALLINT, short_val SMALLINT, int_val INT, long_val BIGINT" +
                        ", float_val REAL, double_val DOUBLE PRECISION" +
                        ", bool_val SMALLINT, string_val " + TestDDL.textType() +
                        ", big_decimal_val DECIMAL(20,5), big_integer_val DECIMAL(20,0)"));

        AllTypesEntity e = new AllTypesEntity();
        e.setId(1);
        e.setByteVal((byte) 42);
        e.setShortVal((short) 1000);
        e.setIntVal(123456);
        e.setLongVal(9876543210L);
        e.setFloatVal(3.14f);
        e.setDoubleVal(2.718281828);
        e.setBoolVal(true);
        e.setStringVal("hello");
        e.setBigDecimalVal(new BigDecimal("12345.67890"));
        e.setBigIntegerVal(BigInteger.valueOf(999999999999L));
        e.create();

        AllTypesEntity found = s.findById(AllTypesEntity.class, 1);
        assertEquals(42, found.getByteVal());
        assertEquals(1000, found.getShortVal());
        assertEquals(123456, found.getIntVal());
        assertEquals(9876543210L, found.getLongVal());
        assertEquals(3.14f, found.getFloatVal(), 0.01f);
        assertEquals(2.718281828, found.getDoubleVal(), 0.000001);
        assertTrue(found.isBoolVal());
        assertEquals("hello", found.getStringVal());
        assertEquals(0, new BigDecimal("12345.67890").compareTo(found.getBigDecimalVal()));
        assertEquals(BigInteger.valueOf(999999999999L), found.getBigIntegerVal());
    }

    @Test
    @Order(2)
    void testBoundaryValues() {
        StormifyManager s = stormify();

        AllTypesEntity e = new AllTypesEntity();
        e.setId(2);
        e.setIntVal(Integer.MAX_VALUE);
        e.setLongVal(Long.MAX_VALUE);
        e.setDoubleVal(Double.MAX_VALUE);
        e.setStringVal("");
        e.create();

        AllTypesEntity found = s.findById(AllTypesEntity.class, 2);
        assertEquals(Integer.MAX_VALUE, found.getIntVal());
        assertEquals(Long.MAX_VALUE, found.getLongVal());
        assertEquals("", found.getStringVal());

        // Negative values
        AllTypesEntity neg = new AllTypesEntity();
        neg.setId(3);
        neg.setIntVal(Integer.MIN_VALUE);
        neg.setLongVal(Long.MIN_VALUE);
        neg.setDoubleVal(-999.999);
        neg.create();

        AllTypesEntity foundNeg = s.findById(AllTypesEntity.class, 3);
        assertEquals(Integer.MIN_VALUE, foundNeg.getIntVal());
        assertEquals(Long.MIN_VALUE, foundNeg.getLongVal());
        assertEquals(-999.999, foundNeg.getDoubleVal(), 0.001);
    }

    @Test
    @Order(3)
    void testTimestamps() {
        StormifyManager s = stormify();
        TestDDL.dropTable("time");
        s.executeUpdate(TestDDL.createTable("time",
                TestDDL.intPrimaryKey("id") + ", time " + TestDDL.timestampType()));

        new Time(1, LocalDateTime.of(2024, Month.APRIL, 1, 12, 0, 0)).create();
        new Time2(2, LocalDate.of(2024, Month.APRIL, 1)).create();

        java.util.List<Time> times = s.findAll(Time.class, null);
        assertEquals(2, times.size());
    }

    @Test
    @Order(4)
    void testNullHandling() {
        StormifyManager s = stormify();
        TestDDL.dropTable("nullable_test");
        s.executeUpdate(TestDDL.createTable("nullable_test",
                TestDDL.intPrimaryKey("id") + ", name " + TestDDL.textType() +
                        ", nullable_int INT, nullable_string " + TestDDL.textType()));

        NullableEntity e = new NullableEntity();
        e.setId(1);
        e.setName("test");
        e.create();

        NullableEntity found = s.findById(NullableEntity.class, 1);
        assertEquals("test", found.getName());
        assertNull(found.getNullableInt());
        assertNull(found.getNullableString());

        // Update null to value
        found.setNullableInt(42);
        found.setNullableString("now set");
        found.update();
        NullableEntity updated = s.findById(NullableEntity.class, 1);
        assertEquals(42, updated.getNullableInt());
        assertEquals("now set", updated.getNullableString());

        // Update value back to null
        updated.setNullableInt(null);
        updated.setNullableString(null);
        updated.update();
        NullableEntity nulled = s.findById(NullableEntity.class, 1);
        assertNull(nulled.getNullableInt());
        assertNull(nulled.getNullableString());
    }

    @Test
    @Order(5)
    void testEmptyStringVsNull() {
        StormifyManager s = stormify();

        NullableEntity empty = new NullableEntity();
        empty.setId(10);
        empty.setName("empty_test");
        empty.setNullableString("");
        empty.create();

        NullableEntity found = s.findById(NullableEntity.class, 10);
        String val = found.getNullableString();
        assertTrue(val == null || val.isEmpty(), "Should be null or empty, got: " + val);
    }

    @Test
    @Order(6)
    void testUnicodeAndSpecialChars() {
        StormifyManager s = stormify();
        TestDDL.dropTable("unicode_test");
        s.executeUpdate(TestDDL.createTable("unicode_test",
                TestDDL.intPrimaryKey("id") + ", value " + TestDDL.textType()));

        s.executeUpdate("INSERT INTO unicode_test (id, value) VALUES (?, ?)", 1, "Ελληνικά");
        s.executeUpdate("INSERT INTO unicode_test (id, value) VALUES (?, ?)", 2, "日本語");
        s.executeUpdate("INSERT INTO unicode_test (id, value) VALUES (?, ?)", 3, "Emoji: 🚀🎉");
        s.executeUpdate("INSERT INTO unicode_test (id, value) VALUES (?, ?)", 4, "O'Brien");
        s.executeUpdate("INSERT INTO unicode_test (id, value) VALUES (?, ?)", 5, "Line1\nLine2\tTab");

        assertEquals("Ελληνικά", s.readOne(String.class, "SELECT value FROM unicode_test WHERE id = ?", 1));
        assertEquals("日本語", s.readOne(String.class, "SELECT value FROM unicode_test WHERE id = ?", 2));
        assertEquals("Emoji: 🚀🎉", s.readOne(String.class, "SELECT value FROM unicode_test WHERE id = ?", 3));
        assertEquals("O'Brien", s.readOne(String.class, "SELECT value FROM unicode_test WHERE id = ?", 4));
        assertEquals("Line1\nLine2\tTab", s.readOne(String.class, "SELECT value FROM unicode_test WHERE id = ?", 5));
    }

    @Test
    @Order(7)
    void testLargeStrings() {
        StormifyManager s = stormify();

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10000; i++) sb.append("X");
        String large = sb.toString();

        s.executeUpdate("INSERT INTO unicode_test (id, value) VALUES (?, ?)", 100, large);
        String found = s.readOne(String.class, "SELECT value FROM unicode_test WHERE id = ?", 100);
        assertEquals(10000, found.length());
    }

    @Test
    @Order(8)
    void testCustomTypeConversion() {
        StormifyManager s = stormify();

        TypeUtils.registerConversion(String.class, Integer.class, str -> Integer.parseInt((String) str));

        TestDDL.dropTable("conversion_test");
        s.executeUpdate(TestDDL.createTable("conversion_test",
                TestDDL.intPrimaryKey("id") + ", value " + TestDDL.textType()));
        s.executeUpdate("INSERT INTO conversion_test (id, value) VALUES (?, ?)", 1, "42");

        Integer val = s.readOne(Integer.class, "SELECT value FROM conversion_test WHERE id = ?", 1);
        assertEquals(42, val);
    }

    @Test
    @Order(9)
    void testBlobAndClob() {
        StormifyManager s = stormify();
        TestDDL.dropTable("blob_test");
        s.executeUpdate(TestDDL.createTable("blob_test",
                TestDDL.intPrimaryKey("id") +
                        ", blob_data BLOB" +
                        ", clob_as_chars " + TestDDL.textType() +
                        ", clob_as_string " + TestDDL.textType()));

        // All three in one entity: byte[] BLOB, char[] CLOB, String CLOB
        byte[] binaryData = {0, 1, 2, (byte) 255, 127, -128, 42};
        char[] charData = "Hello char[] CLOB Ελληνικά".toCharArray();
        String stringData = "Hello String CLOB 日本語";

        BlobEntity e = new BlobEntity();
        e.setId(1);
        e.setBlobData(binaryData);
        e.setClobAsChars(charData);
        e.setClobAsString(stringData);
        e.create();

        BlobEntity found = s.findById(BlobEntity.class, 1);
        assertArrayEquals(binaryData, found.getBlobData());
        assertArrayEquals(charData, found.getClobAsChars());
        assertEquals(stringData, found.getClobAsString());

        // All null
        BlobEntity nullEntity = new BlobEntity();
        nullEntity.setId(2);
        nullEntity.create();

        BlobEntity foundNull = s.findById(BlobEntity.class, 2);
        assertNull(foundNull.getBlobData());
        assertNull(foundNull.getClobAsChars());
        assertNull(foundNull.getClobAsString());
    }
}
