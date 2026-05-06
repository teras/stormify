// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package test

import onl.ycode.stormify.NamingPolicy
import onl.ycode.stormify.Stormify
import kotlin.test.*

/**
 * Verifies that PK column resolution and the post-create id-write-back path use the
 * **DB column name**, not the Kotlin property name, across every PK retrieval mode
 * (manual, auto-increment, sequence) × every naming variant (default snake_case,
 * camelCase with digits, explicit @DbField name override, UPPER_CASE policy,
 * CAMEL_CASE identity policy).
 *
 * Regression target: a bug in the sequence path that called `setField(idNames[0], …)`
 * (property name) instead of `setField(idDbNames[0], …)` (column name), crashing on
 * any entity whose property name differed from its column name (e.g. `t01Id` → `t01_id`).
 */
class PrimaryKeyMappingTest {
    private fun withDb(name: String, test: (Stormify) -> Unit) = TestHelper.withDb(name, test)

    // --- Sequence path (the actual bug regression — line 675 of Stormify.kt) ---

    @Test
    fun sequencePkSimple() = withDb("PK-SEQ-SIMPLE") { s ->
        if (!TestDDL.supportsSequences()) skipTest(SkipReason.FEATURE_NA, "no CREATE SEQUENCE")
        TestDDL.dropTable("pk_simple"); TestDDL.dropSequence("pk_simple_seq")
        TestDDL.createSequence("pk_simple_seq")
        s.executeUpdate(TestDDL.createTable("pk_simple",
            "id ${TestDDL.bigIntType()} PRIMARY KEY, name ${TestDDL.textType()}"))

        assertEquals("id", s.getTableInfo(PkSimple::class).primaryKey.dbName)

        val created = s.create(PkSimple(name = "row1"))
        assertNotNull(created.id, "id must be populated by sequence")
        val found = s.findById<PkSimple>(created.id!!)
        assertNotNull(found); assertEquals("row1", found.name)

        TestDDL.dropTable("pk_simple"); TestDDL.dropSequence("pk_simple_seq")
    }

    @Test
    fun sequencePkDigitCamel() = withDb("PK-SEQ-DIGIT-CAMEL") { s ->
        if (!TestDDL.supportsSequences()) skipTest(SkipReason.FEATURE_NA, "no CREATE SEQUENCE")
        TestDDL.dropTable("pk_digit_camel"); TestDDL.dropSequence("pk_digit_camel_seq")
        TestDDL.createSequence("pk_digit_camel_seq")
        s.executeUpdate(TestDDL.createTable("pk_digit_camel",
            "t01_id ${TestDDL.bigIntType()} PRIMARY KEY, name ${TestDDL.textType()}"))

        assertEquals("t01_id", s.getTableInfo(PkDigitCamel::class).primaryKey.dbName)

        val created = s.create(PkDigitCamel(name = "row1"))
        assertNotNull(created.t01Id, "id must be populated by sequence — was the bug")
        val found = s.findById<PkDigitCamel>(created.t01Id!!)
        assertNotNull(found); assertEquals("row1", found.name)

        TestDDL.dropTable("pk_digit_camel"); TestDDL.dropSequence("pk_digit_camel_seq")
    }

    @Test
    fun sequencePkRenamed() = withDb("PK-SEQ-RENAMED") { s ->
        if (!TestDDL.supportsSequences()) skipTest(SkipReason.FEATURE_NA, "no CREATE SEQUENCE")
        TestDDL.dropTable("pk_renamed"); TestDDL.dropSequence("pk_renamed_seq")
        TestDDL.createSequence("pk_renamed_seq")
        s.executeUpdate(TestDDL.createTable("pk_renamed",
            "totally_different ${TestDDL.bigIntType()} PRIMARY KEY, name ${TestDDL.textType()}"))

        assertEquals("totally_different", s.getTableInfo(PkRenamed::class).primaryKey.dbName)

        val created = s.create(PkRenamed(name = "row1"))
        assertNotNull(created.customPk)
        val found = s.findById<PkRenamed>(created.customPk!!)
        assertNotNull(found); assertEquals("row1", found.name)

        TestDDL.dropTable("pk_renamed"); TestDDL.dropSequence("pk_renamed_seq")
    }

