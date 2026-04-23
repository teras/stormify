# Migrating from V1 to V2

Stormify V2 is a major rewrite that brings Kotlin Multiplatform support, replacing the
JVM-only V1. This guide covers all breaking changes and how to update your code.

## What Changed

| Area | V1 | V2 |
|------|----|----|
| Platforms | JVM only | JVM, Android, Linux, iOS, macOS |
| Database access | JDBC directly | KDBC (wraps JDBC on JVM, native drivers elsewhere) |
| Initialization | Singleton (`StormifyManager.stormify()`) | Constructor (`Stormify(dataSource)`) |
| Entity discovery | Reflection only | Reflection (JVM) + KSP code generation (native) |
| Exceptions | `QueryException`, `SPParamException` | Unified `SQLException` |
| Stored procedures | `SPParam` with `Mode` enum | `Sp.In`, `Sp.Out<T>`, `Sp.InOut<T>` |
| Coroutines | Not supported | `SuspendStormify` with connection pooling |
| Language | Java + optional Kotlin extensions | Kotlin (with Java interop on JVM) |

## Dependencies

### Maven

=== "V1"

    ```xml
    <dependency>
        <groupId>onl.ycode.stormify</groupId>
        <artifactId>db</artifactId>
        <version>1.3.0</version>
    </dependency>
    <!-- Optional Kotlin extensions -->
    <dependency>
        <groupId>onl.ycode.stormify</groupId>
        <artifactId>kotlin</artifactId>
        <version>1.3.0</version>
    </dependency>
    ```

=== "V2"

    ```xml
    <dependency>
        <groupId>onl.ycode</groupId>
        <artifactId>stormify-jvm</artifactId>
        <version>2.2.0-SNAPSHOT</version>
    </dependency>
    ```

### Gradle

=== "V1"

    ```kotlin
    implementation("onl.ycode.stormify:db:1.3.0")
    implementation("onl.ycode.stormify:kotlin:1.3.0") // optional
    ```

=== "V2"

    ```kotlin
    implementation("onl.ycode:stormify-jvm:2.2.0-SNAPSHOT")
    ```

The separate `db` and `kotlin` modules are merged into a single `stormify` artifact.
The group ID changed from `onl.ycode.stormify` to `onl.ycode`.

## Initialization

The global singleton is gone. Create `Stormify` instances via constructor.

=== "V1 (Java)"

    ```java
    import static onl.ycode.stormify.StormifyManager.stormify;

    HikariDataSource dataSource = new HikariDataSource(config);
    stormify().setDataSource(dataSource);

    // Use anywhere via static accessor
    stormify().create(user);
    ```

=== "V2 (Kotlin — JVM)"

    ```kotlin
    import onl.ycode.stormify.Stormify

    val dataSource = HikariDataSource(config)
    val stormify = Stormify(dataSource)  // Accepts javax.sql.DataSource directly

    // Pass instance explicitly
    stormify.create(user)
    ```

=== "V2 (Java — JVM)"

    ```java
    import onl.ycode.stormify.StormifyJ;

    HikariDataSource dataSource = new HikariDataSource(config);
    StormifyJ stormify = new StormifyJ(dataSource);

    stormify.create(user);
    ```

**Key change**: instead of a global singleton, you hold a `Stormify` instance and pass it
to your services. This makes testing and multi-database setups straightforward.

## Transactions

The transaction API moves from the static-singleton pattern to a plain lambda on a concrete instance. Every call inside the block runs on the transaction's connection via the ambient-tx registry, and nested `transaction { }` calls become savepoints automatically.

=== "V1"

    ```java
    stormify().transaction(() -> {
        stormify().create(user);
        stormify().create(profile);
    });
    ```

=== "V2"

    ```kotlin
    stormify.transaction {
        stormify.create(user)
        stormify.create(profile)
        // Auto-commits on success, auto-rolls back on exception
    }
    ```

Nested transactions use savepoints automatically:

```kotlin
stormify.transaction {
    stormify.create(user)
    stormify.transaction {
        // Creates savepoint — rolls back only this block on failure
        stormify.create(profile)
    }
}
```

## CRUD Operations

### Method Signatures

