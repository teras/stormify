package demo

import onl.ycode.stormify.generated.Tables
import onl.ycode.stormify.generated.TablesTest
import kotlin.test.Test
import kotlin.test.assertNotNull

class CommonTestChecks {
    @Test
    fun productionTablesVisible() {
        assertNotNull(Tables.EC_)
    }

    @Test
    fun testTablesVisible() {
        assertNotNull(TablesTest.ECTest_)
    }
}