    @Test
    fun sequencePkUpperPolicy() = withDb("PK-SEQ-UPPER") { s ->
        if (!TestDDL.supportsSequences()) skipTest(SkipReason.FEATURE_NA, "no CREATE SEQUENCE")
        s.namingPolicy = NamingPolicy.UPPER_CASE_WITH_UNDERSCORES
        TestDDL.dropTable("PK_UPPER_POLICY"); TestDDL.dropSequence("PK_UPPER_POLICY_SEQ")
        TestDDL.createSequence("PK_UPPER_POLICY_SEQ")
        s.executeUpdate(TestDDL.createTable("PK_UPPER_POLICY",
            "USER_ID ${TestDDL.bigIntType()} PRIMARY KEY, NAME ${TestDDL.textType()}"))

        val info = s.getTableInfo(PkUpperPolicy::class)
        assertEquals("PK_UPPER_POLICY", info.tableName)
        assertEquals("USER_ID", info.primaryKey.dbName)

        val created = s.create(PkUpperPolicy(name = "row1"))
        assertNotNull(created.userId)
        val found = s.findById<PkUpperPolicy>(created.userId!!)
        assertNotNull(found); assertEquals("row1", found.name)

        TestDDL.dropTable("PK_UPPER_POLICY"); TestDDL.dropSequence("PK_UPPER_POLICY_SEQ")
    }

    @Test
    fun sequencePkCamelPolicy() = withDb("PK-SEQ-CAMEL-POLICY") { s ->
        if (!TestDDL.supportsSequences()) skipTest(SkipReason.FEATURE_NA, "no CREATE SEQUENCE")
        s.namingPolicy = NamingPolicy.CAMEL_CASE
        TestDDL.dropTable("PkCamelPolicy"); TestDDL.dropSequence("pkCamelPolicySeq")
        TestDDL.createSequence("pkCamelPolicySeq")
        s.executeUpdate(TestDDL.createTable("PkCamelPolicy",
            "userId ${TestDDL.bigIntType()} PRIMARY KEY, name ${TestDDL.textType()}"))

        val info = s.getTableInfo(PkCamelPolicy::class)
        assertEquals("PkCamelPolicy", info.tableName)
        assertEquals("userId", info.primaryKey.dbName)

        val created = s.create(PkCamelPolicy(name = "row1"))
        assertNotNull(created.userId)
        val found = s.findById<PkCamelPolicy>(created.userId!!)
        assertNotNull(found); assertEquals("row1", found.name)

        TestDDL.dropTable("PkCamelPolicy"); TestDDL.dropSequence("pkCamelPolicySeq")
    }

    @Test
    fun sequencePkBatch() = withDb("PK-SEQ-BATCH") { s ->
        if (!TestDDL.supportsSequences()) skipTest(SkipReason.FEATURE_NA, "no CREATE SEQUENCE")
        TestDDL.dropTable("pk_digit_camel"); TestDDL.dropSequence("pk_digit_camel_seq")
        TestDDL.createSequence("pk_digit_camel_seq")
        s.executeUpdate(TestDDL.createTable("pk_digit_camel",
            "t01_id ${TestDDL.bigIntType()} PRIMARY KEY, name ${TestDDL.textType()}"))

        val items = (1..3).map { PkDigitCamel(name = "row$it") }
        val created = s.create(items)
        assertEquals(3, created.size)
        for (item in created) assertNotNull(item.t01Id, "every batch item must have id populated")
        assertEquals(3, created.map { it.t01Id }.toSet().size, "ids must be unique")

        TestDDL.dropTable("pk_digit_camel"); TestDDL.dropSequence("pk_digit_camel_seq")
    }

    // --- Auto-increment path (BY_INDEX / BY_NAME generated key retrieval) ---

