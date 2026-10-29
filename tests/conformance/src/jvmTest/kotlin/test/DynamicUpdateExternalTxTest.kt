package test

import onl.ycode.kdbc.Connection
import onl.ycode.kdbc.DataSource
import onl.ycode.stormify.Stormify
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A connection whose transaction belongs to someone else: Stormify neither opened it nor
 * can end it. Auto-commit stays off and commit, rollback and close are the owner's.
 */
private class ExternalTxConnection(private val real: Connection) : Connection by real {
    override fun setAutoCommit(autoCommit: Boolean) {}
    override fun getAutoCommit(): Boolean = false
    override fun commit() {}
    override fun close() {}
}

/**
 * With [Stormify.dynamicUpdate], an update on a connection whose transaction is managed
 * outside Stormify must not make the written values the reference: the owner may still
 * roll the statement back, and the next update would then skip the columns the database
 * never kept.
 */
class DynamicUpdateExternalTxTest {
    @Test
    fun externalRollbackDoesNotLoseTheChange() = TestHelper.withDb("DYN-EXTERNAL-TX") { base ->
        TestDDL.dropTable("dyn_entity")
        TestDDL.dropTable("test")
        base.executeUpdate(TestDDL.createTable("test", "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))
        base.executeUpdate(TestDDL.createTable("dyn_entity",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}, ${TestDDL.intColumn("amount")}, " +
                    "payload ${TestDDL.blobType()}, ${TestDDL.intColumn("parent")}"))
        base.executeUpdate("INSERT INTO dyn_entity (id, name, amount, payload, parent) VALUES (1, 'N1', 10, NULL, NULL)")

        val owned = base.dataSource.getConnection()
        try {
            owned.setAutoCommit(false)
            val external = ExternalTxConnection(owned)
            val s = Stormify(object : DataSource {
                override fun getConnection(): Connection = external
            })
            s.registerPrimaryKeyResolver(0) { _, field -> field.lowercase().startsWith("id") }
            s.dynamicUpdate = true

            val e = s.findById<DynEntity>(1)!!
            e.amount = 1
            s.update(e)
            owned.rollback()   // the owner discards the statement

            s.update(e)        // the change is still pending: it must be written again
            owned.commit()
        } finally {
            owned.close()
        }
        assertEquals(1, base.findById<DynEntity>(1)!!.amount)
    }
}
