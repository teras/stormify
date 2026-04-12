package test

import onl.ycode.stormify.Stormify
import kotlin.test.*

open class ConfigTest {
    private fun withDb(name: String, test: (Stormify) -> Unit) = TestHelper.withDb(name, test)

    @Test
    fun testNamingPolicySnakeCase() = withDb("NAMING-SNAKE") { s ->
        TestDDL.dropTable("camel_entity")
        s.executeUpdate(TestDDL.createTable("camel_entity",
            "${TestDDL.intPrimaryKey("id")}, first_name ${TestDDL.textType()}, last_name ${TestDDL.textType()}"))

        val info = s.getTableInfo(CamelEntity::class)
        assertEquals("camel_entity", info.tableName)
        assertEquals("first_name", info.getField("firstName")?.dbName)

        s.create(CamelEntity(id = 1, firstName = "Alice", lastName = "Smith"))
        assertEquals("Alice", s.findById<CamelEntity>(1)?.firstName)
    }

    @Test
    fun testNamingPolicyCamelCase() = withDb("NAMING-CAMEL") { s ->
        s.namingPolicy = onl.ycode.stormify.NamingPolicy.CAMEL_CASE
        TestDDL.dropTable("CamelPolicyEntity")
        s.executeUpdate(TestDDL.createTable("CamelPolicyEntity",
            "${TestDDL.intPrimaryKey("id")}, firstName ${TestDDL.textType()}, lastName ${TestDDL.textType()}"))

        val info = s.getTableInfo(CamelPolicyEntity::class)
        assertEquals("CamelPolicyEntity", info.tableName)
        assertEquals("firstName", info.getField("firstName")?.dbName)

        s.create(CamelPolicyEntity(id = 1, firstName = "Bob", lastName = "Jones"))
        assertEquals("Bob", s.findById<CamelPolicyEntity>(1)?.firstName)

        s.namingPolicy = onl.ycode.stormify.NamingPolicy.LOWER_CASE_WITH_UNDERSCORES
    }

    @Test
    fun testNamingPolicyUpperCase() = withDb("NAMING-UPPER") { s ->
        s.namingPolicy = onl.ycode.stormify.NamingPolicy.UPPER_CASE_WITH_UNDERSCORES
        TestDDL.dropTable("UPPER_POLICY_ENTITY")
        s.executeUpdate(TestDDL.createTable("UPPER_POLICY_ENTITY",
            "${TestDDL.intPrimaryKey("ID")}, FIRST_NAME ${TestDDL.textType()}, LAST_NAME ${TestDDL.textType()}"))

        val info = s.getTableInfo(UpperPolicyEntity::class)
        assertEquals("UPPER_POLICY_ENTITY", info.tableName)
        assertEquals("FIRST_NAME", info.getField("firstName")?.dbName)

        s.create(UpperPolicyEntity(id = 1, firstName = "Charlie", lastName = "Brown"))
        assertEquals("Charlie", s.findById<UpperPolicyEntity>(1)?.firstName)

        s.namingPolicy = onl.ycode.stormify.NamingPolicy.LOWER_CASE_WITH_UNDERSCORES
    }

    @Test
    fun testStrictMode() = withDb("STRICT") { s ->
        TestDDL.dropTable("strict_test")
        s.executeUpdate(TestDDL.createTable("strict_test",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))
        s.executeUpdate("INSERT INTO strict_test (id, name) VALUES (?, ?)", 1, "test")

        // Non-strict: extra columns ignored
        s.isStrictMode = false
        val result = s.readOne<TestC>("SELECT id, name, id as bonus FROM strict_test WHERE id = ?", 1)
        assertNotNull(result)
        assertEquals("test", result.name)

        // Strict: extra columns throw
        s.isStrictMode = true
        assertFailsWith<onl.ycode.kdbc.SQLException> {
            s.readOne<TestC>("SELECT id, name, id as bonus FROM strict_test WHERE id = ?", 1)
        }

        s.isStrictMode = false
    }

    @Test
    fun testCustomPkResolver() = withDb("PK-RESOLVER") { s ->
        s.registerPrimaryKeyResolver(100) { _, field -> field.equals("pk_id", ignoreCase = true) }

        TestDDL.dropTable("pk_resolver_test")
        s.executeUpdate(TestDDL.createTable("pk_resolver_test",
            "pk_id INT PRIMARY KEY, name ${TestDDL.textType()}"))
        s.executeUpdate("INSERT INTO pk_resolver_test (pk_id, name) VALUES (?, ?)", 1, "test")

        @Suppress("UNCHECKED_CAST")
        val row = s.readOne<Map<String, Any?>>("SELECT pk_id, name FROM pk_resolver_test WHERE pk_id = ?", 1) as Map<String, Any?>
        assertEquals("test", row["name"])
    }

