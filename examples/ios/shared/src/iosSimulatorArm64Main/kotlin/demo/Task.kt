package demo

import onl.ycode.stormify.AutoTable
import onl.ycode.stormify.DbTable
import onl.ycode.stormify.DbField
import onl.ycode.stormify.db

/**
 * Task entity using Stormify annotations and AutoTable for lazy-loaded references.
 * The [user] field is a reference to a User entity — Stormify resolves it
 * automatically from the user_id foreign key column.
 *
 * Properties delegated with `by db()` trigger lazy-loading when accessed.
 */
@DbTable
class Task(
    @DbField(primaryKey = true, autoIncrement = true)
    var id: Int? = null
) : AutoTable() {
    var title: String by db("")
    var description: String by db("")
    var isCompleted: Boolean by db(false)
    var priority: Priority? by db(null)
    @DbField(name = "user_id")
    var user: User? by db(null)

    override fun toString() = "Task(id=$id, title=$title, completed=$isCompleted, priority=$priority, user=$user)"
}
