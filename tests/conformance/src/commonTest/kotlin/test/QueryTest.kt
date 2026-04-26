package test

import onl.ycode.stormify.Stormify
import kotlin.test.*

open class QueryTest {
    private fun withDb(name: String, test: (Stormify) -> Unit) = TestHelper.withDb(name, test)

    @Test
    fun testFindAllVariations() = withDb("FIND-ALL") { s ->
        TestDDL.dropTable("findall_test")
        s.executeUpdate(TestDDL.createTable("findall_test",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))
        s.executeUpdate("INSERT INTO findall_test (id, name) VALUES (?, ?)", 1, "Alice")
        s.executeUpdate("INSERT INTO findall_test (id, name) VALUES (?, ?)", 2, "Bob")
        s.executeUpdate("INSERT INTO findall_test (id, name) VALUES (?, ?)", 3, "Charlie")

        assertEquals(3, s.read<TestC>("SELECT * FROM findall_test").size)
        assertEquals(2, s.read<TestC>("SELECT * FROM findall_test WHERE id > ?", 1).size)
        assertEquals(0, s.read<TestC>("SELECT * FROM findall_test WHERE id > ?", 100).size)

        assertEquals(3, s.readOne<Int>("SELECT COUNT(*) FROM findall_test"))
        assertEquals("[Alice, Bob, Charlie]", s.read<String>("SELECT name FROM findall_test ORDER BY name").toString())
    }

    @Test
    fun testReadOneNotFound() = withDb("NOT-FOUND") { s ->
        TestDDL.dropTable("test")
        s.executeUpdate(TestDDL.createTable("test",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))

        assertNull(s.readOne<String>("SELECT name FROM test WHERE id = ?", 999))
        assertNull(s.findById<TestC>(999))
    }

    @Test
    fun testOnTheFlyFields() = withDb("ON-THE-FLY") { s ->
        TestDDL.dropTable("fly_test")
        s.executeUpdate(TestDDL.createTable("fly_test",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))
        s.executeUpdate("INSERT INTO fly_test (id, name) VALUES (?, ?)", 1, "Alice")

        val withLabel = s.readOne<FlyView>("SELECT id, name, name as label FROM fly_test WHERE id = ?", 1)!!
        assertEquals("Alice", withLabel.name)
        assertEquals("Alice", withLabel.label)

        val partial = s.readOne<FlyView>("SELECT id, name FROM fly_test WHERE id = ?", 1)!!
        assertNull(partial.label)

        val onlyLabel = s.readOne<FlyView>("SELECT id, name as label FROM fly_test WHERE id = ?", 1)!!
        assertNull(onlyLabel.name)
        assertEquals("Alice", onlyLabel.label)

        val computed = s.readOne<FlyView>(TestDDL.selectExpr("42 as id, 'hello' as name, 'world' as label"))!!
        assertEquals(42, computed.id)
        assertEquals("hello", computed.name)
        assertEquals("world", computed.label)
    }

    @Test
    fun testAggregatesViaMap() = withDb("AGGREGATES") { s ->
        TestDDL.dropTable("agg_test")
        s.executeUpdate(TestDDL.createTable("agg_test",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))
        s.executeUpdate("INSERT INTO agg_test (id, name) VALUES (?, ?)", 1, "Alice")
        s.executeUpdate("INSERT INTO agg_test (id, name) VALUES (?, ?)", 2, "Bob")
        s.executeUpdate("INSERT INTO agg_test (id, name) VALUES (?, ?)", 3, "Charlie")

        @Suppress("UNCHECKED_CAST")
        val agg = s.readOne<Map<String, Any?>>("SELECT COUNT(*) as cnt, MIN(id) as min_id, MAX(id) as max_id FROM agg_test") as Map<String, Any?>
        assertEquals(3, (agg["cnt"] as Number).toInt())
        assertEquals(1, (agg["min_id"] as Number).toInt())
        assertEquals(3, (agg["max_id"] as Number).toInt())
    }

    @Test
    fun testJoinsViaMap() = withDb("JOINS") { s ->
        TestDDL.dropTable("order_items")
        TestDDL.dropTable("orders")
        s.executeUpdate(TestDDL.createTable("orders",
            "${TestDDL.intPrimaryKey("id")}, customer ${TestDDL.textType()}"))
        s.executeUpdate(TestDDL.createTable("order_items",
            "${TestDDL.intPrimaryKey("id")}, order_id INT, product ${TestDDL.textType()}"))

        s.executeUpdate("INSERT INTO orders (id, customer) VALUES (?, ?)", 1, "Alice")
        s.executeUpdate("INSERT INTO order_items (id, order_id, product) VALUES (?, ?, ?)", 1, 1, "Widget")
        s.executeUpdate("INSERT INTO order_items (id, order_id, product) VALUES (?, ?, ?)", 2, 1, "Gadget")

        val rows = s.read<Map<String, Any?>>(
            "SELECT o.customer, i.product FROM orders o JOIN order_items i ON o.id = i.order_id ORDER BY i.product")
        assertEquals(2, rows.size)
        @Suppress("UNCHECKED_CAST")
        assertEquals("Gadget", (rows[0] as Map<String, Any?>)["product"])
    }

    @Test
    fun testMapRead() = withDb("MAP") { s ->
        TestDDL.dropTable("map_test")
        s.executeUpdate(TestDDL.createTable("map_test",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))
        s.executeUpdate("INSERT INTO map_test (id, name) VALUES (?, ?)", 1, "Alice")
        s.executeUpdate("INSERT INTO map_test (id, name) VALUES (?, ?)", 2, "Bob")

        @Suppress("UNCHECKED_CAST")
        val row = s.readOne<Map<String, Any?>>("SELECT id, name FROM map_test WHERE id = ?", 1)
            as Map<String, Any?>
        assertEquals("Alice", row["name"])

        val rows = s.read<Map<String, Any?>>("SELECT id, name FROM map_test ORDER BY id")
        assertEquals(2, rows.size)

        @Suppress("UNCHECKED_CAST")
        val agg = s.readOne<Map<String, Any?>>("SELECT COUNT(*) as cnt FROM map_test") as Map<String, Any?>
        assertEquals(2, (agg["cnt"] as Number).toInt())
    }
}
