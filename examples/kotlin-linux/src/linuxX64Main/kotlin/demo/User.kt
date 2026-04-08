package demo

import onl.ycode.stormify.AutoTable
import onl.ycode.stormify.DbField
import onl.ycode.stormify.db

/**
 * User entity using Stormify annotations and AutoTable.
 * On native platforms, JPA annotations are not available,
 * so Stormify's own annotations are used instead.
 */
class User(
    @DbField(primaryKey = true, autoIncrement = true)
    var id: Int? = null
) : AutoTable() {
    var name: String by db("")
    var email: String by db("")

    override fun toString() = "User(id=$id, name=$name, email=$email)"
}
