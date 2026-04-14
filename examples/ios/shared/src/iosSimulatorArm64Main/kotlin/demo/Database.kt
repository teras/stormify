package demo

import db.stormify.GeneratedEntities
import onl.ycode.kdbc.KdbcDataSource
import onl.ycode.stormify.Stormify
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSUserDomainMask

/**
 * Single owner of the Stormify instance for the iOS demo app.
 *
 * Same pattern as the Android example: one Stormify instance for the lifetime
 * of the process, schema bootstrap on first launch, seed data if empty.
 */
object Database {
    private var instance: Stormify? = null

    fun open(): Stormify {
        instance?.let { return it }
        val s = createInstance()
        instance = s
        return s
    }

    private fun createInstance(): Stormify {
        val docs = NSSearchPathForDirectoriesInDomains(
            NSDocumentDirectory, NSUserDomainMask, true
        ).first() as String
        val dbPath = "$docs/stormify-demo.db"

        val ds = KdbcDataSource("jdbc:sqlite:$dbPath")
        val stormify = Stormify(ds, GeneratedEntities)

        bootstrapSchema(stormify)
        seedIfEmpty(stormify)
        return stormify
    }

    private fun bootstrapSchema(stormify: Stormify) {
        stormify.executeUpdate(
            """
            CREATE TABLE IF NOT EXISTS user (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                email TEXT NOT NULL
            )
            """.trimIndent()
        )
        stormify.executeUpdate(
            """
            CREATE TABLE IF NOT EXISTS task (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                description TEXT NOT NULL DEFAULT '',
                is_completed INTEGER NOT NULL DEFAULT 0,
                priority INTEGER NOT NULL DEFAULT 20,
                user_id INTEGER NOT NULL REFERENCES user(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
    }

    private fun seedIfEmpty(stormify: Stormify) {
        val userCount = stormify.readOne<Long>("SELECT COUNT(*) FROM user") ?: 0L
        if (userCount > 0) return

        stormify.transaction {
            val alice = create(User(name = "Alice", email = "alice@example.com"))
            val bob = create(User(name = "Bob", email = "bob@example.com"))

            create(Task().apply {
                title = "Wire up Stormify"
                description = "Drop the library into the app and run it."
                priority = Priority.HIGH
                user = alice
            })
            create(Task().apply {
                title = "Show off lazy refs"
                description = "Tap a row \u2014 the user is loaded on demand."
                priority = Priority.MEDIUM
                user = alice
            })
            create(Task().apply {
                title = "Review the example"
                description = "Skim the code; it should read top-to-bottom."
                priority = Priority.LOW
                user = bob
            })
        }
    }
}

// === Swift-callable API ===

fun getAllTasks(): List<Task> {
    val s = Database.open()
    return s.findAll<Task>().onEach { it.user?.let { u -> s.populate(u) } }
}

fun getAllUsers(): List<User> = Database.open().findAll()

fun addTask(title: String, description: String, priority: Priority, owner: User) {
    Database.open().transaction {
        create(Task().apply {
            this.title = title
            this.description = description
            this.priority = priority
            this.user = owner
        })
    }
}

fun toggleCompleted(task: Task) {
    task.isCompleted = !task.isCompleted
    Database.open().update(task)
}

fun deleteTask(task: Task) {
    Database.open().delete(task)
}
