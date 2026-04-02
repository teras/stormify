package onl.ycode.stormify;

// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

import onl.ycode.stormify.pojos.*;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static onl.ycode.stormify.StormifyManager.stormify;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CrudTest extends BaseDbTest {

    @Test
    @Order(1)
    void testBasicCrud() {
        StormifyManager s = stormify();

        TestDDL.dropTable("child");
        TestDDL.dropTable("test");
        s.executeUpdate(TestDDL.createTable("test",
                TestDDL.intPrimaryKey("id") + ", name " + TestDDL.textType()));
        s.executeUpdate(TestDDL.createTable("child",
                TestDDL.intPrimaryKey("id") + ", name " + TestDDL.textType() + ", " +
                        TestDDL.intColumn("parent") + ", " + TestDDL.foreignKey("parent", "test", "id")));

        // Create
        TestC tst = new TestC(1, "Test1");
        tst.create();
        assertEquals("[TestC(id=1, name=Test1)]", s.findAll(TestC.class, null).toString());

        // Update
        tst.setName("Test2");
        tst.update();
        assertEquals("[TestC(id=1, name=Test2)]", s.findAll(TestC.class, null).toString());

        // Create second
        new TestC(2, "Test2").create();
        assertEquals("[TestC(id=1, name=Test2), TestC(id=2, name=Test2)]",
                s.findAll(TestC.class, "ORDER BY id").toString());

        // Delete
        tst.delete();
        assertEquals("[TestC(id=2, name=Test2)]", s.findAll(TestC.class, null).toString());

        // Populate
        for (int id = 3; id <= 5; id++) new TestC(id, "Test" + id).create();
        tst.setId(2);
        tst.setName(null);
        String before = tst.toString();
        tst.populate();
        assertEquals("TestC(id=2, name=null)", before);
        assertEquals("TestC(id=2, name=Test2)", tst.toString());
    }

    @Test
    @Order(2)
    void testDualKey() {
        StormifyManager s = stormify();

        TestDDL.dropTable("dual_key");
        s.executeUpdate(TestDDL.createTable("dual_key",
                TestDDL.intNotNull("id1") + ", " + TestDDL.intNotNull("id2") +
                        ", data " + TestDDL.textType() + ", PRIMARY KEY (id1, id2)"));

        DualKey dk1 = new DualKey(1, 2, "Data1");
        dk1.create();
        DualKey dk2 = new DualKey(3, 42, "Data2");
        dk2.create();
        assertEquals("[DualKey(id1=1, id2=2, data=Data1), DualKey(id1=3, id2=42, data=Data2)]",
                s.findAll(DualKey.class, "ORDER BY id1").toString());

        dk1.setData("Data3");
        dk1.update();
        dk2.delete();
        assertEquals("[DualKey(id1=1, id2=2, data=Data3)]", s.findAll(DualKey.class, null).toString());

        DualKey dk1P = new DualKey(1, 2);
        dk1P.populate();
        assertEquals(dk1, dk1P);
    }

    @Test
    @Order(3)
    void testAutoIncrement() {
        assumeTrue(TestDDL.supportsAutoIncrement(),
                "Skipping auto-increment test for " + stormify().getSqlDialect());
        StormifyManager s = stormify();

        String autoPk = TestDDL.autoIncrementPrimaryKey("id");
        TestDDL.dropTable("auto_increment");
        s.executeUpdate(TestDDL.createTable("auto_increment", autoPk + ", name " + TestDDL.textType()));

        AutoIncrement a1 = new AutoIncrement("Test1");
        a1.create();
        AutoIncrement a2 = new AutoIncrement("Test2");
        a2.create();
        assertEquals(1, a1.getId());
        assertEquals(2, a2.getId());
        assertEquals(2, (int) s.readOne(int.class, "SELECT COUNT(*) FROM auto_increment"));
    }

    @Test
    @Order(4)
    void testAutoTable() {
        StormifyManager s = stormify();

        TestDDL.dropTable("auto_child");
        TestDDL.dropTable("auto_parent");
        s.executeUpdate(TestDDL.createTable("auto_parent",
                TestDDL.intPrimaryKey("id") + ", data " + TestDDL.textType() + ", other " + TestDDL.textType()));
        s.executeUpdate(TestDDL.createTable("auto_child",
                TestDDL.intPrimaryKey("id") + ", data " + TestDDL.textType() + ", " +
                        TestDDL.intColumn("parent") + ", " + TestDDL.foreignKey("parent", "auto_parent", "id")));

        AutoParent parent = new AutoParent();
        parent.setData("I am a parent");
        parent.setOther("other fields");
        parent.setId(17);
        parent.create();

        AutoChild child = new AutoChild();
        child.setData("I am a child");
        child.setParent(parent);
        child.setId(23);
        child.create();

        AutoChild ch = s.findAll(AutoChild.class, null).get(0);
        assertEquals("I am a child", ch.getData());
        assertEquals("I am a parent", ch.getParent().getData());

        // More children
        AutoChild c2 = new AutoChild(); c2.setData("child 2"); c2.setParent(parent); c2.setId(2); c2.create();
        AutoChild c3 = new AutoChild(); c3.setData("child 3"); c3.setParent(parent); c3.setId(3); c3.create();

        List<Integer> childIds = new ArrayList<>();
        for (AutoChild ac : parent.getChildren()) childIds.add(ac.getId());
        Collections.sort(childIds);
        assertEquals("[2, 3, 23]", childIds.toString());
    }

    @Test
    @Order(5)
    void testBatchCreate() {
        StormifyManager s = stormify();
        TestDDL.dropTable("test");
        s.executeUpdate(TestDDL.createTable("test",
                TestDDL.intPrimaryKey("id") + ", name " + TestDDL.textType()));

        List<TestC> items = new ArrayList<>();
        for (int i = 1; i <= 100; i++) items.add(new TestC(i, "Item" + i));
        s.create(items);

        assertEquals(100, (int) s.readOne(int.class, "SELECT COUNT(*) FROM test"));
        assertEquals("Item50", s.findById(TestC.class, 50).getName());
    }

    @Test
    @Order(6)
    void testDeleteNonExistent() {
        StormifyManager s = stormify();
        TestDDL.dropTable("error_test");
        s.executeUpdate(TestDDL.createTable("error_test",
                TestDDL.intPrimaryKey("id") + ", name " + TestDDL.textType()));

        new TestC(999, "ghost").delete(); // no exception expected
    }

    @Test
    @Order(7)
    void testDuplicateKeyInsert() {
        StormifyManager s = stormify();
        TestDDL.dropTable("test");
        s.executeUpdate(TestDDL.createTable("test",
                TestDDL.intPrimaryKey("id") + ", name " + TestDDL.textType()));

        new TestC(1, "first").create();
        assertThrows(QueryException.class, () -> new TestC(1, "duplicate").create());
    }
}
