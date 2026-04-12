package demo

import onl.ycode.stormify.DbField
import onl.ycode.stormify.DbTable

/**
 * User entity — a plain class without AutoTable.
 * When obtained as a reference (e.g. from Task.user), only the ID is set.
 * Call stormify.populate(user) explicitly to load the remaining fields.
 *
 * Compare with Task, which extends AutoTable and loads fields automatically.
 */
@DbTable
class User(
    @DbField(primaryKey = true, autoIncrement = true)
    var id: Int? = null,
    var name: String? = null,
    var email: String? = null,
) {
    override fun toString() = "User(id=$id, name=$name, email=$email)"
}