    @Test
    fun autoIncrementPkSimple() = withDb("PK-AI-SIMPLE") { s ->
        val ddl = TestDDL.autoIncrementPrimaryKey("id")
            ?: skipTest(SkipReason.FEATURE_NA, "no auto-increment on this dialect")
        TestDDL.dropTable("pk_simple_ai")
        s.executeUpdate(TestDDL.createTable("pk_simple_ai", "$ddl, name ${TestDDL.textType()}"))

        assertEquals("id", s.getTableInfo(PkSimpleAI::class).primaryKey.dbName)

        val created = s.create(PkSimpleAI(name = "row1"))
        assertTrue(created.id > 0L, "auto-increment id must be > 0")
        val found = s.findById<PkSimpleAI>(created.id)
        assertNotNull(found); assertEquals("row1", found.name)

        TestDDL.dropTable("pk_simple_ai")
    }

    @Test
    fun autoIncrementPkDigitCamel() = withDb("PK-AI-DIGIT-CAMEL") { s ->
        val ddl = TestDDL.autoIncrementPrimaryKey("t01_id")
            ?: skipTest(SkipReason.FEATURE_NA, "no auto-increment on this dialect")
        TestDDL.dropTable("pk_digit_camel_ai")
        s.executeUpdate(TestDDL.createTable("pk_digit_camel_ai", "$ddl, name ${TestDDL.textType()}"))

        assertEquals("t01_id", s.getTableInfo(PkDigitCamelAI::class).primaryKey.dbName)

        val created = s.create(PkDigitCamelAI(name = "row1"))
        assertTrue(created.t01Id > 0L)
        val found = s.findById<PkDigitCamelAI>(created.t01Id)
        assertNotNull(found); assertEquals("row1", found.name)

        TestDDL.dropTable("pk_digit_camel_ai")
    }

    @Test
    fun autoIncrementPkRenamed() = withDb("PK-AI-RENAMED") { s ->
        val ddl = TestDDL.autoIncrementPrimaryKey("totally_different")
            ?: skipTest(SkipReason.FEATURE_NA, "no auto-increment on this dialect")
        TestDDL.dropTable("pk_renamed_ai")
        s.executeUpdate(TestDDL.createTable("pk_renamed_ai", "$ddl, name ${TestDDL.textType()}"))

        assertEquals("totally_different", s.getTableInfo(PkRenamedAI::class).primaryKey.dbName)

        val created = s.create(PkRenamedAI(name = "row1"))
        assertTrue(created.customPk > 0L)
        val found = s.findById<PkRenamedAI>(created.customPk)
        assertNotNull(found); assertEquals("row1", found.name)

        TestDDL.dropTable("pk_renamed_ai")
    }

    // --- Manual PK path (no setField after insert; round-trips findById WHERE clause) ---

    @Test
    fun manualPkDigitCamel() = withDb("PK-MANUAL-DIGIT-CAMEL") { s ->
        TestDDL.dropTable("pk_manual_camel")
        s.executeUpdate(TestDDL.createTable("pk_manual_camel",
            "t01_id ${TestDDL.bigIntType()} PRIMARY KEY, name ${TestDDL.textType()}"))

        assertEquals("t01_id", s.getTableInfo(PkManualCamel::class).primaryKey.dbName)

        s.create(PkManualCamel(t01Id = 42L, name = "row1"))
        val found = s.findById<PkManualCamel>(42L)
        assertNotNull(found); assertEquals(42L, found.t01Id); assertEquals("row1", found.name)

        TestDDL.dropTable("pk_manual_camel")
    }

    @Test
    fun manualPkRenamed() = withDb("PK-MANUAL-RENAMED") { s ->
        TestDDL.dropTable("pk_manual_renamed")
        s.executeUpdate(TestDDL.createTable("pk_manual_renamed",
            "totally_different ${TestDDL.bigIntType()} PRIMARY KEY, name ${TestDDL.textType()}"))

        assertEquals("totally_different", s.getTableInfo(PkManualRenamed::class).primaryKey.dbName)

        s.create(PkManualRenamed(customPk = 7L, name = "row1"))
        val found = s.findById<PkManualRenamed>(7L)
        assertNotNull(found); assertEquals(7L, found.customPk)

        TestDDL.dropTable("pk_manual_renamed")
    }
}
