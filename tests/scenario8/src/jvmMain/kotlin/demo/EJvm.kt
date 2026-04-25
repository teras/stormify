package demo

import onl.ycode.stormify.DbField
import onl.ycode.stormify.DbTable

@DbTable
class EJvm(
    @DbField(primaryKey = true, autoIncrement = true)
    var id: Int? = null,
    var jvmOnly: String = "",
)
