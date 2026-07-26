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
extensions and [`CRUDTable`](CRUD.md)-implementing entities) automatically joins
the transaction. The same call works identically inside or outside a transaction.

Failures are always reported as `SQLException` with the original throwable kept
as `cause` — whether the failure came from the database or from your own code
inside the block. (Coroutine cancellation in the suspend API is the one
exception: it propagates unwrapped.)

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

    With a [default instance](Configuration.md#default-instance) registered,
    call the top-level `transaction { }` and the top-level extensions
    (`user.create()`, `"SELECT …".read<T>()`, `findById<T>(id)`, …) without any
    prefix. Every call still participates in the active transaction.

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
        StormifyJ stormify = StormifyJ.getDefault();
        stormify.transaction(() -> {
            User user = stormify.create(new User("test@example.com"));
            stormify.create(new Profile(user.getId(), "Test User"));
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

Nested calls to `transaction` on the same `Stormify` instance become savepoints automatically — a failure inside the inner block rolls back only its work, not the outer transaction.

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

All helpers naturally participate in whichever enclosing transaction is active — every Stormify operation picks up the current transaction automatically.

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

Every `Stormify` instance carries a lazily-created connection pool behind its
`suspending` property:

```kotlin
import onl.ycode.stormify.Stormify
import onl.ycode.stormify.coroutines.*

val stormify = Stormify(dataSource)          // pool created on first suspending access
val async = stormify.suspending              // always the same shared pool
```

Pool tuning goes through the `poolConfig` constructor parameter (defaults are
sensible — tune only if needed):

```kotlin
val stormify = Stormify(dataSource, poolConfig = PoolConfig(
    minConnections = 2,
    maxConnections = 10,
))
```

The blocking `Stormify` instance continues to work independently — `SuspendStormify`
is purely additive. You can use both APIs side-by-side.

!!! note "Pooling is suspend-only, by design"
    Each pooled connection is pinned to a dedicated worker thread (Native drivers
    and Android's `SQLiteSession` forbid cross-thread connection use), so the
    blocking API can never borrow from this pool — there is no blocking-API pool
    and no `PooledDataSource`. If you want pooling, write suspend code.

If you need a second, independent pool (e.g. an isolated reporting workload),
construct one explicitly:

```kotlin
val reporting = SuspendStormify(stormify, PoolConfig(maxConnections = 2))
```

!!! warning "Already pooling DataSource (e.g. HikariCP)"
    If your `DataSource` already pools connections, the suspend pool holds up to
    `maxConnections` of its connections permanently. Keep `maxConnections` well
    below the outer pool's size so other consumers (migrations, health checks)
    are not starved, and raise or disable the outer pool's leak-detection
    threshold — long-lived borrows here are normal, not leaks.

### Two Scopes

Inside a scope, every database operation uses the scope's connection — the
blocking API is called unchanged and joins the borrowed connection transparently:

- **`withConnection { }`** — borrows a pooled connection, auto-commit stays on.
  For reads and any work that does not need atomicity. Exceptions propagate
  **unchanged** and do not evict the connection.
- **`transaction { }`** — the same borrow plus BEGIN/COMMIT (rollback on any
  throwable). For writes that need atomicity.

```kotlin
async.withConnection {
    val suppliers = stormify.read<Supplier>("SELECT * FROM supplier WHERE active = ?", true)
}
```

Application-level exceptions inside `withConnection` (validation errors, not-found,
constraint violations in auto-commit) reach the caller unwrapped — no `SQLException`
wrapping on this path — and the borrowed connection returns to the pool healthy.
Only coroutine cancellation evicts a connection.

Scopes nest in every combination on the same coroutine lineage:

| inner ↓ / outer → | `withConnection` | `transaction` |
|---|---|---|
| `withConnection` | reuse the connection | reuse, autoCommit untouched |
| `transaction` | full BEGIN/COMMIT on the ambient connection | savepoint |

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
hop (`delay`, `withContext`, etc.) on JVM / Android.

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
A server that borrows one connection per request can therefore serve at most
`maxConnections` requests concurrently — further requests suspend up to
`acquireTimeout` and then fail with `PoolAcquireTimeoutException`. Size the pool
to your expected concurrency.

### Pool Statistics

Monitor pool health via `async.stats`:

```kotlin
val stats = async.stats
println("total=${stats.total} inUse=${stats.inUse} idle=${stats.idle}")
```

### Shutdown

```kotlin
stormify.closeSuspending()  // closes the shared pool; a no-op if it was never created
async.close()               // or close an explicitly constructed SuspendStormify
```

Both wait up to `shutdownTimeout` (default 30s) for in-flight borrows, then
force-close what remains. On Native every pooled connection owns a thread —
skipping shutdown leaks threads, not just connections.
