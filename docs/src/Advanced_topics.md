# Advanced Topics

## Transaction Management

Stormify provides support for managing database transactions, allowing you to group multiple operations into a single transaction. This ensures data consistency and integrity, especially when dealing with complex operations that must all succeed or fail together.

### Managing Transactions

Use the `transaction` method to group operations. All included operations are committed if they succeed, or rolled back if any operation fails. Stormify also supports nested transactions through savepoints.

#### Basic Transaction Example

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

### Nested Transactions

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

### Extracting Transaction Logic

For complex business logic, you can extract operations into reusable functions:

#### Pattern 1: Extension Functions (Kotlin)

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

#### Pattern 2: Service Layer Classes

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

## Kotlin Extension Functions

Stormify provides Kotlin extension functions that allow a more idiomatic, concise syntax.
These require a **default Stormify instance** set via `stormify.asDefault()`.

### Setup

```kotlin
val stormify = Stormify(dataSource)
stormify.asDefault()  // Register as the default instance
```

### Entity Extensions

Any entity can call `create()`, `update()`, `delete()` directly — no need to implement
`CRUDTable`:

```kotlin
val user = User(name = "Alice").create()   // INSERT, returns the created entity
user.name = "Bob"
user.update()                               // UPDATE
user.delete()                               // DELETE
```

### String Extensions (SQL)

Execute SQL directly from string literals:

```kotlin
// Read
val users = "SELECT * FROM users WHERE age > ?".read<User>(25)
val user = "SELECT * FROM users WHERE id = ?".readOne<User>(1)

// Cursor-based (streaming)
"SELECT * FROM users".readCursor<User> { user -> processUser(user) }

// Execute DML
"DELETE FROM users WHERE age < ?".executeUpdate(18)

// Stored procedure
"my_procedure".procedure(arg1, spOut<Int>(), arg3)
```

### Query Helpers

```kotlin
val all = findAll<User>("WHERE status = ?", "active")
val user = findById<User>(42)
val items = order.details<OrderItem>()  // Parent-child query
```

### Transactions

```kotlin
transaction {
    val user = create(User(email = "test@example.com"))
    create(Profile(userId = user.id))
}
```

## AutoTable: Lazy Loading

`AutoTable` is an abstract base class that provides automatic lazy-loading of entity fields.
When you read a list of entities whose reference fields point to `AutoTable` subclasses,
those references are created with only their primary key set. When you access any non-key
field, the full entity is loaded from the database on demand.

### How It Works in Java

Subclasses must call `populate()` in every getter/setter of non-primary-key fields:

```java
public class User extends AutoTable {
    private Integer id;
    private String name;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }

    public String getName() {
        populate();  // Triggers lazy load if needed
        return name;
    }
    public void setName(String name) {
        populate();
        this.name = name;
    }
}
```

### How It Works in Kotlin

In Kotlin, the `db` property delegate eliminates the need to call `populate()` manually:

```kotlin
class User : AutoTable() {
    @DbField(primaryKey = true)
    var id: Int? = null
    var name: String by db("")       // Auto-populated on first access
    var email: String by db("")      // Auto-populated on first access
}
```

Every non-key property that needs lazy loading uses `by db(defaultValue)`.

### Sibling Batch Optimization

When multiple `AutoTable` references of the same type are created during a single read
operation (e.g., many `Order` rows each referencing a `Customer`), those references are
grouped into a **sibling group**. When any one of them triggers `populate()`, all
siblings in the group are loaded in a single `SELECT ... WHERE id IN (...)` query.

### Lazy Details (Child Records)

For parent-child relationships, the `lazyDetails` delegate loads child records on first access:

```kotlin
class Order : AutoTable() {
    @DbField(primaryKey = true)
    var id: Int? = null
    var total: Double by db(0.0)
    var items: List<OrderItem> by lazyDetails()  // Loaded on first access
}
```

## Handling Auto-Increment Fields

Stormify can manage auto-increment fields automatically by leveraging database sequences or letting the database handle the generation of primary key values. You can specify this behavior using the `@DbField` annotation's `primaryKey` and `primarySequence` attributes.

### Using Sequences

If your database uses sequences for generating primary keys, you can specify the sequence name using the `primarySequence` attribute in the `@DbField` annotation.

#### Example

