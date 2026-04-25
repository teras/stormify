package demo

import onl.ycode.stormify.DbField
import onl.ycode.stormify.DbTable

@DbTable
class EJ(
    @DbField(primaryKey = true, autoIncrement = true)
    var id: Int? = null,
    var payload: String = "",
)
