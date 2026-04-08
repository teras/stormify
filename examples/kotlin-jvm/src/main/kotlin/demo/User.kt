package demo

import onl.ycode.stormify.AutoTable
import onl.ycode.stormify.DbField
import onl.ycode.stormify.db

/**
 * User entity using Stormify annotations and AutoTable.
 * On JVM, JPA annotations are also supported — see the Java example.
 */
class User(
    @DbField(primaryKey = true, autoIncrement = true)
    var id: Int? = null
) : AutoTable() {
    var name: String by db("")
    var email: String by db("")

    override fun toString() = "User(id=$id, name=$name, email=$email)"
}
