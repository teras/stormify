package demo

import onl.ycode.stormify.DbField
import onl.ycode.stormify.DbTable

@DbTable
class EA(
    @DbField(primaryKey = true, autoIncrement = true)
    var id: Int? = null,
    var androidOnly: String = "",
)
