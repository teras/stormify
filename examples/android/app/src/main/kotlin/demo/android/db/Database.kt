package demo.android.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import onl.ycode.stormify.Stormify

/**
 * Single owner of the Stormify instance for the demo app.
 *
 * On Android the recommended pattern is to keep one [Stormify] (and one
 * [SQLiteDatabase]) for the lifetime of the process. The platform's SQLite
 * implementation is internally synchronized so the same instance can safely
 * be used from multiple threads — and Stormify holds no per-call state.
 *
 * The first call to [open] does the schema bootstrap (CREATE TABLE IF NOT
 * EXISTS) and seeds a couple of users so the UI has something to show on
 * first launch. After that, the app gets a fully wired ORM that talks to a
 * private database file under `/data/data/<pkg>/databases/stormify-demo.db`.
 */
object Database {
    @Volatile private var instance: Stormify? = null

    /**
     * Returns the (lazily initialized) shared [Stormify] instance.
     * Safe to call from any thread; first caller pays the bootstrap cost.
     */
    fun open(context: Context): Stormify {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: createInstance(context).also { instance = it }
        }
    }

    private fun createInstance(context: Context): Stormify {
        // Use the application context so the database is tied to the process,
        // not to whichever Activity happened to make the first call.
        val db: SQLiteDatabase = context.applicationContext
            .openOrCreateDatabase("stormify-demo.db", Context.MODE_PRIVATE, null)
        // Foreign keys are off by default in Android SQLite — turn them on so
        // the FK from task.user_id → user.id is enforced.
        db.execSQL("PRAGMA foreign_keys = ON")

        // Stormify(SQLiteDatabase) is the Android-specific convenience function
        // declared in stormify-android. It wraps the SQLiteDatabase in an
        // AndroidDataSource (KDBC) and returns a ready-to-use Stormify.
        val stormify = Stormify(db)

        bootstrapSchema(stormify)
        seedIfEmpty(stormify)
        return stormify
    }

    /** Idempotent schema setup — safe to call on every launch. */
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

    /** Seed two users + a couple of tasks the first time the app is launched. */
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
                description = "Tap a row — the user is loaded on demand."
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
