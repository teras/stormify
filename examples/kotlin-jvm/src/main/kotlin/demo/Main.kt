package demo

import onl.ycode.stormify.Stormify
import org.sqlite.SQLiteDataSource
import java.io.File

fun main() {
    // Clean up any previous run
    File("build/demo.db").delete()

    // Create a SQLite DataSource and initialize Stormify
    val ds = SQLiteDataSource().apply { url = "jdbc:sqlite:build/demo.db" }
    val stormify = Stormify(ds).asDefault()

    // === Schema Setup (Low-Level SQL API) ===
    println("=== Schema Setup ===")
    stormify.executeUpdate(
        """CREATE TABLE user (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL,
            email TEXT NOT NULL)"""
    )
    stormify.executeUpdate(
        """CREATE TABLE task (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            title TEXT NOT NULL,
            description TEXT,
            is_completed INTEGER NOT NULL DEFAULT 0,
            priority INTEGER,
            user_id INTEGER NOT NULL REFERENCES user(id))"""
    )
    println("Tables created.\n")

    // === Create (ORM) ===
    println("=== Creating Users ===")
    stormify.transaction {
        val alice = create(User(name = "Alice", email = "alice@example.com"))
        println("Created: $alice")

        val bob = create(User(name = "Bob", email = "bob@example.com"))
        println("Created: $bob")

        println("\n=== Creating Tasks ===")
        val t1 = Task().apply { title = "Set up database"; description = "Configure schema and indexes"; priority = Priority.HIGH; user = alice }
        create(t1)
        println("Created: $t1")

        val t2 = Task().apply { title = "Write documentation"; description = "API reference and examples"; priority = Priority.MEDIUM; user = alice }
        create(t2)
        println("Created: $t2")

        val t3 = Task().apply { title = "Review pull request"; description = "Check code style and tests"; priority = Priority.LOW; user = bob }
        create(t3)
        println("Created: $t3")
    }

    // === Read (ORM) ===
    println("\n=== Find by ID ===")
    val foundUser = stormify.findById<User>(1)
    println("Found user: $foundUser")

    val foundTask = stormify.findById<Task>(2)
    println("Found task: $foundTask")

    // === Reference loading demo ===
    // Task extends AutoTable, so accessing task.user auto-populates the Task.
    // But User is a plain class — it comes back with only the ID filled in.
    // We must explicitly populate it to get the remaining fields.
    println("\n=== Reference Loading ===")
    val userRef = foundTask!!.user           // auto-populated by Task (AutoTable)
    println("Before populate: $userRef")     // User(id=1, name=, email=)
    stormify.populate(userRef!!)
    println("After populate:  $userRef")     // User(id=1, name=Alice, email=alice@example.com)

    println("\n=== Find All ===")
    val allTasks = stormify.findAll<Task>()
    allTasks.forEach { println("  $it") }

    // === Update (ORM) ===
    println("\n=== Update Task ===")
    foundTask.isCompleted = true
    stormify.update(foundTask)
    val updated = stormify.findById<Task>(foundTask.id!!)
    println("Updated: $updated")

    // === Delete (ORM) ===
    println("\n=== Delete Task ===")
    val toDelete = stormify.findById<Task>(3)!!
    println("Deleting: $toDelete")
    stormify.delete(toDelete)
    val remaining = stormify.findAll<Task>()
    println("Remaining tasks: ${remaining.size}")

    // === Transaction Rollback ===
    println("\n=== Transaction Rollback Demo ===")
    val countBefore = stormify.findAll<Task>().size
    try {
        stormify.transaction {
            val u = findById<User>(1)
            create(Task().apply { title = "Temporary task"; description = "..."; priority = Priority.LOW; user = u })
            println("Task created inside transaction")
            throw RuntimeException("Something went wrong!")
        }
    } catch (e: RuntimeException) {
        println("Transaction failed: ${e.message}")
    }
    val countAfter = stormify.findAll<Task>().size
    println("Tasks before: $countBefore, after: $countAfter (unchanged)")

    // === Raw SQL with JOIN (Low-Level API) ===
    println("\n=== Raw SQL JOIN Query ===")
    val results = stormify.read<Map<String, Any?>>(
        "SELECT u.name AS user_name, t.title AS task_title, t.is_completed FROM task t JOIN user u ON t.user_id = u.id"
    )
    results.forEach { println("  $it") }

    println("\nDone!")
    File("build/demo.db").delete()
}
