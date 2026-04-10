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

### Fresh Construction vs. Lazy Stubs

An `AutoTable` instance exists in one of three states, and `populate()` behaves accordingly.
A `Stormify` instance is considered "available" when it is either directly attached to the
entity (by a prior Stormify operation) **or** when a [default
instance](Core_concepts.md#working-with-entities) has been registered via
`Stormify.asDefault()`.

| State | `Stormify` available? | User touched any `db` field? | Behavior on read |
|-------|----------------------|-------------------------------|------------------|
| **Fresh construct (detached)** — `User().apply { id=1; name="Alice" }` with no default instance | No | Yes (e.g., `name="Alice"`) | Returns in-memory value; no DB access |
| **Manual stub / lazy reference** — `User().apply { id=1 }` where only the ID is set | Yes (via default instance) | No | First access triggers a `SELECT` to load the row |
| **FK reference stub** — came from a foreign-key read; Stormify auto-attaches | Yes (directly) | No | First access triggers a `SELECT` to load the row |
| **Populated** — came from `findById`/`findAll`/`create`, or has been lazy-loaded already | Yes | — | Returns in-memory value; no further DB access |
| **Only-ID, no Stormify anywhere** — `User().apply { id=1 }` with no default instance | No | No | **Throws** `SQLException` — no way to load, and the library refuses to hand back silent defaults |

Stormify distinguishes these states automatically. You don't need to mark anything — the
combination of "is a Stormify reachable?" and "has the user written any delegated field?"
is enough to pick the right behavior.

#### Manual Stubs (Pattern)

You can construct lazy stubs yourself — useful when you already have an ID in hand (e.g.
from a URL parameter, a cache, or another table). Either attach a Stormify instance
explicitly via `stormify.attach(...)`, or register a default via `asDefault()` and let
the library pick it up:

=== "Kotlin"

    ```kotlin
    // Explicit attach — works without a default instance
    val user = stormify.attach(User().apply { id = userId })
    println(user.name)    // triggers SELECT, returns the DB value
    println(user.email)   // already loaded; no second query

    // Or with a default instance — even simpler
    stormify.asDefault()
    val user2 = User().apply { id = userId }
    println(user2.name)   // default instance picks up lazy-load
    ```

=== "Java"

    ```java
    // Explicit attach — works without a default instance
    User user = stormify.attach(new User());
    user.setId(userId);
    System.out.println(user.getName());    // triggers SELECT
    System.out.println(user.getEmail());   // already loaded

    // Or with a default instance
    stormify.asDefault();
    User user2 = new User();
    user2.setId(userId);
    System.out.println(user2.getName());
    ```

This is exactly what `findById(userId)` does internally, with one difference: `findById`
eagerly runs the `SELECT` and returns the populated entity, while the manual-stub pattern
defers the query until the first field access. For "I might not actually read this" code
paths, manual stubs save a round trip.

The same `stormify.attach(target)` API works for any `StormifyAware` object — both
entities and `PagedList` instances — so you only need to remember one pattern.

### Sibling Batch Optimization

When multiple `AutoTable` references of the same type are created during a single read
operation (e.g., many `Order` rows each referencing a `Customer`), those references are
grouped into a **sibling group**. When any one of them triggers `populate()`, all
siblings in the group are loaded in a single `SELECT ... WHERE id IN (...)` query.

If any of the requested IDs are no longer present in the database at the time of the
batch load, the whole call fails fast with an `SQLException("No data found for <table>
with ids [<missing>]")`. This surfaces stale references (rows deleted in another session
between when you obtained them and when you tried to access them) immediately instead of
silently leaving affected entities empty.

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

## PagedList: Lazy Paginated Views

`PagedList<T>` is a column-based, on-demand paginated list backed by the database. It
targets UI scenarios — data grids, dropdown pickers, search screens — where you want to
expose a potentially very large result set without materializing it in memory, while
still supporting per-column filtering, sorting, and foreign-key traversal.

It implements `kotlin.collections.AbstractList<T>` (and therefore Java's `List<T>`), so
every `get(index)`, `size`, and `iterator()` call Just Works — the list loads pages
behind the scenes.

### Quick Start

Construction takes only the entity type. The [Stormify] instance is attached separately
(or resolved from [the default instance](Core_concepts.md#working-with-entities) if you
have one registered), so the same class works identically from Kotlin and Java.

=== "Kotlin"

    ```kotlin
    import onl.ycode.stormify.biglist.Column
    import onl.ycode.stormify.biglist.PagedList
    import db.stormify.Company_   // KSP-generated typed paths

    // Construct and attach in one fluent call
    val list = stormify.attach(PagedList<Company>())

    list.addColumn(Company_.name)                                 // auto-detect TEXT
    list.addColumn(Company_.contactPerson.firstName,              // OR across FK-traversed fields
                   Company_.contactPerson.lastName)
    list.addRawColumn("SUM(order_total)", Column.NUMERIC)

    list.getColumn(0).filter = "Acme"
    list.getColumn(1).sort = Column.ASCENDING

    // Consume as a normal List
    val firstCompany = list[0]                                    // triggers page load
    val totalMatches = list.size                                  // triggers COUNT query
    for (company in list) println(company.name)                   // streams page-by-page
    ```

=== "Java"

    ```java
    import onl.ycode.stormify.biglist.Column;
    import onl.ycode.stormify.biglist.PagedList;
    import db.stormify.Company_;   // KSP-generated typed paths

    // Construct and attach in one fluent call
    PagedList<Company> list = stormify.attach(new PagedList<>(Company.class));

    list.addColumn(Company_.name);                                 // auto-detect TEXT
    list.addColumn(Company_.contactPerson.firstName,               // OR across FK-traversed fields
                   Company_.contactPerson.lastName);
    list.addRawColumn("SUM(order_total)", Column.NUMERIC);

    list.getColumn(0).setFilter("Acme");
    list.getColumn(1).setSort(Column.ASCENDING);

    // Consume as a normal List
    Company firstCompany = list.get(0);                            // triggers page load
    int totalMatches = list.size();                                // triggers COUNT query
    for (Company c : list) System.out.println(c.getName());        // streams page-by-page
    ```

If you have set a default Stormify instance via `asDefault()`, you can skip the explicit
`attach` — the list will pick up the default on first access:

=== "Kotlin"

    ```kotlin
    stormify.asDefault()
    val list = PagedList<Company>()        // no attach needed, uses default instance
    ```

=== "Java"

    ```java
    stormify.asDefault();
    PagedList<Company> list = new PagedList<>(Company.class);
    ```

### Enabling Type-Safe Paths

Typed paths like `Company_.name` are generated by the `annproc` KSP processor. Add it to
your build (once; the same processor also generates entity metadata):

=== "Gradle (Kotlin)"

    ```kotlin
    plugins {
        id("com.google.devtools.ksp") version "2.2.20-2.0.2"
    }

    dependencies {
        ksp("onl.ycode:annproc:2.0.0")
    }
    ```

=== "Gradle (Java)"

    ```groovy
    plugins {
        id 'com.google.devtools.ksp' version '2.2.20-2.0.2'
    }

    dependencies {
        ksp 'onl.ycode:annproc:2.0.0'
    }
    ```

For each entity class `Foo` the processor emits a `Foo_` object under the `db.stormify`
package, with fields for each scalar property and nested objects for FK references. See
[Annotation Processor](Core_concepts.md#annotation-processor-annproc) for the full setup.
On Native/Android/iOS, `annproc` is **required** anyway (for entity metadata) — you get
typed paths for free.

### Columns

A column is the unit of filtering and sorting. It can be backed by:

- **A single entity field** — `addColumn(Company_.name)`
- **Multiple fields with OR semantics** — `addColumn(Person_.firstName, Person_.lastName)`
  filters with `firstName LIKE ? OR lastName LIKE ?`
- **A foreign-key path** — `addColumn(Order_.customer.name)` auto-generates the JOIN
- **A raw SQL expression** — `addRawColumn("SUM(total)", Column.NUMERIC)` for calculated columns

Filters between different columns use AND semantics; multiple paths within the same
column use OR.

=== "Kotlin"

    ```kotlin
    val list = stormify.attach(PagedList<Order>())
    val nameCol   = list.addColumn(Order_.customer.name)       // FK traversal
    val statusCol = list.addColumn(Order_.status)              // scalar
    val rawCol    = list.addRawColumn("total * tax_rate", Column.NUMERIC)

    nameCol.filter = "Acme"
    statusCol.filter = "ACTIVE"
    // Both filters active → WHERE customer.name LIKE %Acme% AND status = 'ACTIVE'
    ```

=== "Java"

    ```java
    PagedList<Order> list = stormify.attach(new PagedList<>(Order.class));
    Column<Order> nameCol   = list.addColumn(Order_.customer.name);
    Column<Order> statusCol = list.addColumn(Order_.status);
    Column<Order> rawCol    = list.addRawColumn("total * tax_rate", Column.NUMERIC);

    nameCol.setFilter("Acme");
    statusCol.setFilter("ACTIVE");
    ```

String paths with dot notation work too (`addColumn("customer.name")`) — useful for
cross-cutting code where a field is chosen at runtime. Mix both styles freely.

### Column Types and Filter Syntax

Each column has a `Column.Type` that controls how the filter string is parsed. The type
is auto-detected from the field's Kotlin type, or you can set it explicitly via the
two-argument `addColumn(type, ...)` overload.

#### Text (`Column.TEXT`)

Case-insensitive by default. Supports several patterns:

| Filter value  | Meaning |
|--------------|---------|
| `Alice`       | Substring match: `LIKE %Alice%` |
| `*lice`       | Ends with: `LIKE %lice` |
| `Ali*`        | Starts with: `LIKE Ali%` |
| `"Alice"`     | Exact match (still case-insensitive unless `isCaseSensitive = true`) |

Set `column.isCaseSensitive = true` for a case-sensitive column.

#### Numeric (`Column.NUMERIC`)

| Filter value  | Meaning |
|--------------|---------|
| `42`          | Equals |
| `> 10`        | Greater than |
| `>= 10`       | Greater than or equal |
| `< 100`       | Less than |
| `<= 100`      | Less than or equal |
| `10 ... 20`   | Range (inclusive) |

#### Temporal (`Column.TEMPORAL`)

Covers `LocalDate`, `LocalTime`, `LocalDateTime`, `Instant`. Supports the same comparison
operators and range syntax as `NUMERIC`. Values are parsed via the active [input
parser](#input-parsing-and-locale).

#### Enum (`Column.ENUM`)

For enum-backed fields. Auto-detected when the field's Kotlin type is an enum. The filter
string is matched (case-insensitively, as a substring) against the enum's display names.
Multiple matching display names produce an `IN` clause of the corresponding DB values.

=== "Kotlin"

    ```kotlin
    enum class Status { ACTIVE, INACTIVE, BANNED }

    val list = stormify.attach(PagedList<User>())
    list.addColumn(User_.status)            // auto-detects as ENUM
    list.getColumn(0).filter = "active"     // matches ACTIVE and INACTIVE
    ```

=== "Java"

    ```java
    public enum Status { ACTIVE, INACTIVE, BANNED }

    PagedList<User> list = stormify.attach(new PagedList<>(User.class));
    list.addColumn(User_.status);
    list.getColumn(0).setFilter("active");
    ```

To customize the display names (e.g., localized UI strings), implement the
[`HumanReadable`](#humanreadable-display-names) interface on the enum. For non-enum
fields that you want to treat as enums (or to override the auto-built map), use
`addEnumColumn`:

=== "Kotlin"

    ```kotlin
    val displayMap = mapOf("Ενεργός" to 1, "Ανενεργός" to 0)
    list.addEnumColumn(displayMap, User_.statusCode)
    ```

=== "Java"

    ```java
    Map<String, Object> displayMap = Map.of("Ενεργός", 1, "Ανενεργός", 0);
    list.addEnumColumn(displayMap, User_.statusCode);
    ```

#### NULL Filter

Any column can filter for `NULL` using the sentinel constant `PagedList.NULL`:

=== "Kotlin"

    ```kotlin
    list.getColumn(0).filter = PagedList.NULL   // → WHERE name IS NULL
    ```

=== "Java"

    ```java
    list.getColumn(0).setFilter(PagedList.NULL);
    ```

### Sorting

Set `column.sort` to `Column.ASCENDING` or `Column.DESCENDING`. Any number of columns can
be active sorts at once; the list sorts by them in the order they were defined:

=== "Kotlin"

    ```kotlin
    list.getColumn(0).sort = Column.ASCENDING    // primary
    list.getColumn(1).sort = Column.DESCENDING   // secondary
    ```

=== "Java"

    ```java
    list.getColumn(0).setSort(Column.ASCENDING);
    list.getColumn(1).setSort(Column.DESCENDING);
    ```

`column.clearSort()` deactivates the sort for a single column; `list.reset()` clears
all filters and sorts at once (but keeps constraints — see below).

If no column has an active sort, rows are ordered by the entity's primary key. This
requires a single-column PK; for composite keys you must explicitly set a sort on at
least one column, otherwise the list throws `IllegalStateException`.

### Constraints

Constraints are a fixed `WHERE` clause that is always applied, independent of column
filters. Use them to scope the list to a sub-query (e.g., "only orders for user 42"):

=== "Kotlin"

    ```kotlin
    list.setConstraints("customer_id = ?", 42)
    ```

=== "Java"

    ```java
    list.setConstraints("customer_id = ?", 42);
    ```

Constraints and column filters combine with `AND`. Unlike filters, constraints are **not**
cleared by `list.reset()` — they're considered part of the list's fundamental definition.

### Pagination and Caching

`PagedList` keeps one page of rows in memory at a time. `pageSize` defaults to 15 and
can be changed:

=== "Kotlin"

    ```kotlin
    list.pageSize = 50
    ```

=== "Java"

    ```java
    list.setPageSize(50);
    ```

Indexing outside the currently cached page triggers a new page load. The `size` property
triggers a `COUNT(*)` query the first time it's read and caches the result. Any change to
filters, sorting, or constraints invalidates both the page cache and the count.

Stormify uses the underlying SQL dialect's pagination syntax (`LIMIT/OFFSET`, `FETCH
FIRST`, `ROWNUM`, etc.) automatically.

### Selected Entity

You can mark one entity as **selected** — it appears as the first element of the list
regardless of any sort order. This is useful for UI patterns like "show the currently
highlighted row at the top":

=== "Kotlin"

    ```kotlin
    list.selected = currentCompany
    list[0]  // always currentCompany (when non-null)
    ```

=== "Java"

    ```java
    list.setSelected(currentCompany);
    list.get(0);  // always currentCompany (when non-null)
    ```

The helpers `list.add(entity)` and `list.remove(entity)` set or clear the selection as a
side effect. Use them when you create/delete an entity and want the list to reflect the
change immediately without resetting the user's filters:

=== "Kotlin"

    ```kotlin
    val company = stormify.create(Company(name = "Acme"))
    list.add(company)        // appears first

    stormify.delete(company)
    list.remove(company)     // selection cleared
    ```

=== "Java"

    ```java
    Company company = stormify.create(new Company("Acme"));
    list.add(company);

    stormify.delete(company);
    list.remove(company);
    ```

### Selection Values (Distinct Per-Column)

For populating dropdown pickers, call `column.getSelectionValues()`. This returns a
`SelectionList<T>` — another lazy paginated list, but of the distinct values of that
column, filtered by the **other** columns' active filters. The user only sees values that
would actually return results under the current filter state.

=== "Kotlin"

    ```kotlin
    val statusCol = list.addColumn(User_.status)
    val distinctStatuses = statusCol.getSelectionValues()
    // Populate a dropdown; values are filtered by the other active filters
    distinctStatuses.forEach { println(it) }
    ```

=== "Java"

    ```java
    Column<User> statusCol = list.addColumn(User_.status);
    SelectionList<User> distinctStatuses = statusCol.getSelectionValues();
    distinctStatuses.forEach(System.out::println);
    ```

`SelectionList` auto-invalidates whenever any filter or constraint changes on the parent
list.

### Input Parsing and Locale

Raw user input (e.g., from a text field) often uses locale-specific formats — Greek
numbers like `1.234,56`, European dates like `31/12/2026`. `PagedList` applies an
`InputParser` (a SAM `fun interface`) that transforms the filter string before it reaches SQL.

Resolution order: **column → list → global → identity (no transformation)**.

=== "Kotlin"

    ```kotlin
    // Global — applies to all PagedList instances unless overridden
    PagedList.defaultInputParser = InputParser { input, type ->
        when (type) {
            Column.NUMERIC -> input.replace(".", "").replace(",", ".")
            Column.TEMPORAL -> flipDayMonthYear(input)
            else -> input
        }
    }

    // Per-list override
    myList.inputParser = InputParser { input, type -> /* ... */ }

    // Per-column override
    myList.getColumn(0).inputParser = InputParser { input, type -> /* ... */ }
    ```

=== "Java"

    ```java
    // Global — applies to all PagedList instances unless overridden
    PagedList.setDefaultInputParser((input, type) -> {
        if (type == Column.NUMERIC) return input.replace(".", "").replace(",", ".");
        if (type == Column.TEMPORAL) return flipDayMonthYear(input);
        return input;
    });

    // Per-list override
    myList.setInputParser((input, type) -> /* ... */);

    // Per-column override
    myList.getColumn(0).setInputParser((input, type) -> /* ... */);
    ```

Set to `NoInputParser` (the default) to fall through to the next level.

### Custom SQL Generators for Raw Columns

Raw columns can take a `SqlGenerator` — a SAM interface that produces the SQL fragment
and stages bind parameters via a `SqlArgsCollector`. Use this to build non-trivial
predicates over calculated expressions:

=== "Kotlin"

    ```kotlin
    val col = list.addRawColumn("users.id", Column.NUMERIC) { column, value, args ->
        val n = value.toIntOrNull() ?: 0
        args.accept(n)
        "$column % ? = 0"    // rows whose id is divisible by n
    }
    col.filter = "3"         // keep ids divisible by 3
    ```

=== "Java"

    ```java
    Column<User> col = list.addRawColumn("users.id", Column.NUMERIC, (column, value, args) -> {
        int n = Integer.parseInt(value);
        args.accept(n);
        return column + " % ? = 0";
    });
    col.setFilter("3");
    ```

### `HumanReadable` (Display Names)

Implement `HumanReadable` on enums (or any class) that participate in enum columns to
provide localized display names:

=== "Kotlin"

    ```kotlin
    enum class Status : HumanReadable {
        ACTIVE    { override fun displayName() = "Ενεργή" },
        INACTIVE  { override fun displayName() = "Ανενεργή" },
        BANNED    { override fun displayName() = "Αποκλεισμένη" }
    }
    ```

=== "Java"

    ```java
    public enum Status implements HumanReadable {
        ACTIVE   { public String displayName() { return "Ενεργή"; } },
        INACTIVE { public String displayName() { return "Ανενεργή"; } },
        BANNED   { public String displayName() { return "Αποκλεισμένη"; } }
    }
    ```

When the enum column auto-builds its display-name-to-DB-value map, it uses
`displayName()` instead of `name`. This is also what `SelectionList` returns for enum
columns — so your dropdown shows the localized labels instead of `ACTIVE`/`INACTIVE`/`BANNED`.

### Distinct Mode

Set `list.isDistinct = true` to make the underlying query use `SELECT DISTINCT`. This is
useful when JOINs cause row duplication. Note that `DISTINCT` affects both `size` (which
becomes `COUNT(DISTINCT ...)`) and page queries.

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