=== "V1"

    ```java
    // Class parameter required
    List<User> users = stormify().read(User.class, "SELECT * FROM users WHERE age > ?", 18);
    User user = stormify().readOne(User.class, "SELECT * FROM users WHERE id = ?", 1);
    stormify().create(user);    // void
    stormify().update(user);    // void
    stormify().delete(user);    // void
    ```

=== "V2"

    ```kotlin
    // Reified generics — no Class parameter
    val users = stormify.read<User>("SELECT * FROM users WHERE age > ?", 18)
    val user = stormify.readOne<User>("SELECT * FROM users WHERE id = ?", 1)
    val created = stormify.create(user)    // Returns the created item
    val updated = stormify.update(user)    // Returns the updated item
    stormify.delete(user)
    ```

**Changes:**
- No `Class<T>` parameter — V2 uses Kotlin reified generics
- `create()` and `update()` now **return** the item (useful for generated IDs)
- For Java callers, use `StormifyJ` which accepts `Class<T>` parameters

### findById

=== "V1"

    ```java
    User user = stormify().findById(User.class, 42);
    ```

=== "V2"

    ```kotlin
    val user = stormify.findById<User>(42)
    ```

### Cursor-based Reading

V2 adds `readCursor` for processing large result sets without loading everything into memory:

```kotlin
stormify.readCursor<User>("SELECT * FROM users") { user ->
    processUser(user)  // Called for each row
}
```

## Stored Procedures

The stored procedure API is redesigned for type safety.

=== "V1"

    ```java
    SPParam count = new SPParam(SPParam.Mode.OUT, Integer.class);
    SPParam msg = new SPParam(SPParam.Mode.OUT, String.class);
    stormify().procedure("tally", 42, count, msg);
    int result = (Integer) count.getValue();
    ```

=== "V2 (Kotlin)"

    ```kotlin
    val count = spOut<Int>()
    val msg = spOut<String>()
    stormify.procedure("tally", 42, count, msg)
    println("${count.value}, ${msg.value}")
    ```

=== "V2 (Java)"

    ```java
    Sp.Out<Integer> count = Sp.outParam(Integer.class);
    Sp.Out<String> msg = Sp.outParam(String.class);
    stormify.procedure("tally", 42, count, msg);
    Integer result = count.getValue();
    ```

**Parameter types:**

| V1 | V2 | Purpose |
|----|----|---------|
| `SPParam(Mode.IN, value)` | `Sp.In(value)` or `spIn(value)` | Input parameter |
| `SPParam(Mode.OUT, type)` | `Sp.Out<T>()` or `spOut<T>()` | Output parameter |
| `SPParam(Mode.IN_OUT, value)` | `Sp.InOut<T>(value)` or `spInOut(value)` | Bidirectional |

## Exception Handling

V2 uses a single unified exception type across all platforms.

=== "V1"

    ```java
    try {
        stormify().read(User.class, "SELECT ...");
    } catch (QueryException e) {
        // ...
    } catch (SPParamException e) {
        // ...
    }
    ```

=== "V2"

    ```kotlin
    try {
        stormify.read<User>("SELECT ...")
    } catch (e: SQLException) {
        // All database errors are SQLException
        // Database-specific messages pass through
        // e.g. "ORA-00942: table or view does not exist"
    }
    ```

`QueryException` and `SPParamException` are removed. All database errors throw
`onl.ycode.kdbc.SQLException`.

## Configuration

=== "V1"

    ```java
    stormify().setNamingPolicy(NamingPolicy.CAMEL_CASE);
    stormify().setStrictMode(true);
    ```

=== "V2"

    ```kotlin
    stormify.namingPolicy = NamingPolicy.CAMEL_CASE
    stormify.unmatchedColumnPolicy = UnmatchedColumnPolicy.THROW
    ```

The naming policies and annotations (`@DbTable`, `@DbField`, `@Id`, `@Table`, `@Column`,
`@Transient`, etc.) are unchanged.

## Entity Registration

On JVM, reflection-based entity discovery works the same as V1 (no changes needed) —
`kotlin-reflect` is now included as a transitive dependency.

