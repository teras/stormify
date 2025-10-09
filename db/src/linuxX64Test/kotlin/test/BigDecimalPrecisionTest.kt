package test

import kotlin.test.*
import com.ionspin.kotlin.bignum.decimal.BigDecimal as BDN
import com.ionspin.kotlin.bignum.integer.BigInteger as BIN

/**
 * Tests for BigDecimal precision preservation across all database drivers.
 *
 * These tests verify that BigDecimal values maintain full precision when:
 * 1. Sent to database (no Double conversion)
 * 2. Stored in database (proper NUMERIC/DECIMAL handling)
 * 3. Retrieved from database (proper decoding)
 *
 * Driver-specific implementations:
 * - **PostgreSQL**: Uses NUMERIC binary format (base-10000, network byte order)
 * - **MariaDB/MySQL**: Uses string format (DECIMAL sent as strings in binary protocol)
 * - **Oracle**: Uses NUMBER binary format (base-100, SQLT_VNU)
 *
 * NOTE: These tests are currently disabled because they require:
 * 1. Running database instances (PostgreSQL, MySQL/MariaDB, Oracle)
 * 2. Stormify setup with proper table definitions
 * 3. Integration test infrastructure
 *
 * TODO: Enable when integration testing is set up.
 */
@Ignore("BigDecimal precision tests - requires database instances and Stormify setup")
class BigDecimalPrecisionTest {

    /**
     * Test that 18-digit integer IDs (like Snowflake IDs) preserve full precision.
     *
     * These are common in distributed systems:
     * - Twitter Snowflake IDs
     * - Discord IDs
     * - Other distributed ID generators
     *
     * Double can only represent ~15-17 digits accurately, so this test
     * would fail if drivers incorrectly convert to Double.
     */
    @Test
    fun testLargeIntegerId_18Digits() {
        // val id = BDN.parseString("123456789012345678") // 18 digits
        //
        // stormify.transaction {
        //     val entity = TestEntity(id = id)
        //     val created = create(entity)
        //
        //     val retrieved = find(TestEntity::class, id)
        //     assertNotNull(retrieved)
        //     assertEquals(id.toString(), retrieved.id.toString(), "18-digit ID should preserve all digits")
        // }
    }

    /**
     * Test that financial amounts with many decimal places preserve precision.
     *
     * Financial calculations require exact precision - even small errors
     * compound over many transactions.
     */
    @Test
    fun testFinancialPrecision_9DecimalPlaces() {
        // val amount = BDN.parseString("1234567890.123456789") // 9 decimal places
        //
        // stormify.transaction {
        //     val entity = TestEntity(amount = amount)
        //     val created = create(entity)
        //
        //     val retrieved = find(TestEntity::class, created.id)
        //     assertNotNull(retrieved)
        //     assertEquals(amount.toString(), retrieved.amount.toString(), "Financial amount should preserve 9 decimal places")
        // }
    }

    /**
     * Test high-precision scientific values.
     *
     * Scientific calculations (physics, chemistry, astronomy) often require
     * precision beyond what Double provides.
     */
    @Test
    fun testScientificPrecision_20Digits() {
        // val pi = BDN.parseString("3.14159265358979323846") // 20 digits
        //
        // stormify.transaction {
        //     val entity = TestEntity(scientificValue = pi)
        //     val created = create(entity)
        //
        //     val retrieved = find(TestEntity::class, created.id)
        //     assertNotNull(retrieved)
        //     assertEquals(pi.toString(), retrieved.scientificValue.toString(), "Scientific value should preserve 20 digits")
        // }
    }

    /**
     * Test very small decimal values.
     *
     * Small values like probabilities, chemical concentrations, etc.
     */
    @Test
    fun testSmallDecimal_ManyLeadingZeros() {
        // val small = BDN.parseString("0.00000000123456789") // 9 significant digits after 8 zeros
        //
        // stormify.transaction {
        //     val entity = TestEntity(value = small)
        //     val created = create(entity)
        //
        //     val retrieved = find(TestEntity::class, created.id)
        //     assertNotNull(retrieved)
        //     assertEquals(small.toString(), retrieved.value.toString(), "Small decimal should preserve all significant digits")
        // }
    }

    /**
     * Test that the problematic case from Issue #4 is fixed.
     *
     * This value would lose precision if converted to Double:
     * BDN("0.1000000000000001") → Double(0.1) ❌
     */
    @Test
    fun testBugFix_Issue4_DecimalPrecision() {
        // val problematic = BDN.parseString("0.1000000000000001") // 16 decimal places
        //
        // stormify.transaction {
        //     val entity = TestEntity(value = problematic)
        //     val created = create(entity)
        //
        //     val retrieved = find(TestEntity::class, created.id)
        //     assertNotNull(retrieved)
        //
        //     // OLD BUG: Would return "0.1" due to Double conversion
        //     assertNotEquals("0.1", retrieved.value.toString(), "Should NOT lose precision to Double")
        //     assertEquals(problematic.toString(), retrieved.value.toString(), "Should preserve all 16 decimal places")
        // }
    }

