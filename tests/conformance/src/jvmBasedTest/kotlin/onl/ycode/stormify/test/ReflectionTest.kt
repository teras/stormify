package onl.ycode.stormify.test

import onl.ycode.stormify.*
import test.SkipReason
import test.TestDDL
import test.TestHelper
import test.skipTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests for JVM reflection-based entity discovery, covering:
 * - Kotlin classes with delegated properties (by db())
 * - Entity references with AutoTable and lazy loading
 * - Update preserving FK references via sqlData()
 * - Inheritance with and without AutoTable
 */
open class ReflectionTest {

    private fun withDb(name: String, test: (Stormify) -> Unit) = TestHelper.withDb(name, test)

    private fun Stormify.createRefSchema() {
        TestDDL.dropTable("ref_child")
        TestDDL.dropTable("ref_parent")
        executeUpdate(TestDDL.createTable("ref_parent",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))
        executeUpdate(TestDDL.createTable("ref_child",
            "${TestDDL.intPrimaryKey("id")}, title ${TestDDL.textType()}, " +
                    "${TestDDL.intColumn("parent")}, ${TestDDL.foreignKey("parent", "ref_parent", "id")}"))
    }

    private fun Stormify.seedRefData() {
        val p1 = RefParent().apply { id = 1; name = "Alice" }
        val p2 = RefParent().apply { id = 2; name = "Bob" }
        create(p1); create(p2)
        create(RefChild().apply { id = 10; title = "Task1"; this.parent = p1 })
        create(RefChild().apply { id = 20; title = "Task2"; this.parent = p1 })
        create(RefChild().apply { id = 30; title = "Task3"; this.parent = p2 })
    }

    @Test
    fun autoTableDelegatesAndRefs() = withDb("REF-DELEGATES") { s ->
        s.createRefSchema()
        s.seedRefData()

        val child = s.findById<RefChild>(10)
        assertNotNull(child)
        assertEquals("Task1", child.title)
        assertNotNull(child.parent)
        assertEquals("Alice", child.parent?.name)
    }

    @Test
    fun updatePreservesReferences() = withDb("REF-UPDATE") { s ->
        s.createRefSchema()
        s.seedRefData()

        val child = s.findById<RefChild>(10)!!
        child.title = "updated"
        s.update(child)

        val updated = s.findById<RefChild>(10)!!
        assertEquals("updated", updated.title)
        assertEquals("Alice", updated.parent?.name)
    }

    @Test
    fun findAllWithReferences() = withDb("REF-FINDALL") { s ->
        s.createRefSchema()
        s.seedRefData()

        val children = s.findAll<RefChild>("ORDER BY id")
        assertEquals(3, children.size)
        assertEquals("Alice", children[0].parent?.name)
        assertEquals("Alice", children[1].parent?.name)
        assertEquals("Bob", children[2].parent?.name)
    }

    @Test
    fun deleteWithReference() = withDb("REF-DELETE") { s ->
        s.createRefSchema()
        s.seedRefData()

        s.delete(s.findById<RefChild>(10)!!)
        assertEquals(2, s.findAll<RefChild>().size)
    }

    // --- Inheritance tests: plain Kotlin vs AutoTable ---
    // Each tests: inherited PK, String fields, Boolean fields, entity references, full CRUD