If you plan to target **native platforms** or want faster JVM startup, V2 offers the
`annproc` annotation processor (via KSP) to generate entity metadata at compile time.
See [Annotation Processor](Annotations.md#annotation-processor-annproc) for setup details.

## DataSource Wrapping (JVM)

On JVM, V2 accepts `javax.sql.DataSource` directly in the `Stormify` constructor (via a
convenience overload). If you need the KDBC `DataSource` interface explicitly:

```kotlin
import onl.ycode.kdbc.JdbcDataSource

val kdbcDataSource = JdbcDataSource(hikariDataSource)
```

The `JdbcDataSource` wrapper has zero overhead — it delegates directly to the underlying
JDBC types.

## Removed APIs

The following V1 APIs no longer exist in V2:

- **`onInit()` callbacks** — not needed; use constructor-based initialization instead.
- **`closeDataSource()`** / **`close()`** — Stormify no longer owns the DataSource. Close it directly.
- **`User::class.db`** (Kotlin table name extension) — removed. Use
  `stormify.getTableInfo(User::class).tableName` instead.
- **`storedProcedure()`** — renamed to `procedure()`.

## Quick Migration Checklist

1. **Update dependencies**: `onl.ycode.stormify:db` → `onl.ycode:stormify-jvm`
2. **Replace singleton**: `StormifyManager.stormify()` → `Stormify(dataSource)` constructor
3. **Hold the instance**: pass `Stormify` to services instead of calling static methods
4. **Update transactions**: `stormify().transaction(() -> …)` → `stormify.transaction { … }` (see 2.1 → 2.2 note below for the receiver-vs-plain change)
5. **Remove Class parameters**: `read(User.class, sql)` → `read<User>(sql)`
6. **Update exception handling**: `QueryException` → `SQLException`
7. **Update stored procedures**: `storedProcedure()` → `procedure()`, `SPParam` → `Sp` API
8. **Add KSP** if targeting native platforms
9. **Optional**: adopt coroutine API for async workloads

## Upgrading from 2.1 to 2.2

Version 2.2 flattens the transaction API. The lambda-with-receiver of 2.1 is gone — the block is a plain `() -> R` and every operation participates in the enclosing transaction through an ambient-tx registry instead of a receiver parameter.

### Transaction body

=== "2.1"

    ```kotlin
    stormify.transaction {
        val u = create(User(email = "a@b.c"))   // receiver-scoped create
        create(Profile(userId = u.id))
    }
    ```

=== "2.2"

    ```kotlin
    stormify.transaction {
        val u = stormify.create(User(email = "a@b.c"))   // plain lambda
        stormify.create(Profile(userId = u.id))
    }

    // or — after calling stormify.asDefault() once — via top-level extensions:
    transaction {
        val u = User(email = "a@b.c").create()
        Profile(userId = u.id).create()
    }
    ```

### Nested transactions

No syntactic change on the outside, but the inner call is now qualified:

```kotlin
// 2.2
stormify.transaction {
    stormify.create(a)
    stormify.transaction { stormify.create(b) }   // savepoint, detected via ambient
}
```

### Java

The `TransactionContextJ` wrapper is gone. `StormifyJ.transaction` now takes a plain `Runnable` / `Supplier<R>`:

=== "2.1 (Java)"

    ```java
    stormify.transaction(tx -> {
        tx.create(user);
        tx.create(profile);
    });
    ```

=== "2.2 (Java)"

    ```java
    stormify.transaction(() -> {
        stormify.create(user);
        stormify.create(profile);
    });
    ```

A new `StormifyJHelpers` companion offers static imports so you can drop the prefix entirely (requires a one-time `new StormifyJ(ds).asDefault()` at startup):

```java
import static onl.ycode.stormify.StormifyJHelpers.*;

transaction(() -> {
    create(user);
    create(profile);
});
```

### CRUDTable is no longer deprecated

`CRUDTable.create()/update()/delete()` now route through the ambient transaction too, so implementing the interface is again a recommended Java-friendly pattern. Any 2.1 code using it continues to work; the `@Deprecated` warning is removed.

### Extension `read`/`create` on entities and SQL strings

The `TransactionContext` member-scope extensions that 2.1.2-SNAPSHOT briefly introduced (`user.create()` inside `transaction { }` routed via a receiver shadow) are gone. The same top-level extensions from `StormifyBindings.kt` work everywhere — inside or outside a transaction — via ambient routing.