=== "Kotlin"

    ```kotlin
    data class User(
        @DbField(name = "custom_id", primaryKey = true, primarySequence = "id_seq")
        var id: Int = 0,
        var name: String = ""
    )
    ```

=== "Java"

    ```java
    public class User {
        @DbField(name = "custom_id", primaryKey = true, primarySequence = "id_seq")
        private int id;
        private String name;

        // getters and setters
    }
    ```

In this example, the `id` field uses the sequence named `id_seq` to generate its values.

## Working with Composite Keys

Stormify supports tables with composite primary keys, allowing you to define multiple fields as part of the primary key.

### Defining Composite Keys

To define a composite key, mark all fields involved in the key as primary keys using annotations or a resolver function.

#### Example

=== "Kotlin"

    ```kotlin
    data class CompositeKeyExample(
        @DbField(name = "key_part1", primaryKey = true)
        var part1: Int = 0,

        @DbField(name = "key_part2", primaryKey = true)
        var part2: Int = 0,

        var data: String = ""
    )
    ```

=== "Java"

    ```java
    public class CompositeKeyExample {
        @DbField(name = "key_part1", primaryKey = true)
        private int part1;

        @DbField(name = "key_part2", primaryKey = true)
        private int part2;

        private String data;

        // getters and setters
    }
    ```

In this example, both `part1` and `part2` fields form the composite primary key.

## Enum Properties

Enum fields are stored as integers by default and converted back to enum constants when reading. Enums can also be used as query parameters.

### Basic Usage

=== "Kotlin"

    ```kotlin
    enum class Status { ACTIVE, INACTIVE, BANNED }

    data class User(
        @DbField(primaryKey = true) var id: Int = 0,
        var name: String = "",
        var status: Status? = null  // stored as 0, 1, 2 (ordinal)
    )

    // DDL: status INTEGER
    ```

=== "Java"

    ```java
    public enum Status { ACTIVE, INACTIVE, BANNED }

    public class User {
        @DbField(primaryKey = true)
        private int id;
        private String name;
        private Status status;  // stored as 0, 1, 2 (ordinal)
        // getters and setters
    }
    ```

Enum values work seamlessly as query parameters:

=== "Kotlin"

    ```kotlin
    // Single parameter
    val banned = stormify.findAll<User>("WHERE status = ?", Status.BANNED)

    // Collection parameter (IN clause)
    val filtered = stormify.read<User>(
        "SELECT * FROM user WHERE status IN ?",
        listOf(Status.ACTIVE, Status.BANNED)
    )
    ```

=== "Java"

    ```java
    List<User> banned = stormify.findAll(User.class, "WHERE status = ?", Status.BANNED);

    List<User> filtered = stormify.read(User.class,
        "SELECT * FROM user WHERE status IN ?",
        List.of(Status.ACTIVE, Status.BANNED));
    ```

### Custom Integer Values with `DbValue`

By default, enums are stored as their ordinal (position index). If you need stable integer
values that don't change when entries are reordered, implement the `DbValue` interface:

=== "Kotlin"

    ```kotlin
    enum class Priority(override val dbValue: Int) : DbValue {
        LOW(10),
        MEDIUM(20),
        HIGH(30)
    }
    ```

=== "Java"

    ```java
    public enum Priority implements DbValue {
        LOW(10), MEDIUM(20), HIGH(30);

        private final int dbValue;
        Priority(int dbValue) { this.dbValue = dbValue; }

        @Override
        public int getDbValue() { return dbValue; }
    }
    ```

With `DbValue`, `Priority.HIGH` is stored as `30` in the database instead of ordinal `2`.

### String Storage with `enumAsString`

To store the enum name as a string instead of an integer, use `@DbField(enumAsString = true)` or the JPA `@Enumerated(EnumType.STRING)` annotation:

=== "Kotlin"

    ```kotlin
    data class User(
        @DbField(primaryKey = true) var id: Int = 0,
        @DbField(enumAsString = true)
        var status: Status? = null  // stored as "ACTIVE", "INACTIVE", "BANNED"
    )

    // DDL: status VARCHAR(50) or TEXT
    ```

=== "Java"

    ```java
    public class User {
        @DbField(primaryKey = true)
        private int id;

        @DbField(enumAsString = true)
        private Status status;  // stored as "ACTIVE", "INACTIVE", "BANNED"
        // getters and setters
    }
    ```

