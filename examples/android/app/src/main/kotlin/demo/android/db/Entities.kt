package demo.android.db

import onl.ycode.stormify.AutoTable
import onl.ycode.stormify.DbField
import onl.ycode.stormify.DbTable
import onl.ycode.stormify.DbValue
import onl.ycode.stormify.db

/**
 * Entities for the Stormify Android demo.
 *
 * Two tables, one foreign-key relationship — small enough to read at a glance,
 * rich enough to demonstrate the ORM features that matter on Android:
 *
 * - `@DbTable` maps a class to a table; column names default to snake_case.
 * - `@DbField(primaryKey = true, autoIncrement = true)` flags the PK column.
 * - [Priority] uses [DbValue] to persist enums as their `dbValue` int instead
 *   of the ordinal — safer if the enum order ever changes.
 * - [Task] extends [AutoTable] so the `user` reference is lazy-loaded the
 *   first time it is accessed (Stormify handles the SELECT under the hood).
 *
 * Because Android cannot rely on full reflection, the `annproc` KSP processor
 * declared in `app/build.gradle.kts` walks these classes at compile time and
 * generates a `TableInfo` registration source. The registration runs the first
 * time we touch the database (see [Database]).
 */

enum class Priority(override val dbValue: Int) : DbValue {
    LOW(10),
    MEDIUM(20),
    HIGH(30),
}

@DbTable
class User(
    @DbField(primaryKey = true, autoIncrement = true)
    var id: Int? = null,
    var name: String? = null,
    var email: String? = null,
) {
    override fun toString(): String = "User(id=$id, name=$name, email=$email)"
}

@DbTable
class Task(
    @DbField(primaryKey = true, autoIncrement = true)
    var id: Int? = null,
) : AutoTable() {
    var title: String by db("")
    var description: String by db("")

    var isCompleted: Boolean by db(false)

    var priority: Priority by db(Priority.MEDIUM)

    /**
     * Foreign key to [User]. Stormify maps `user` → column `user_id` because of
     * the @DbField annotation, and resolves the related User automatically the
     * first time the property is accessed (lazy loading via [AutoTable]).
     */
    @DbField(name = "user_id")
    var user: User? by db(null)

    override fun toString(): String =
        "Task(id=$id, title=$title, completed=$isCompleted, priority=$priority)"
}