    /**
     * Test that BigInteger values larger than Long are handled correctly.
     */
    @Test
    fun testBigInteger_LargerThanLong() {
        // val bigInt = BIN.parseString("9223372036854775808") // Long.MAX_VALUE + 1
        //
        // stormify.transaction {
        //     val entity = TestEntity(bigIntValue = bigInt)
        //     val created = create(entity)
        //
        //     val retrieved = find(TestEntity::class, created.id)
        //     assertNotNull(retrieved)
        //     assertEquals(bigInt.toString(), retrieved.bigIntValue.toString(), "BigInteger > Long.MAX_VALUE should work")
        // }
    }

    /**
     * Test maximum Oracle NUMBER precision (38 digits).
     */
    @Test
    fun testMaximumPrecision_38Digits_Oracle() {
        // val maxPrecision = BDN.parseString("12345678901234567890123456789012345678") // 38 digits
        //
        // // This test only makes sense for Oracle
        // assumeTrue(currentDriver == "Oracle", "Oracle-specific test")
        //
        // stormify.transaction {
        //     val entity = TestEntity(value = maxPrecision)
        //     val created = create(entity)
        //
        //     val retrieved = find(TestEntity::class, created.id)
        //     assertNotNull(retrieved)
        //     assertEquals(maxPrecision.toString(), retrieved.value.toString(), "Oracle should support 38-digit precision")
        // }
    }

    /**
     * Test PostgreSQL NUMERIC with large scale.
     * PostgreSQL supports NUMERIC(precision, scale) with precision up to 1000.
     */
    @Test
    fun testPostgresNumeric_LargeScale() {
        // val value = BDN.parseString("123.12345678901234567890") // 20 decimal places
        //
        // assumeTrue(currentDriver == "PostgreSQL", "PostgreSQL-specific test")
        //
        // stormify.transaction {
        //     val entity = TestEntity(value = value)
        //     val created = create(entity)
        //
        //     val retrieved = find(TestEntity::class, created.id)
        //     assertNotNull(retrieved)
        //     assertEquals(value.toString(), retrieved.value.toString(), "PostgreSQL NUMERIC should support large scale")
        // }
    }

    /**
     * Test MySQL DECIMAL(65, 30) - maximum precision.
     * MySQL/MariaDB supports DECIMAL(M, D) where M <= 65 and D <= 30.
     */
    @Test
    fun testMySqlDecimal_MaxPrecision() {
        // val value = BDN.parseString("12345678901234567890123456789012345.123456789012345678901234567890") // 65 total, 30 after decimal
        //
        // assumeTrue(currentDriver == "MySQL" || currentDriver == "MariaDB", "MySQL/MariaDB-specific test")
        //
        // stormify.transaction {
        //     val entity = TestEntity(value = value)
        //     val created = create(entity)
        //
        //     val retrieved = find(TestEntity::class, created.id)
        //     assertNotNull(retrieved)
        //     assertEquals(value.toString(), retrieved.value.toString(), "MySQL DECIMAL should support 65 total digits, 30 after decimal")
        // }
    }

    /**
     * Test negative BigDecimal values.
     */
    @Test
    fun testNegativeBigDecimal() {
        // val negative = BDN.parseString("-123456789.987654321")
        //
        // stormify.transaction {
        //     val entity = TestEntity(value = negative)
        //     val created = create(entity)
        //
        //     val retrieved = find(TestEntity::class, created.id)
        //     assertNotNull(retrieved)
        //     assertEquals(negative.toString(), retrieved.value.toString(), "Negative BigDecimal should preserve precision")
        // }
    }

    /**
     * Test zero with trailing zeros.
     * Different databases handle trailing zeros differently.
     */
    @Test
    fun testZeroWithTrailingZeros() {
        // val zero = BDN.parseString("0.00000")
        //
        // stormify.transaction {
        //     val entity = TestEntity(value = zero)
        //     val created = create(entity)
        //
        //     val retrieved = find(TestEntity::class, created.id)
        //     assertNotNull(retrieved)
        //
        //     // Note: Some databases may trim trailing zeros
        //     // We should get either "0.00000" or "0" back
        //     val retrievedValue = BDN.parseString(retrieved.value.toString())
        //     assertEquals(zero, retrievedValue, "Zero should be preserved (trailing zeros may vary)")
        // }
    }

    /**
     * Test that Double conversion optimization was removed.
     *
     * This is a regression test for the bug where drivers would check:
     * if (value.doubleValue() fits) use Double else use String
     *
     * That optimization violated user intent and caused precision loss.
     */
    @Test
    fun testNoDoubleOptimization() {
        // Test several values that COULD fit in Double but shouldn't be converted

        // val values = listOf(
        //     BDN.parseString("1.0"),              // Simple, would fit in Double
        //     BDN.parseString("123.456"),          // Would fit in Double
        //     BDN.parseString("0.1"),              // Famous binary representation issue
        //     BDN.parseString("1234567890123456"), // 16 digits, at edge of Double precision
        // )
        //
        // for (value in values) {
        //     stormify.transaction {
        //         val entity = TestEntity(value = value)
        //         val created = create(entity)
        //
        //         val retrieved = find(TestEntity::class, created.id)
        //         assertNotNull(retrieved)
        //         assertEquals(value.toString(), retrieved.value.toString(),
        //             "Value $value should not be converted to Double even if it 'fits'")
        //     }
        // }
    }
}