You can mix ordinal and string fields in the same entity. The storage mode is per-field.
String matching is **case-insensitive** — a database value of `"active"`, `"ACTIVE"`, or `"Active"` all resolve to the same enum constant.

### Unknown Database Values

When the database contains a value that doesn't match any enum constant:

- **Nullable fields** (`Status?`): receive `null`
- **Non-null fields** (`Status`): throw an exception

## Strict Mode vs. Lenient Mode

### Lenient Mode (Default)

By default, Stormify operates in lenient mode, logging warnings instead of throwing exceptions for mismatches between classes and database columns. This mode is useful for development or scenarios where flexibility is more important than strict validation.

### Strict Mode

Strict mode enforces strict mapping between classes and database tables. When enabled, Stormify throws exceptions if fields are missing or do not match between the class and the database schema.

To enable strict mode:

=== "Kotlin"

    ```kotlin
    stormify.isStrictMode = true
    ```

=== "Java"

    ```java
    stormify.setStrictMode(true);
    ```

## Batch CRUD Operations

Pass a collection to create, update, or delete multiple entities:

=== "Kotlin"

    ```kotlin
    val users = listOf(User(name = "Alice"), User(name = "Bob"), User(name = "Carol"))
    stormify.create(users)     // Batch INSERT
    stormify.update(users)     // Batch UPDATE
    stormify.delete(users)     // Batch DELETE
    ```

=== "Java"

    ```java
    List<User> users = List.of(new User("Alice"), new User("Bob"), new User("Carol"));
    stormify.create(users);
    stormify.update(users);
    stormify.delete(users);
    ```

!!! note "Generated keys in batch insert"
    When batch-inserting entities with auto-generated keys, the generated key is populated
    back to the entity **only if a single item** is inserted. For batch inserts, use
    database sequences (`primarySequence`) instead of auto-increment to ensure keys are
    assigned before insertion.

## Collection Parameter Expansion

When a `?` placeholder receives a `Collection` or array, Stormify automatically expands
it into multiple placeholders:

=== "Kotlin"

    ```kotlin
    val ids = listOf(1, 2, 3)
    val users = stormify.read<User>("SELECT * FROM users WHERE id IN ?", ids)
    // Expands to: SELECT * FROM users WHERE id IN (?, ?, ?)
    ```

=== "Java"

    ```java
    List<Integer> ids = List.of(1, 2, 3);
    List<User> users = stormify.read(User.class, "SELECT * FROM users WHERE id IN ?", ids);
    // Expands to: SELECT * FROM users WHERE id IN (?, ?, ?)
    ```

This also works with entity collections — Stormify extracts each entity's primary key
automatically:

=== "Kotlin"

    ```kotlin
    val admins = stormify.findAll<User>("WHERE role = ?", "admin")
    val tasks = stormify.read<Task>("SELECT * FROM task WHERE user_id IN ?", admins)
    // Expands to: SELECT * FROM task WHERE user_id IN (?, ?, ...) with user PKs
    ```

=== "Java"

    ```java
    List<User> admins = stormify.findAll(User.class, "WHERE role = ?", "admin");
    List<Task> tasks = stormify.read(Task.class, "SELECT * FROM task WHERE user_id IN ?", admins);
    // Expands to: SELECT * FROM task WHERE user_id IN (?, ?, ...) with user PKs
    ```

This works with any `Iterable` or array type.

## Stored Procedures

Stormify supports calling stored procedures with IN, OUT, and INOUT parameters:

=== "Kotlin"

    ```kotlin
    val count = spOut<Int>()
    val msg = spOut<String>()
    stormify.procedure("tally", 42, count, msg)
    println("count=${count.value}, msg=${msg.value}")
    ```

=== "Java"

    ```java
    Sp.Out<Integer> count = SpKt.outParam(Integer.class);
    Sp.Out<String> msg = SpKt.outParam(String.class);
    stormify.procedure("tally", 42, count, msg);
    System.out.println("count=" + count.getValue() + ", msg=" + msg.getValue());
    ```

Parameter types:

| Type | Kotlin | Java |
|------|--------|------|
| Input | `spIn(value)` or raw value | `Sp.In(value)` or raw value |
| Output | `spOut<T>()` | `SpKt.outParam(Type.class)` |
| Bidirectional | `spInOut(value)` | `SpKt.inOutParam(Type.class, value)` |

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

### Nested Transactions

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

