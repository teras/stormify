package onl.ycode.stormify;

// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

import onl.ycode.stormify.pojos.*;
import org.junit.jupiter.api.*;

import java.util.Map;

import static onl.ycode.stormify.StormifyManager.stormify;
import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConfigTest extends BaseDbTest {

    @Test
    @Order(1)
    void testNamingPolicySnakeCase() {
        StormifyManager s = stormify();

        // Default: lowerCaseWithUnderscores
        TestDDL.dropTable("camel_entity");
        s.executeUpdate(TestDDL.createTable("camel_entity",
                TestDDL.intPrimaryKey("id") + ", first_name " + TestDDL.textType() + ", last_name " + TestDDL.textType()));

        TableInfo snakeInfo = s.getTableInfo(CamelEntity.class);
        assertEquals("camel_entity", snakeInfo.getTableName());
        assertEquals("first_name", snakeInfo.getField("firstName").getDbName());
        assertEquals("last_name", snakeInfo.getField("lastName").getDbName());

        CamelEntity e = new CamelEntity();
        e.setId(1);
        e.setFirstName("Alice");
        e.setLastName("Smith");
        e.create();
        assertEquals("Alice", s.findById(CamelEntity.class, 1).getFirstName());
    }

    @Test
    @Order(2)
    void testNamingPolicyCamelCase() {
        StormifyManager s = stormify();

        s.setNamingPolicy(NamingPolicy.camelCase);
        TestDDL.dropTable("CamelPolicyEntity");
        s.executeUpdate(TestDDL.createTable("CamelPolicyEntity",
                TestDDL.intPrimaryKey("id") + ", firstName " + TestDDL.textType() + ", lastName " + TestDDL.textType()));

        TableInfo camelInfo = s.getTableInfo(CamelPolicyEntity.class);
        assertEquals("CamelPolicyEntity", camelInfo.getTableName());
        assertEquals("firstName", camelInfo.getField("firstName").getDbName());

        CamelPolicyEntity e = new CamelPolicyEntity();
        e.setId(1);
        e.setFirstName("Bob");
        e.setLastName("Jones");
        e.create();
        assertEquals("Bob", s.findById(CamelPolicyEntity.class, 1).getFirstName());

        s.setNamingPolicy(NamingPolicy.lowerCaseWithUnderscores);
    }

    @Test
    @Order(3)
    void testNamingPolicyUpperCase() {
        StormifyManager s = stormify();

        s.setNamingPolicy(NamingPolicy.upperCaseWithUnderscores);
        TestDDL.dropTable("UPPER_POLICY_ENTITY");
        s.executeUpdate(TestDDL.createTable("UPPER_POLICY_ENTITY",
                TestDDL.intPrimaryKey("ID") + ", FIRST_NAME " + TestDDL.textType() + ", LAST_NAME " + TestDDL.textType()));

        TableInfo upperInfo = s.getTableInfo(UpperPolicyEntity.class);
        assertEquals("UPPER_POLICY_ENTITY", upperInfo.getTableName());
        assertEquals("FIRST_NAME", upperInfo.getField("firstName").getDbName());

        UpperPolicyEntity e = new UpperPolicyEntity();
        e.setId(1);
        e.setFirstName("Charlie");
        e.setLastName("Brown");
        e.create();
        assertEquals("Charlie", s.findById(UpperPolicyEntity.class, 1).getFirstName());

        s.setNamingPolicy(NamingPolicy.lowerCaseWithUnderscores);
    }

    @Test
    @Order(4)
    void testFieldAnnotations() {
        StormifyManager s = stormify();
        TestDDL.dropTable("annotated_test");
        s.executeUpdate(TestDDL.createTable("annotated_test",
                TestDDL.intPrimaryKey("id") + ", custom_col " + TestDDL.textType() +
                        ", read_only_on_create " + TestDDL.textType() +
                        ", read_only_on_update " + TestDDL.textType()));

        // @DbField(name = "custom_col")
        TableInfo info = s.getTableInfo(AnnotatedEntity.class);
        assertEquals("custom_col", info.getField("renamedField").getDbName());

        // Java transient keyword — field excluded
        assertNull(info.getField("transientByKeyword"));

        // JPA @Transient annotation — field excluded
        assertNull(info.getField("transientByAnnotation"));

        // creatable = false → not in INSERT
        AnnotatedEntity e = new AnnotatedEntity();
        e.setId(1);
        e.setRenamedField("custom");
        e.setReadOnlyOnCreate("ignored");
        e.setReadOnlyOnUpdate("saved");
        e.create();

        AnnotatedEntity found = s.findById(AnnotatedEntity.class, 1);
        assertEquals("custom", found.getRenamedField());
        assertNull(found.getReadOnlyOnCreate());
        assertEquals("saved", found.getReadOnlyOnUpdate());

        // updatable = false → not in UPDATE
        s.executeUpdate("UPDATE annotated_test SET read_only_on_create = ? WHERE id = ?", "set_directly", 1);
        found = s.findById(AnnotatedEntity.class, 1);
        found.setRenamedField("updated");
        found.setReadOnlyOnUpdate("try_change");
        found.update();

        AnnotatedEntity after = s.findById(AnnotatedEntity.class, 1);
        assertEquals("updated", after.getRenamedField());
        assertEquals("set_directly", after.getReadOnlyOnCreate());
        assertEquals("saved", after.getReadOnlyOnUpdate()); // not updated
    }

    @Test
    @Order(5)
    void testDoubleDbNames() {
        StormifyManager s = stormify();
        TestDDL.dropTable("double_db_name");
        s.executeUpdate(TestDDL.createTable("double_db_name",
                TestDDL.intColumn("id") + ", name " + TestDDL.textType()));

        DoubleDbName ddn = new DoubleDbName();
        ddn.setId(1);
        ddn.setName1("Name1");
        ddn.setName2("Name2");
        ddn.create();

        DoubleDbName test1 = stormify().findById(DoubleDbName.class, 1);
        assertEquals("Name1", test1.getName1());
        assertEquals("Name1", test1.getName2());

        ddn.update();
        DoubleDbName test2 = stormify().findById(DoubleDbName.class, 1);
        assertEquals("Name2", test2.getName1());
        assertEquals("Name2", test2.getName2());
    }

    @Test
    @Order(6)
    void testStrictMode() {
        StormifyManager s = stormify();
        TestDDL.dropTable("strict_test");
        s.executeUpdate(TestDDL.createTable("strict_test",
                TestDDL.intPrimaryKey("id") + ", name " + TestDDL.textType()));
        s.executeUpdate("INSERT INTO strict_test (id, name) VALUES (?, ?)", 1, "test");

        boolean wasStrict = s.isStrictMode();

        // Non-strict: extra columns ignored
        s.setStrictMode(false);
        TestC result = s.readOne(TestC.class, "SELECT id, name, id as bonus FROM strict_test WHERE id = ?", 1);
        assertNotNull(result);
        assertEquals("test", result.getName());

        // Strict: extra columns throw
        s.setStrictMode(true);
        assertThrows(QueryException.class, () ->
                s.readOne(TestC.class, "SELECT id, name, id as bonus FROM strict_test WHERE id = ?", 1));

        s.setStrictMode(wasStrict);
    }

    @Test
    @Order(7)
    void testBlacklistFields() {
        StormifyManager s = stormify();
        s.addBlacklistField("secret");

        TestDDL.dropTable("blacklist_test");
        s.executeUpdate(TestDDL.createTable("blacklist_test",
                TestDDL.intPrimaryKey("id") + ", name " + TestDDL.textType() + ", secret " + TestDDL.textType()));

        assertNull(s.getTableInfo(BlacklistEntity.class).getField("secret"));
        assertNotNull(s.getTableInfo(BlacklistEntity.class).getField("name"));

        BlacklistEntity e = new BlacklistEntity();
        e.setId(1);
        e.setName("visible");
        e.setSecret("hidden");
        e.create();

        assertNull(s.readOne(String.class, "SELECT secret FROM blacklist_test WHERE id = ?", 1));

        s.removeBlacklistField("secret");
    }

    @Test
    @Order(8)
    void testCustomPkResolver() {
        StormifyManager s = stormify();
        s.registerPrimaryKeyResolver(100, (table, field) -> field.equalsIgnoreCase("pk_id"));

        TestDDL.dropTable("pk_resolver_test");
        s.executeUpdate(TestDDL.createTable("pk_resolver_test",
                "pk_id INT PRIMARY KEY, name " + TestDDL.textType()));
        s.executeUpdate("INSERT INTO pk_resolver_test (pk_id, name) VALUES (?, ?)", 1, "test");

        @SuppressWarnings("unchecked")
        Map<String, Object> row = (Map) s.readOne(Map.class,
                "SELECT pk_id, name FROM pk_resolver_test WHERE pk_id = ?", 1);
        assertEquals("test", row.get("name"));
    }

    @Test
    @Order(9)
    void testInheritance() {
        StormifyManager s = stormify();
        TestDDL.dropTable("user_entity");
        s.executeUpdate(TestDDL.createTable("user_entity",
                TestDDL.intPrimaryKey("id") + ", name " + TestDDL.textType() +
                        ", email " + TestDDL.textType() + ", created_by " + TestDDL.textType()));

        UserEntity user = new UserEntity();
        user.setId(1);
        user.setName("Alice");
        user.setEmail("alice@test.com");
        user.setCreatedBy("admin");
        user.create();

        UserEntity found = s.findById(UserEntity.class, 1);
        assertEquals("Alice", found.getName());
        assertEquals("alice@test.com", found.getEmail());
        assertEquals("admin", found.getCreatedBy());

        found.setCreatedBy("system");
        found.update();
        assertEquals("system", s.findById(UserEntity.class, 1).getCreatedBy());
    }

    @Test
    @Order(10)
    void testGenerics() {
        StormifyManager s = stormify();
        TestDDL.dropTable("generic_test");
        s.executeUpdate(TestDDL.createTable("generic_test",
                TestDDL.intPrimaryKey("id") + ", value " + TestDDL.textType()));

        GenericHolder<String> holder = new GenericHolder<>();
        holder.setId(1);
        holder.setValue("hello");
        holder.create();

        GenericHolder<?> found = s.findById(GenericHolder.class, 1);
        assertEquals(1, found.getId());
        assertEquals("hello", found.getValue());
    }
}