    private fun Stormify.createInheritSchema() {
        TestDDL.dropTable("plain_child")
        TestDDL.dropTable("at_child")
        TestDDL.dropTable("ref_parent")
        executeUpdate(TestDDL.createTable("ref_parent",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))
        executeUpdate(TestDDL.createTable("plain_child",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}, is_active ${TestDDL.booleanType()}, " +
                    "${TestDDL.intColumn("parent")}, ${TestDDL.foreignKey("parent", "ref_parent", "id")}"))
        executeUpdate(TestDDL.createTable("at_child",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}, is_active ${TestDDL.booleanType()}, " +
                    "${TestDDL.intColumn("parent")}, ${TestDDL.foreignKey("parent", "ref_parent", "id")}"))
    }

    private fun Stormify.seedParents() {
        create(RefParent().apply { id = 100; name = "ParentA" })
        create(RefParent().apply { id = 200; name = "ParentB" })
    }

    @Test
    fun plainInheritanceCrud() = withDb("PLAIN-INHERIT") { s ->
        s.createInheritSchema()
        s.seedParents()

        val parentA = s.findById<RefParent>(100)!!
        val parentB = s.findById<RefParent>(200)!!

        s.create(PlainChild().apply { id = 1; name = "Alice"; isActive = true; parent = parentA })
        s.create(PlainChild().apply { id = 2; name = "Bob"; isActive = false; parent = parentB })

        // Verify all fields after create
        val found = s.findById<PlainChild>(1)!!
        assertEquals("Alice", found.name)
        assertEquals(true, found.isActive)
        assertEquals(100, found.parent?.id)
        val bob = s.findById<PlainChild>(2)!!
        assertEquals("Bob", bob.name)
        assertEquals(false, bob.isActive)
        assertEquals(200, bob.parent?.id)

        // Update all fields and verify
        found.name = "Alice2"
        found.isActive = false
        found.parent = parentB
        s.update(found)
        val updated = s.findById<PlainChild>(1)!!
        assertEquals("Alice2", updated.name)
        assertEquals(false, updated.isActive)
        assertEquals("ParentB", updated.parent?.name)

        // Delete and verify remaining
        s.delete(updated)
        val remaining = s.findAll<PlainChild>()
        assertEquals(1, remaining.size)
        assertEquals("Bob", remaining[0].name)
    }

    @Test
    fun autoTableInheritanceCrud() = withDb("AT-INHERIT") { s ->
        s.createInheritSchema()
        s.seedParents()

        val parentA = s.findById<RefParent>(100)!!
        val parentB = s.findById<RefParent>(200)!!

        s.create(AtChild().apply { id = 1; name = "Alice"; isActive = true; parent = parentA })
        s.create(AtChild().apply { id = 2; name = "Bob"; isActive = false; parent = parentB })

        // Verify all fields after create
        val found = s.findById<AtChild>(1)!!
        assertEquals("Alice", found.name)
        assertEquals(true, found.isActive)
        assertEquals(100, found.parent?.id)
        val bob = s.findById<AtChild>(2)!!
        assertEquals("Bob", bob.name)
        assertEquals(false, bob.isActive)
        assertEquals(200, bob.parent?.id)

        // Update all fields and verify
        found.name = "Alice2"
        found.isActive = false
        found.parent = parentB
        s.update(found)
        val updated = s.findById<AtChild>(1)!!
        assertEquals("Alice2", updated.name)
        assertEquals(false, updated.isActive)
        assertEquals("ParentB", updated.parent?.name)

        // Delete and verify remaining
        s.delete(updated)
        val remaining = s.findAll<AtChild>()
        assertEquals(1, remaining.size)
        assertEquals("Bob", remaining[0].name)
    }

    // --- Enum via reflection (no annproc) ---

    @Test
    fun enumViaReflection() = withDb("REFL-ENUM") { s ->
        TestDDL.dropTable("refl_enum_test")
        s.executeUpdate(TestDDL.createTable("refl_enum_test",
            "${TestDDL.intPrimaryKey("id")}, ${TestDDL.intColumn("plain_status")}, ${TestDDL.intColumn("custom_status")}"))

        // Create
        s.create(ReflEnumEntity().apply { id = 1; plainStatus = ReflPlainStatus.BANNED; customStatus = ReflCustomStatus.INACTIVE })

        // Read back
        val found = s.findById<ReflEnumEntity>(1)
        assertNotNull(found)
        assertEquals(ReflPlainStatus.BANNED, found.plainStatus)
        assertEquals(ReflCustomStatus.INACTIVE, found.customStatus)

        // Verify stored values
        assertEquals(2, s.readOne<Int>("SELECT plain_status FROM refl_enum_test WHERE id = ?", 1))   // ordinal
        assertEquals(20, s.readOne<Int>("SELECT custom_status FROM refl_enum_test WHERE id = ?", 1))  // dbValue

        // Update
        found.plainStatus = ReflPlainStatus.ACTIVE
        found.customStatus = ReflCustomStatus.BANNED
        s.update(found)
        val updated = s.findById<ReflEnumEntity>(1)!!
        assertEquals(ReflPlainStatus.ACTIVE, updated.plainStatus)
        assertEquals(ReflCustomStatus.BANNED, updated.customStatus)

        // Query with enum param
        val results = s.read<ReflEnumEntity>("SELECT * FROM refl_enum_test WHERE plain_status = ?", ReflPlainStatus.ACTIVE)
        assertEquals(1, results.size)

        // Null enum
        s.create(ReflEnumEntity().apply { id = 2 })
        val nullEntity = s.findById<ReflEnumEntity>(2)!!
        assertNull(nullEntity.plainStatus)
        assertNull(nullEntity.customStatus)
    }

    @Test
    fun enumAsStringViaReflection() = withDb("REFL-ENUM-STRING") { s ->
        TestDDL.dropTable("refl_enum_string_test")
        s.executeUpdate(TestDDL.createTable("refl_enum_string_test",
            "${TestDDL.intPrimaryKey("id")}, status ${TestDDL.textType()}, ${TestDDL.intColumn("priority")}"))

        s.create(ReflEnumStringEntity().apply { id = 1; status = ReflPlainStatus.BANNED; priority = ReflPlainStatus.BANNED })

        // Verify string vs ordinal storage
        assertEquals("BANNED", s.readOne<String>("SELECT status FROM refl_enum_string_test WHERE id = ?", 1))
        assertEquals(2, s.readOne<Int>("SELECT priority FROM refl_enum_string_test WHERE id = ?", 1))

        // Read back
        val found = s.findById<ReflEnumStringEntity>(1)!!
        assertEquals(ReflPlainStatus.BANNED, found.status)
        assertEquals(ReflPlainStatus.BANNED, found.priority)

        // Update
        found.status = ReflPlainStatus.ACTIVE
        s.update(found)
        assertEquals("ACTIVE", s.readOne<String>("SELECT status FROM refl_enum_string_test WHERE id = ?", 1))
    }

    // Asserts on the instant only — the column carries no offset/zone, so
    // OffsetDateTime/ZonedDateTime round-trip lose their original anchor.
    @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
    @Test
    fun javaScalarFieldsAreNotReferences() = withDb("REGRESSION-SCALARS") { s ->
        TestDDL.dropTable("scalar_regression")
        // pg TIMESTAMP WITHOUT TIME ZONE shifts UTC instants through the JVM
        // zone; mysql/maria default TIMESTAMP drops sub-second precision.
        val tsType = when {
            TestDDL.isPostgres -> "TIMESTAMPTZ"
            TestDDL.isMysqlFamily -> "TIMESTAMP(3)"
            else -> TestDDL.timestampType()
        }
        s.executeUpdate(TestDDL.createTable("scalar_regression",
            "${TestDDL.intPrimaryKey("id")}, " +
                // Nullable: MySQL 5.7 otherwise invents an invalid zero-date
                // default for every TIMESTAMP column after the first.
                "ts $tsType NULL, " +
                "odt $tsType NULL, " +
                "zdt $tsType NULL, " +
                "uuid ${TestDDL.textType()}, " +
                "kuid ${TestDDL.textType()}"))

        val ts = java.time.Instant.ofEpochMilli(1_700_000_000_123L)
        val odt = java.time.OffsetDateTime.ofInstant(ts, java.time.ZoneOffset.UTC)
        val zdt = java.time.ZonedDateTime.ofInstant(ts, java.time.ZoneOffset.UTC)
        val uid = java.util.UUID.fromString("12345678-1234-1234-1234-123456789012")
        val kuid = kotlin.uuid.Uuid.parse("12345678-1234-1234-1234-123456789012")

        s.create(ScalarRegression().apply {
            id = 1; this.ts = ts; this.odt = odt; this.zdt = zdt
            this.uuid = uid; this.kuid = kuid
        })

        val loaded = s.findById<ScalarRegression>(1)
        assertNotNull(loaded)
        assertEquals(ts, loaded.ts)
        assertEquals(odt.toInstant(), loaded.odt?.toInstant())
        assertEquals(zdt.toInstant(), loaded.zdt?.toInstant())
        assertEquals(uid, loaded.uuid)
        assertEquals(kuid, loaded.kuid)
    }

    // Entity-level round-trip for java.time LocalDate/LocalDateTime — the type
    // matrix only exercises the raw-SQL path, not ORM property mapping.
    @Test
    fun javaLocalTemporalEntityRoundTrip() = withDb("JAVA-LOCAL-TEMPORAL") { s ->
        TestDDL.dropTable("java_local_temporal")
        s.executeUpdate(TestDDL.createTable("java_local_temporal",
            "${TestDDL.intPrimaryKey("id")}, ld DATE, ldt ${TestDDL.timestampType()}"))
        val ld = java.time.LocalDate.of(2026, 3, 20)
        // Whole seconds: some dialects (MySQL family) drop sub-second precision.
        val ldt = java.time.LocalDateTime.of(2026, 3, 20, 14, 30, 45)
        s.create(JavaLocalTemporal().apply { id = 1; this.ld = ld; this.ldt = ldt })
        val loaded = s.findById<JavaLocalTemporal>(1)
        assertNotNull(loaded)
        assertEquals(ld, loaded.ld)
        assertEquals(ldt, loaded.ldt)
    }

    // OffsetTime ↔ TIMETZ: only PostgreSQL has TIME WITH TIME ZONE.
    @Test
    fun offsetTimeRoundTripWithTimetz() = withDb("OFFSETTIME-TIMETZ") { s ->
        if (!TestDDL.isPostgres)
            skipTest(SkipReason.DB_LIMITATION, "TIME WITH TIME ZONE only on PostgreSQL")
        TestDDL.dropTable("offset_time_test")
        s.executeUpdate("CREATE TABLE offset_time_test (id INT PRIMARY KEY, ot TIMETZ)")

        val ot = java.time.OffsetTime.of(14, 30, 45, 0, java.time.ZoneOffset.ofHours(2))
        s.executeUpdate("INSERT INTO offset_time_test VALUES (?, ?)", 1, ot)
        val back = s.readOne<java.time.OffsetTime>(
            "SELECT ot FROM offset_time_test WHERE id = ?", 1)
        assertEquals(ot, back)
    }

    // --- JPA annotations via reflection (no annproc) ---

    @Test
    fun jpaAnnotationsViaReflection() = withDb("REFL-JPA") { s ->
        TestDDL.dropTable("jpa_users")
        s.executeUpdate(TestDDL.createTable("jpa_users",
            "${TestDDL.intPrimaryKey("user_id")}, email ${TestDDL.textType()}, inserted_at ${TestDDL.textType()}"))

        s.create(JpaReflUser().apply {
            id = 1; emailAddress = "a@b.c"; insertedAt = "NOT-INSERTABLE"; scratch = "ignored"
        })
        // @Column(name = ...) honored on both read and write paths
        assertEquals("a@b.c", s.readOne<String>("SELECT email FROM jpa_users WHERE user_id = ?", 1))
        // insertable = false → column excluded from INSERT (readOne throws on NULL, so count instead)
        assertEquals(0, s.readOne<Int>("SELECT COUNT(*) FROM jpa_users WHERE user_id = ? AND inserted_at IS NOT NULL", 1))

        val loaded = s.findById<JpaReflUser>(1)
        assertNotNull(loaded)
        assertEquals("a@b.c", loaded.emailAddress)
        loaded.emailAddress = "x@y.z"
        loaded.insertedAt = "NOT-UPDATABLE"
        s.update(loaded)
        assertEquals("x@y.z", s.readOne<String>("SELECT email FROM jpa_users WHERE user_id = ?", 1))
        // updatable = false → column excluded from UPDATE
        assertEquals(0, s.readOne<Int>("SELECT COUNT(*) FROM jpa_users WHERE user_id = ? AND inserted_at IS NOT NULL", 1))
    }

    // Read-only association next to the raw foreign key column: the classic JPA
    // mapping where the column is written through the scalar and read through the
    // reference. @JoinColumn(insertable/updatable = false) must keep the column out
    // of INSERT and UPDATE, otherwise it is listed twice and most databases reject
    // the statement.
    @Test
    fun jpaReadOnlyJoinColumnViaReflection() = withDb("REFL-JPA-JOINCOLUMN") { s ->
        TestDDL.dropTable("jpa_ro_child")
        TestDDL.dropTable("ref_parent")
        s.executeUpdate(TestDDL.createTable("ref_parent",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))
        s.executeUpdate(TestDDL.createTable("jpa_ro_child",
            "${TestDDL.intPrimaryKey("id")}, ${TestDDL.intColumn("parent_id")}"))
        s.create(RefParent().apply { id = 1; name = "Alice" })
        s.create(RefParent().apply { id = 2; name = "Bob" })

        val info = s.getTableInfo(JpaReadOnlyJoinChild::class)
        val parentColumns = info.fieldInfos.filter { it.dbName == "parent_id" }
        assertEquals(listOf("parentId"), parentColumns.filter { it.isInsertable }.map { it.name })
        assertEquals(listOf("parentId"), parentColumns.filter { it.isUpdatable }.map { it.name })

        s.create(JpaReadOnlyJoinChild().apply { id = 10; parentId = 1; parent = RefParent().apply { id = 2 } })
        assertEquals(1, s.readOne<Int>("SELECT parent_id FROM jpa_ro_child WHERE id = ?", 10))

        val loaded = s.findById<JpaReadOnlyJoinChild>(10)!!
        assertEquals("Alice", loaded.parent?.name)
        loaded.parentId = 2
        loaded.parent = null
        s.update(loaded)
        assertEquals(2, s.readOne<Int>("SELECT parent_id FROM jpa_ro_child WHERE id = ?", 10))
    }

    // Only properties that store a value are columns. A computed `val x get() = ...` has
    // no field and no delegate, and a `by lazy` with @delegate:Transient is transient,
    // so neither may appear in SQL: with no such column every statement would fail.
    @Test
    fun computedAndTransientLazyPropertiesAreNotColumns() = withDb("REFL-COMPUTED") { s ->
        TestDDL.dropTable("computed_plain")
        TestDDL.dropTable("computed_auto")
        s.executeUpdate(TestDDL.createTable("computed_plain", "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))
        s.executeUpdate(TestDDL.createTable("computed_auto", "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))

        assertEquals(listOf("id", "name"), s.getTableInfo(ComputedPlain::class).fieldInfos.map { it.name }.sorted())
        assertEquals(listOf("id", "name"), s.getTableInfo(ComputedAuto::class).fieldInfos.map { it.name }.sorted())

        s.create(ComputedPlain().apply { id = 1; name = "plain" })
        val plain = s.findById<ComputedPlain>(1)!!
        assertEquals("PLAIN", plain.upper)
        assertEquals("lazy-1", plain.cached)
        plain.name = "plain2"
        s.update(plain)
        assertEquals("PLAIN2", s.findAll<ComputedPlain>().single().upper)

        s.create(ComputedAuto().apply { id = 2; name = "auto" })
        val auto = s.findById<ComputedAuto>(2)!!
        assertEquals("AUTO", auto.upper)
        assertEquals("lazy-2", auto.cached)
        auto.name = "auto2"
        s.update(auto)
        assertEquals("AUTO2", s.findAll<ComputedAuto>().single().upper)
    }

    // A read-only property with a backing field (a `val`, a `var` with a private setter)
    // is a normal column: written through its getter, and loaded through its field, so an
    // UPDATE after a load writes back what the database holds and nothing is lost.
    @Test
    fun readOnlyPropertiesLoadThroughTheirField() = withDb("REFL-READONLY-PROPS") { s ->
        TestDDL.dropTable("readonly_val")
        s.executeUpdate(TestDDL.createTable("readonly_val",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}, stamp ${TestDDL.textType()}, " +
                    "guarded ${TestDDL.textType()}"))

        val fields = s.getTableInfo(ReadOnlyValEntity::class).fieldInfos.associateBy { it.name }
        assertEquals(setOf("id", "name", "stamp", "guarded"), fields.keys)
        assertEquals(true, fields.getValue("stamp").isInsertable)
        assertEquals(true, fields.getValue("stamp").isUpdatable)

        s.create(ReadOnlyValEntity("written").apply { id = 1; name = "a" })
        assertEquals("written", s.readOne<String>("SELECT stamp FROM readonly_val WHERE id = ?", 1))
        assertEquals("guarded", s.readOne<String>("SELECT guarded FROM readonly_val WHERE id = ?", 1))
        s.executeUpdate("UPDATE readonly_val SET stamp = 'db-stamp', guarded = 'db-guarded' WHERE id = 1")

        val loaded = s.findById<ReadOnlyValEntity>(1)!!
        assertEquals("a", loaded.name)
        assertEquals("db-stamp", loaded.stamp)
        assertEquals("db-guarded", loaded.guarded)
        assertEquals("A", loaded.upper)
        assertEquals("lazy-1", loaded.cached)

        loaded.name = "b"
        s.update(loaded)
        assertEquals("b", s.readOne<String>("SELECT name FROM readonly_val WHERE id = ?", 1))
        assertEquals("db-stamp", s.readOne<String>("SELECT stamp FROM readonly_val WHERE id = ?", 1))
        assertEquals("db-guarded", s.readOne<String>("SELECT guarded FROM readonly_val WHERE id = ?", 1))
    }

    // A `val` primary key is loaded through its field like any read-only property,
    // whether it is annotated or found by a primary key resolver.
    @Test
    fun valPrimaryKeyIsLoaded() = withDb("REFL-VAL-PK") { s ->
        TestDDL.dropTable("val_pk")
        s.executeUpdate(TestDDL.createTable("val_pk", "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))
        s.create(ValPkAnnotated(1).apply { name = "a" })
        s.create(ValPkResolved(2).apply { name = "b" })

        val annotated = s.findById<ValPkAnnotated>(1)!!
        assertEquals(1, annotated.id)
        assertEquals("a", annotated.name)
        annotated.name = "a2"
        s.update(annotated)
        assertEquals("a2", s.readOne<String>("SELECT name FROM val_pk WHERE id = ?", 1))

        val resolved = s.findById<ValPkResolved>(2)!!
        assertEquals(2, resolved.id)
        assertEquals("b", resolved.name)
    }

    // A read-only property backed by a delegate other than `db` (here `by lazy`) has
    // nowhere to store a loaded value. Unless it is transient, the class is rejected
    // when its metadata is built, before any SQL runs.
    @Test
    fun readOnlyDelegateWithoutTransientIsRejected() = withDb("REFL-LAZY-NOT-TRANSIENT") { s ->
        val e = assertFailsWith<onl.ycode.kdbc.SQLException> { s.getTableInfo(LazyColumnEntity::class) }
        assertEquals(
            "Property lazyCol of LazyColumnEntity uses a delegate and cannot be mapped to a column: " +
                    "mark it @delegate:Transient or use by db()", e.message
        )
        assertFailsWith<onl.ycode.kdbc.SQLException> { s.findById<LazyColumnEntity>(1) }
    }

    // A read-only property is loaded through its field, so it must be written through its
    // field too: a custom getter that transforms the value would otherwise turn every
    // load followed by an update into a silent change of the stored data.
    @Test
    fun readOnlyWithCustomGetterWritesItsField() = withDb("REFL-RO-CUSTOM-GETTER") { s ->
        TestDDL.dropTable("ro_custom_getter")
        s.executeUpdate(TestDDL.createTable("ro_custom_getter",
            "${TestDDL.intPrimaryKey("id")}, code ${TestDDL.textType()}"))
        s.create(RoCustomGetter().apply { id = 1 })
        assertEquals("abc", s.readOne<String>("SELECT code FROM ro_custom_getter WHERE id = ?", 1))

        s.executeUpdate("UPDATE ro_custom_getter SET code = 'xyz' WHERE id = 1")
        val loaded = s.findById<RoCustomGetter>(1)!!
        assertEquals("XYZ", loaded.code)   // the getter still applies for the caller
        s.update(loaded)
        assertEquals("xyz", s.readOne<String>("SELECT code FROM ro_custom_getter WHERE id = ?", 1))
    }

    // `val x by db()`: the db delegate holds the value, but only a var's setter can put a
    // loaded value there. Rejected when the metadata is built, with the fix to make.
    @Test
    fun valByDbIsRejected() = withDb("REFL-VAL-BY-DB") { s ->
        val e = assertFailsWith<onl.ycode.kdbc.SQLException> { s.getTableInfo(ValByDbEntity::class) }
        assertEquals("Property name of ValByDbEntity is a val by db(): it must be a var to be loaded", e.message)
    }

    // `var x by db()` with a private setter: the message names the actual problem.
    @Test
    fun dbDelegateWithPrivateSetterIsRejected() = withDb("REFL-DB-PRIVATE-SET") { s ->
        val e = assertFailsWith<onl.ycode.kdbc.SQLException> { s.getTableInfo(PrivateSetByDbEntity::class) }
        assertEquals("Property name of PrivateSetByDbEntity has no public setter: make its setter public to be loaded", e.message)
    }

    // In JPA the generator name and the database sequence are different things:
    // `sequenceName` is the sequence, `name` only identifies the generator. A
    // generator without `sequenceName` still uses its name as the sequence.
    @Test
    fun jpaSequenceNameViaReflection() = withDb("REFL-JPA-SEQUENCE") { s ->
        if (!TestDDL.supportsSequences()) skipTest(SkipReason.FEATURE_NA, "no CREATE SEQUENCE")
        TestDDL.dropTable("jpa_seq")
        TestDDL.dropSequence("jpa_seq_real")
        TestDDL.dropSequence("jpa_seq_gen")
        TestDDL.createSequence("jpa_seq_real")
        TestDDL.createSequence("jpa_seq_gen")
        // Move the generator-named sequence away from the other one, so a value
        // drawn from the wrong sequence cannot match by accident.
        repeat(5) { s.readOne<Long>(s.sqlDialect.sequenceDialect("jpa_seq_gen", 1)!!) }
        s.executeUpdate(TestDDL.createTable("jpa_seq",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))

        assertEquals("jpa_seq_real", s.getTableInfo(JpaSequenceEntity::class).primaryKey.sequence)
        assertEquals("jpa_seq_gen", s.getTableInfo(JpaGeneratorOnlyEntity::class).primaryKey.sequence)

        val created = s.create(JpaSequenceEntity().apply { name = "a" })
        assertEquals(1, created.id)
        val generatorOnly = s.create(JpaGeneratorOnlyEntity().apply { name = "b" })
        assertEquals(6, generatorOnly.id)

        TestDDL.dropTable("jpa_seq")
        TestDDL.dropSequence("jpa_seq_real")
        TestDDL.dropSequence("jpa_seq_gen")
    }

    // @DbField and JPA annotations on the same property are merged, not overridden:
    // @DbField(name) together with @Id is still the primary key, and a JPA
    // insertable/updatable = false is honored next to a @DbField with defaults.
    // @Entity(name) is the table name when there is no @Table, as in JPA.
    @Test
    fun dbFieldAndJpaAnnotationsMergeViaReflection() = withDb("REFL-JPA-MERGE") { s ->
        TestDDL.dropTable("jpa_merged")
        s.executeUpdate(TestDDL.createTable("jpa_merged",
            "${TestDDL.intPrimaryKey("merged_id")}, label ${TestDDL.textType()}, stamp ${TestDDL.textType()}"))

        val info = s.getTableInfo(JpaMergedEntity::class)
        assertEquals("jpa_merged", info.tableName)
        assertEquals(listOf("merged_id"), info.primaryKeys.map { it.dbName })
        assertEquals(listOf("label", "merged_id"), info.fieldInfos.filter { it.isInsertable }.map { it.dbName }.sorted())
        assertEquals(listOf("label"), info.fieldInfos.filter { it.isUpdatable && !it.isPrimaryKey }.map { it.dbName })

        s.create(JpaMergedEntity().apply { id = 1; label = "a"; stamp = "NOT-INSERTABLE" })
        assertEquals(0, s.readOne<Int>("SELECT COUNT(*) FROM jpa_merged WHERE merged_id = ? AND stamp IS NOT NULL", 1))
        val loaded = s.findById<JpaMergedEntity>(1)!!
        loaded.label = "b"
        s.update(loaded)
        assertEquals("b", s.readOne<String>("SELECT label FROM jpa_merged WHERE merged_id = ?", 1))
    }

}

// --- Enum test entities (reflection-only, no annproc) ---

enum class ReflPlainStatus { ACTIVE, INACTIVE, BANNED }

enum class ReflCustomStatus(override val dbValue: Int) : DbValue {
    ACTIVE(10), INACTIVE(20), BANNED(99)
}

@DbTable(name = "refl_enum_test")
class ReflEnumEntity {
    @DbField(primaryKey = true) var id: Int = 0
    var plainStatus: ReflPlainStatus? = null
    var customStatus: ReflCustomStatus? = null
}

@DbTable(name = "refl_enum_string_test")
class ReflEnumStringEntity {
    @DbField(primaryKey = true) var id: Int = 0
    @DbField(enumAsString = true) var status: ReflPlainStatus? = null
    var priority: ReflPlainStatus? = null  // ordinal for comparison
}

// JPA-annotated entity, resolved purely through reflection (test sources are
// not processed by the annotation processor). `scratch` must be excluded from
// all SQL; if it leaked in, every statement would fail with "no such column".
@javax.persistence.Table(name = "jpa_users")
class JpaReflUser {
    @javax.persistence.Id
    @javax.persistence.Column(name = "user_id")
    var id: Int = 0

    @javax.persistence.Column(name = "email")
    var emailAddress: String? = null

    @javax.persistence.Column(name = "inserted_at", insertable = false, updatable = false)
    var insertedAt: String? = null

    @javax.persistence.Transient
    var scratch: String? = null
}

// Raw foreign key column written through `parentId`, read through the read-only
// `parent` association.
@javax.persistence.Table(name = "jpa_ro_child")
class JpaReadOnlyJoinChild {
    @javax.persistence.Id
    var id: Int? = null

    @javax.persistence.Column(name = "parent_id")
    var parentId: Int? = null

    @javax.persistence.JoinColumn(name = "parent_id", insertable = false, updatable = false)
    var parent: RefParent? = null
}

// Read-only columns loaded through their field: `stamp` is a val with a backing field,
// `guarded` a var with a private setter. `upper` is computed and `cached` is a transient
// lazy delegate, so neither is a column.
@DbTable(name = "readonly_val")
class ReadOnlyValEntity(val stamp: String? = "default") {
    @DbField(primaryKey = true) var id: Int? = null
    var name: String? = null
    var guarded: String? = "guarded"
        private set
    val upper: String? get() = name?.uppercase()
    @delegate:Transient val cached: String by lazy { "lazy-$id" }
}

@DbTable(name = "val_pk")
class ValPkAnnotated(@DbField(primaryKey = true) val id: Int = 0) {
    var name: String? = null
}

// No annotation: the test primary key resolver picks `id`.
@DbTable(name = "val_pk")
class ValPkResolved(val id: Int = 0) {
    var name: String? = null
}

@DbTable(name = "ro_custom_getter")
class RoCustomGetter {
    @DbField(primaryKey = true) var id: Int? = null
    val code: String = "abc"
        get() = field.uppercase()
}

@DbTable(name = "private_set_by_db")
class PrivateSetByDbEntity : onl.ycode.stormify.AutoTable() {
    @DbField(primaryKey = true) var id: Int? = null
    var name: String? by onl.ycode.stormify.db(null)
        private set
}

@DbTable(name = "val_by_db")
class ValByDbEntity : onl.ycode.stormify.AutoTable() {
    @DbField(primaryKey = true) var id: Int? = null
    val name: String? by onl.ycode.stormify.db(null)
}

@DbTable(name = "lazy_column")
class LazyColumnEntity {
    @DbField(primaryKey = true) var id: Int? = null
    val lazyCol: String by lazy { "lazy" }
}

@DbTable(name = "computed_plain")
class ComputedPlain {
    @DbField(primaryKey = true) var id: Int? = null
    var name: String? = null
    val upper: String? get() = name?.uppercase()
    @delegate:Transient val cached: String by lazy { "lazy-$id" }
}

@DbTable(name = "computed_auto")
class ComputedAuto : AutoTable() {
    @DbField(primaryKey = true) var id: Int? = null
    var name: String? by db(null)
    val upper: String? get() = name?.uppercase()
    @delegate:Transient val cached: String by lazy { "lazy-$id" }
}

@javax.persistence.Table(name = "jpa_seq")
class JpaSequenceEntity {
    @javax.persistence.Id
    @javax.persistence.SequenceGenerator(name = "jpa_seq_gen", sequenceName = "jpa_seq_real")
    @javax.persistence.GeneratedValue(generator = "jpa_seq_gen")
    var id: Int? = null
    var name: String? = null
}

@javax.persistence.Table(name = "jpa_seq")
class JpaGeneratorOnlyEntity {
    @javax.persistence.Id
    @javax.persistence.SequenceGenerator(name = "jpa_seq_gen")
    var id: Int? = null
    var name: String? = null
}

@javax.persistence.Entity(name = "jpa_merged")
class JpaMergedEntity {
    @DbField(name = "merged_id")
    @javax.persistence.Id
    var id: Int = 0

    @DbField
    var label: String? = null

    @DbField
    @javax.persistence.Column(insertable = false, updatable = false)
    var stamp: String? = null
}

// --- Test entity classes ---

@DbTable
class RefParent : AutoTable() {
    @DbField(primaryKey = true) var id: Int? = null
    var name: String? by db(null)
    override fun toString() = "RefParent(id=$id, name=$name)"
}

@DbTable
class RefChild : AutoTable() {
    @DbField(primaryKey = true) var id: Int? = null
    var title: String? by db(null)
    var parent: RefParent? by db(null)
    override fun toString() = "RefChild(id=$id, title=$title)"
}

// Each of these scalars must be in TypeConversion's registry — without it the
// property is misclassified as an entity reference and the read fails.
@DbTable(name = "scalar_regression")
@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
class ScalarRegression : AutoTable() {
    @DbField(primaryKey = true) var id: Int? = null
    var ts: java.time.Instant? by db(null)
    var odt: java.time.OffsetDateTime? by db(null)
    var zdt: java.time.ZonedDateTime? by db(null)
    var uuid: java.util.UUID? by db(null)
    var kuid: kotlin.uuid.Uuid? by db(null)
}

@DbTable(name = "java_local_temporal")
class JavaLocalTemporal : AutoTable() {
    @DbField(primaryKey = true) var id: Int? = null
    var ld: java.time.LocalDate? by db(null)
    var ldt: java.time.LocalDateTime? by db(null)
}

// Plain Kotlin inheritance — no AutoTable, no delegates
open class PlainBase {
    @DbField(primaryKey = true) var id: Int = 0
    var name: String = ""
    var isActive: Boolean = false
}

@DbTable
class PlainChild : PlainBase() {
    var parent: RefParent? = null
}

// AutoTable inheritance — delegates in both base and subclass
open class AtBase : AutoTable() {
    @DbField(primaryKey = true) var id: Int? = null
    var name: String? by db(null)
    var isActive: Boolean? by db(null)
}

@DbTable
class AtChild : AtBase() {
    var parent: RefParent? by db(null)
}
