package demo

import onl.ycode.stormify.AutoTable
import onl.ycode.stormify.DbField
import onl.ycode.stormify.db

/**
 * User entity using Stormify annotations and AutoTable.
 * In a multiplatform project, Stormify annotations are used in common code
 * so that the same entity definitions work on both JVM and native targets.
 */
class User(
    @DbField(primaryKey = true, autoIncrement = true)
    var id: Int? = null
) : AutoTable() {
    var name: String by db("")
    var email: String by db("")

    override fun toString() = "User(id=$id, name=$name, email=$email)"
}
