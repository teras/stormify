# Transactions

Stormify groups multiple operations into a single transaction — either all commit together, or all roll back if anything fails. Nested transactions use database savepoints.

Two flavours:

- **Blocking** — `stormify.transaction { ... }` (top half of this page). The default.
- **Suspend** — `async.transaction { ... }` for Kotlin coroutines, with a built-in
  connection pool ([see below](#coroutines-suspend-api)).

Both share the same CRUD operations and nested-transaction semantics.

## Managing Transactions

Transactions are plain lambdas — the block is the unit of work, commit happens on
normal return, rollback on any thrown exception. Every CRUD or query call issued
through the same `Stormify` instance inside the block (including top-level
extensions and [`CRUDTable`](CRUD.md)-implementing entities) automatically shares
the transaction's connection via an ambient registry — you do not thread a
context parameter through your code.

### Basic Transaction Example

=== "Direct"

    === "Kotlin"

        ```kotlin
        stormify.transaction {
            val user = stormify.create(User(email = "test@example.com"))
            stormify.create(Profile(userId = user.id, name = "Test User"))
            stormify.update(account)
        }
        ```

    === "Java"

        ```java
        stormify.transaction(() -> {
            User user = stormify.create(new User("test@example.com"));
            stormify.create(new Profile(user.getId(), "Test User"));
            stormify.update(account);
        });
        ```

=== "Default instance"

    With a default Stormify instance registered via `stormify.asDefault()`,
    call the top-level `transaction { }` and the top-level extensions
    (`user.create()`, `"SELECT …".read<T>()`, `findById<T>(id)`, …) without any
    prefix. Every call still routes through the active transaction's connection.

    === "Kotlin"

        ```kotlin
        stormify.asDefault()

        transaction {
            val user = User(email = "test@example.com").create()
            Profile(userId = user.id).create()
        }
        ```

    === "Java"

        ```java
        // Register once at startup:
        new StormifyJ(dataSource).asDefault();

        // Then, anywhere:
        import static onl.ycode.stormify.StormifyJHelpers.*;

        transaction(() -> {
            User user = create(new User("test@example.com"));
            create(new Profile(user.getId(), "Test User"));
        });
        ```

### Returning a Value

The `transaction` block returns whatever its body returns, so you can lift a computed value out of the transaction directly.

=== "Kotlin"

    ```kotlin
    val userId: Int = stormify.transaction {
        val user = stormify.create(User(email = "test@example.com"))
        user.id
    }
    ```

=== "Java"

    ```java
    Integer userId = stormify.transaction(() -> {
        User user = stormify.create(new User("test@example.com"));
        return user.getId();
    });
    ```

In Java the value-returning overload takes a `Supplier<R>`; the `Runnable` overload (no return value) is available for fire-and-forget transactions.

## Nested Transactions

Nested calls to `transaction` on the same `Stormify` instance become savepoints automatically — the runtime consults the ambient registry and opens a savepoint when it detects an outer transaction is already active on the current thread.

=== "Kotlin"

    ```kotlin
    stormify.transaction {
        stormify.create(record1)

        stormify.transaction {          // Becomes a savepoint
            stormify.create(record2)
            // If this fails, only record2 is rolled back
        }

        stormify.create(record3)        // Still executes
    }
    ```

=== "Java"

    ```java
    stormify.transaction(() -> {
        stormify.create(record1);

        stormify.transaction(() -> {    // Becomes a savepoint
            stormify.create(record2);
            // If this fails, only record2 is rolled back
        });

        stormify.create(record3);       // Still executes
    });
    ```

## Extracting Transaction Logic

For complex business logic, extract operations into reusable helpers.

### Kotlin — plain functions

```kotlin
fun registerUser(stormify: Stormify, email: String, name: String) {
    val user = stormify.create(User(email = email))
    stormify.create(Profile(userId = user.id, name = name))
    stormify.create(AuditLog(action = "User registered", userId = user.id))
}

fun transferFunds(stormify: Stormify, from: Account, to: Account, amount: Double) {
    require(from.balance >= amount) { "Insufficient funds" }
    stormify.update(from.copy(balance = from.balance - amount))
    stormify.update(to.copy(balance = to.balance + amount))
    stormify.create(Transaction(fromId = from.id, toId = to.id, amount = amount))
}

// Usage
stormify.transaction {
    registerUser(stormify, "alice@example.com", "Alice")
    transferFunds(stormify, accountA, accountB, 100.0)
}
```

If you've called `stormify.asDefault()`, the helpers can just use the top-level extensions (`user.create()` etc.) and skip the `stormify` parameter entirely.

### Java — service classes

```java
class UserService {
    private final StormifyJ stormify;

    UserService(StormifyJ stormify) { this.stormify = stormify; }

    void registerUser(String email, String name) {
        User user = stormify.create(new User(email));
        stormify.create(new Profile(user.getId(), name));
    }
}

stormify.transaction(() -> {
    UserService userService = new UserService(stormify);
    userService.registerUser("alice@example.com", "Alice");
});
```

All helpers naturally participate in whichever enclosing transaction is active, because every Stormify operation consults the ambient registry.

## Coroutines (Suspend API)

Stormify provides an optional suspend-based transaction API for Kotlin coroutine projects.
All database operations run on the IO dispatcher, and coroutine cancellation is wired to
the underlying database cancel primitive.

### Extra Dependency

The coroutines API requires `kotlinx-coroutines-core` as a runtime dependency. It is
**not** pulled transitively — you must add it yourself:

=== "Gradle (Kotlin)"

    ```kotlin
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    ```

=== "Maven"

    ```xml
    <dependency>
        <groupId>org.jetbrains.kotlinx</groupId>
        <artifactId>kotlinx-coroutines-core</artifactId>
        <version>1.10.2</version>
    </dependency>
    ```

### Setup

Create a `SuspendStormify` from an existing `Stormify` instance:

```kotlin
import onl.ycode.stormify.Stormify
import onl.ycode.stormify.coroutines.*

val stormify = Stormify(dataSource)
val async = stormify.suspending(PoolConfig(
    minConnections = 2,
    maxConnections = 10,
))
```

The `suspending()` extension creates an internal connection pool configured by `PoolConfig`.
The blocking `Stormify` instance continues to work independently — `SuspendStormify`
is purely additive. You can use both APIs side-by-side.

### Suspend Transactions

```kotlin
async.transaction {
    val user = stormify.create(User(email = "test@example.com"))
    stormify.create(Profile(userId = user.id, name = "Test User"))
}
```

All operations inside the block run on the IO dispatcher. The transaction commits
on success and rolls back on any exception. Convenience calls on the underlying
`Stormify` instance transparently join the transaction even after a dispatcher
hop (`delay`, `withContext`, etc.) on JVM / Android — the runtime re-publishes
the ambient binding on whichever thread resumes the coroutine.

### Nested Suspend Transactions

Calling `transaction` from within another `transaction` on the same coroutine reuses the
outer connection via a savepoint:

```kotlin
async.transaction {
    stormify.create(record1)
    async.transaction {
        // Uses savepoint — rollback only affects this inner block
        stormify.create(record2)
    }
}
```

### Cancellation

When a coroutine running a transaction is cancelled, the pool dispatches `Connection.cancel()`
which maps to the driver's native async-cancel primitive:

| Platform | Cancel mechanism |
|----------|-----------------|
| Native (PostgreSQL) | `PQcancel` |
| Native (SQLite) | `sqlite3_interrupt` |
| Native (MariaDB) | `mariadb_cancel` |
| Native (Oracle) | `dpiConn_breakExecution` |
| Native (MSSQL) | `dbcancel` |
| JVM | `Statement.cancel()` (JDBC) |

The blocked query returns with an error, the transaction rolls back, and the connection
is evicted from the pool.

### Pool Configuration

`PoolConfig` controls pool behavior:

```kotlin
PoolConfig(
    minConnections = 2,          // Pre-warmed connections
    maxConnections = 10,         // Hard upper bound
    acquireTimeout = 30.seconds, // Wait time when pool is saturated
    idleTimeout = 5.minutes,     // Evict idle connections
    maxLifetime = 30.minutes,    // Retire long-lived connections
    validationQuery = "SELECT 1" // Optional health check
)
```

When the pool is saturated, callers **suspend** (not block) until a connection is released.

### Pool Statistics

Monitor pool health via `async.stats`:

```kotlin
val stats = async.stats
println("total=${stats.total} inUse=${stats.inUse} idle=${stats.idle}")
```

### Shutdown

```kotlin
async.close()  // Waits up to shutdownTimeout (default 30s), then force-closes remaining
```
