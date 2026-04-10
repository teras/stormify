# Transactions

Stormify provides support for managing database transactions, allowing you to group multiple operations into a single transaction. This ensures data consistency and integrity, especially when dealing with complex operations that must all succeed or fail together.

Two transaction APIs are available:

- **Blocking** — `stormify.transaction { ... }` (this page, top half). The default.
- **Suspend** — `async.transaction { ... }` for Kotlin coroutines, with a built-in
  connection pool ([see below](#coroutines-suspend-api)).

Both share the same CRUD operations and nested-transaction semantics.

## Managing Transactions

Use the `transaction` method to group operations. All included operations are committed if they succeed, or rolled back if any operation fails. Stormify also supports nested transactions through savepoints.

### Basic Transaction Example

=== "Direct"

    === "Kotlin"

        ```kotlin
        stormify.transaction {
            val user = create(User(email = "test@example.com"))
            create(Profile(userId = user.id, name = "Test User"))
            update(account)
        }
        ```

    === "Java"

        ```java
        stormify.transaction(tx -> {
            User user = tx.create(new User("test@example.com"));
            tx.create(new Profile(user.getId(), "Test User"));
            tx.update(account);
        });
        ```

=== "Extension"

    ```kotlin
    // With a default Stormify instance registered via stormify.asDefault(),
    // you can call transaction { ... } directly without the stormify receiver.
    transaction {
        val user = create(User(email = "test@example.com"))
        create(Profile(userId = user.id))
    }
    ```

## Nested Transactions

Nested transactions use database savepoints. If an inner transaction fails, only operations within that savepoint are rolled back.

=== "Kotlin"

    ```kotlin
    stormify.transaction {
        create(record1)

        transaction {  // Creates a savepoint
            create(record2)
            // If this fails, only record2 is rolled back
        }

        create(record3)  // This still executes
    }
    ```

=== "Java"

    ```java
    stormify.transaction(tx -> {
        tx.create(record1);

        tx.transaction(() -> {  // Creates a savepoint
            tx.create(record2);
            // If this fails, only record2 is rolled back
        });

        tx.create(record3);  // This still executes
    });
    ```

## Extracting Transaction Logic

For complex business logic, you can extract operations into reusable functions:

### Pattern 1: Extension Functions (Kotlin)

Extension functions on `TransactionContext` provide the cleanest syntax:

```kotlin
fun TransactionContext.registerUser(email: String, name: String) {
    val user = create(User(email = email))
    create(Profile(userId = user.id, name = name))
    create(AuditLog(action = "User registered", userId = user.id))
}

fun TransactionContext.transferFunds(from: Account, to: Account, amount: Double) {
    require(from.balance >= amount) { "Insufficient funds" }
    update(from.copy(balance = from.balance - amount))
    update(to.copy(balance = to.balance + amount))
    create(Transaction(fromId = from.id, toId = to.id, amount = amount))
}

// Usage
stormify.transaction {
    registerUser("alice@example.com", "Alice")
    transferFunds(accountA, accountB, 100.0)
}
```

### Pattern 2: Service Layer Classes

For more structured applications, encapsulate transaction logic in service classes:

=== "Kotlin"

    ```kotlin
    class UserService(private val tx: TransactionContext) {
        fun registerUser(email: String, name: String) {
            val user = tx.create(User(email = email))
            tx.create(Profile(userId = user.id, name = name))
        }
    }

    stormify.transaction {
        val userService = UserService(this)
        userService.registerUser("alice@example.com", "Alice")
    }
    ```

=== "Java"

    ```java
    class UserService {
        private final TransactionContextJ tx;

        UserService(TransactionContextJ tx) { this.tx = tx; }

        void registerUser(String email, String name) {
            User user = tx.create(new User(email));
            tx.create(new Profile(user.getId(), name));
        }
    }

    stormify.transaction(tx -> {
        UserService userService = new UserService(tx);
        userService.registerUser("alice@example.com", "Alice");
    });
    ```

Both patterns ensure that all operations share the same database connection and participate in the same transaction.

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
    val user = create(User(email = "test@example.com"))
    create(Profile(userId = user.id, name = "Test User"))
}
```

All operations inside the block are suspend functions that run on the IO dispatcher.
The transaction commits on success and rolls back on any exception.

### Nested Suspend Transactions

Calling `transaction` from within another `transaction` on the same coroutine reuses the
outer connection via a savepoint:

```kotlin
async.transaction {
    create(record1)
    transaction {
        // Uses savepoint — rollback only affects this inner block
        create(record2)
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