    @Test
    fun testFieldAnnotations() = withDb("ANNOTATIONS") { s ->
        TestDDL.dropTable("annotated_test")
        s.executeUpdate(TestDDL.createTable("annotated_test",
            "${TestDDL.intPrimaryKey("id")}, custom_col ${TestDDL.textType()}" +
                    ", read_only_on_create ${TestDDL.textType()}, read_only_on_update ${TestDDL.textType()}"))

        val info = s.getTableInfo(AnnotatedEntity::class)
        assertEquals("custom_col", info.getField("renamedField")?.dbName)

        // creatable=false → not in INSERT
        s.create(AnnotatedEntity(id = 1, renamedField = "custom", readOnlyOnCreate = "ignored", readOnlyOnUpdate = "saved"))
        val found = s.findById<AnnotatedEntity>(1)!!
        assertEquals("custom", found.renamedField)
        assertNull(found.readOnlyOnCreate)
        assertEquals("saved", found.readOnlyOnUpdate)

        // updatable=false → not in UPDATE
        s.executeUpdate("UPDATE annotated_test SET read_only_on_create = ? WHERE id = ?", "set_directly", 1)
        val f2 = s.findById<AnnotatedEntity>(1)!!
        f2.renamedField = "updated"; f2.readOnlyOnUpdate = "try_change"
        s.update(f2)
        val after = s.findById<AnnotatedEntity>(1)!!
        assertEquals("updated", after.renamedField)
        assertEquals("set_directly", after.readOnlyOnCreate)
        assertEquals("saved", after.readOnlyOnUpdate)
    }

    @Test
    fun testDoubleDbNames() = withDb("DOUBLE-DB") { s ->
        TestDDL.dropTable("double_db_name")
        s.executeUpdate(TestDDL.createTable("double_db_name",
            "${TestDDL.intColumn("id")}, name ${TestDDL.textType()}"))

        s.create(DoubleDbName(id = 1, name1 = "Name1", name2 = "Name2"))
        val t1 = s.findById<DoubleDbName>(1)!!
        assertEquals("Name1", t1.name1)
        assertEquals("Name1", t1.name2)
    }

    @Test
    fun testBlacklistFields() = withDb("BLACKLIST") { s ->
        s.addBlacklistField("secret")
        TestDDL.dropTable("blacklist_test")
        s.executeUpdate(TestDDL.createTable("blacklist_test",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}, secret ${TestDDL.textType()}"))

        assertNull(s.getTableInfo(BlacklistEntity::class).getField("secret"))
        assertNotNull(s.getTableInfo(BlacklistEntity::class).getField("name"))

        s.create(BlacklistEntity(id = 1, name = "visible", secret = "hidden"))
        // Secret not inserted (blacklisted), verify via Map
        @Suppress("UNCHECKED_CAST")
        val row = s.readOne<Map<String, Any?>>("SELECT secret FROM blacklist_test WHERE id = ?", 1) as Map<String, Any?>
        assertNull(row["secret"])

        s.removeBlacklistField("secret")
    }

    @Test
    fun testInheritance() = withDb("INHERITANCE") { s ->
        TestDDL.dropTable("user_entity")
        s.executeUpdate(TestDDL.createTable("user_entity",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}" +
                    ", email ${TestDDL.textType()}, created_by ${TestDDL.textType()}"))

        val user = UserEntity().apply { id = 1; name = "Alice"; email = "alice@test.com"; createdBy = "admin" }
        s.create(user)
        val found = s.findById<UserEntity>(1)!!
        assertEquals("Alice", found.name)
        assertEquals("admin", found.createdBy)
    }

    @Test
    fun testGenerics() = withDb("GENERICS") { s ->
        TestDDL.dropTable("generic_test")
        s.executeUpdate(TestDDL.createTable("generic_test",
            "${TestDDL.intPrimaryKey("id")}, value ${TestDDL.textType()}"))

        s.create(GenericHolder(id = 1, value = "hello"))
        val found = s.findById<GenericHolder<*>>(1)!!
        assertEquals(1, found.id)
        assertEquals("hello", found.value)
    }
}
