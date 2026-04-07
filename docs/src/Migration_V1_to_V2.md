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
        <version>2.0.0</version>
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
    // JVM
    implementation("onl.ycode:stormify-jvm:2.0.0")

    // Kotlin Multiplatform
    kotlin {
        sourceSets {
            commonMain {
                dependencies {
                    implementation("onl.ycode:stormify:2.0.0")
                }
            }
        }
    }
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

=== "V2 (Kotlin — Native)"

    ```kotlin
    import onl.ycode.stormify.Stormify
    import onl.ycode.kdbc.KdbcDataSource

    val ds = KdbcDataSource("jdbc:postgresql://localhost:5432/mydb", "user", "pass")
    val stormify = Stormify(ds)
    ```

=== "V2 (Java — JVM)"

    ```java
    import onl.ycode.stormify.Stormify;

    HikariDataSource dataSource = new HikariDataSource(config);
    Stormify stormify = new Stormify(dataSource);

    stormify.create(user);
    ```

**Key change**: instead of a global singleton, you hold a `Stormify` instance and pass it
to your services. This makes testing and multi-database setups straightforward.

## Transactions

The transaction API moves from ThreadLocal-based context to a lambda-with-receiver pattern.

=== "V1"

    ```java
    try (TransactionContext ctx = TransactionContext.begin()) {
        stormify().create(user);
        stormify().create(profile);
        ctx.commit();
    }
    ```

=== "V2"

    ```kotlin
    stormify.transaction {
        create(user)
        create(profile)
        // Auto-commits on success, auto-rolls back on exception
    }
    ```

Nested transactions use savepoints automatically:

```kotlin
stormify.transaction {
    create(user)
    transaction {
        // Creates savepoint — rolls back only this block on failure
        create(profile)
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
- For Java callers, V2 provides overloads that accept `KClass<T>`

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
    stormify.isStrictMode = true
    ```

The naming policies and annotations (`@DbTable`, `@DbField`, `@Id`, `@Table`, `@Column`,
`@Transient`, etc.) are unchanged.

## Entity Registration on Native

On JVM, reflection-based entity discovery works the same as V1 (no changes needed).

On **native platforms** (Linux, iOS, macOS, Android), reflection is limited. You must use
the annotation processor (KSP) to generate entity metadata at compile time:

```kotlin
// build.gradle.kts
plugins {
    id("com.google.devtools.ksp")
}

dependencies {
    ksp("onl.ycode:annproc:2.0.0")
}
```

Annotate your entity classes with `@DbTable` or JPA `@Entity`:

```kotlin
@DbTable("users")
data class User(
    @DbField(primaryKey = true)
    val id: Int = 0,
    val name: String = "",
    val age: Int = 0
)
```

The annotation processor generates the required `TableInfo` registrations automatically.

## DataSource Wrapping (JVM)

On JVM, V2 accepts `javax.sql.DataSource` directly in the `Stormify` constructor (via a
convenience overload). If you need the KDBC `DataSource` interface explicitly:

```kotlin
import onl.ycode.kdbc.JdbcDataSource

val kdbcDataSource = JdbcDataSource(hikariDataSource)
```

The `JdbcDataSource` wrapper has zero overhead — it delegates directly to the underlying
JDBC types.

## Coroutines (New)

V2 adds optional coroutine support with connection pooling:

```kotlin
val pool = DefaultSuspendConnectionPool(dataSource, PoolConfig(
    minConnections = 2,
    maxConnections = 10,
    connectionTimeout = 5.seconds,
    idleTimeout = 10.minutes,
))
val stormify = SuspendStormify(pool)

// All operations are suspend functions
val users = stormify.read<User>("SELECT * FROM users")
stormify.transaction {
    create(user)
}
```

## Quick Migration Checklist

1. **Update dependencies**: `onl.ycode.stormify:db` → `onl.ycode:stormify-jvm`
2. **Replace singleton**: `StormifyManager.stormify()` → `Stormify(dataSource)` constructor
3. **Hold the instance**: pass `Stormify` to services instead of calling static methods
4. **Update transactions**: `TransactionContext.begin()` → `stormify.transaction { }`
5. **Remove Class parameters**: `read(User.class, sql)` → `read<User>(sql)`
6. **Update exception handling**: `QueryException` → `SQLException`
7. **Update stored procedures**: `SPParam` → `Sp.In`/`Sp.Out<T>`/`Sp.InOut<T>`
8. **Add KSP** if targeting native platforms
9. **Optional**: adopt coroutine API for async workloads
