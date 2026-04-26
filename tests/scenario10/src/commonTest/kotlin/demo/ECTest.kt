package demo

import onl.ycode.stormify.DbField
import onl.ycode.stormify.DbTable

@DbTable
class ECTest(
    @DbField(primaryKey = true, autoIncrement = true)
    var id: Int? = null,
    var label: String = "",
)
