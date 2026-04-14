<p align="center">
  <img src="logo.png" alt="Stormify Logo" width="200" height="175">
</p>

# Stormify

Stormify is a flexible ORM library for Kotlin Multiplatform that simplifies database interactions with minimal configuration. It operates and performs CRUD operations on plain Kotlin classes without requiring extensive annotations or XML setups, as long as field names match database columns. Custom mappings are available through annotations when needed, but are not required.

Designed for developers seeking a simple yet powerful ORM, Stormify excels in projects that favor convention over configuration, allowing for minimal setup and clean, straightforward code.

## Features

- **Kotlin Multiplatform**: JVM (Java & Kotlin), Android, Linux (x64 & ARM64), Windows (x64), macOS, and iOS — same API across all platforms.
- **Native Database Access**: Direct access to PostgreSQL, MariaDB/MySQL, Oracle, MSSQL, and SQLite on Linux, Windows, and macOS without JVM or JDBC.
- **Android Support**: Full ORM on Android's built-in SQLite, with compile-time entity metadata via annotation processing.
- **iOS Support**: SQLite-based ORM on iOS devices and simulators.
- **CRUD Operations**: Easily create, read, update, and delete records, with batch variants for bulk operations.
- **Annotation-Free Classes**: Perform operations with plain Kotlin classes without the need for extensive annotations or XML files.
- **Fine or Coarse Grain Definitions**: Define naming policies and primary key resolvers for standard naming patterns, or use annotations to handle special cases.
- **JPA Compatibility**: Support common JPA annotations to maintain compatibility and simplify integration.
- **Flexible Query Execution**: Execute custom and complex SQL queries and map results to Kotlin objects, with automatic collection parameter expansion for `IN` clauses.
- **Transaction Management**: Support for nested transactions with rollback and commit capabilities via savepoints.
- **Coroutines**: Suspend-based transaction API with a built-in connection pool, coroutine cancellation wired to native database cancel primitives.
- **Enum Properties**: Enum fields stored as integers or strings, with support for custom mappings.
- **Lazy Loading**: Reference fields with `by db()` delegates for automatic lazy loading of related entities.
- **Paginated Views**: `PagedList<T>` for data grids and pickers — column filters, sorting, FK traversal, aggregations, facet counts, and streaming iteration over very large result sets.
- **Stored Procedures**: Call stored procedures with input, output, and bidirectional parameters.
- **Support for Composite Keys**: Handle tables with composite primary keys effortlessly.

## Installation

=== "Gradle (Kotlin)"

    ```kotlin
    implementation("onl.ycode:stormify-jvm:2.0.0")
    ksp("onl.ycode:annproc:2.0.0")          // optional on JVM
    ```

=== "Gradle (Android)"

    ```kotlin
    implementation("onl.ycode:stormify-android:2.0.0")
    ksp("onl.ycode:annproc:2.0.0")          // required on Android
    ```

=== "Gradle (Native)"

    ```kotlin
    // Pick the artifact for your target platform:
    implementation("onl.ycode:stormify-linuxx64:2.0.0")        // Linux x64
    implementation("onl.ycode:stormify-linuxarm64:2.0.0")      // Linux ARM64
    implementation("onl.ycode:stormify-mingwx64:2.0.0")        // Windows x64
    implementation("onl.ycode:stormify-macosarm64:2.0.0")      // macOS (Apple Silicon)
    implementation("onl.ycode:stormify-macosx64:2.0.0")        // macOS (Intel)
    implementation("onl.ycode:stormify-iosarm64:2.0.0")        // iOS (device)
    implementation("onl.ycode:stormify-iossimulatorarm64:2.0.0") // iOS (simulator)
    ksp("onl.ycode:annproc:2.0.0")                             // required (no reflection on native)
    ```

=== "Gradle (Java)"

    ```groovy
    implementation 'onl.ycode:stormify-jvm:2.0.0'
    ksp 'onl.ycode:annproc:2.0.0'  // optional on JVM
    ```

=== "Maven"

    ```xml
    <dependency>
        <groupId>onl.ycode</groupId>
        <artifactId>stormify-jvm</artifactId>
        <version>2.0.0</version>
    </dependency>
    ```

**Entity metadata**: Stormify needs metadata (field names, types, primary keys) to perform
ORM operations. On **JVM**, this is discovered at runtime via `kotlin-reflect` (included
as a transitive dependency). On **Native/Android/iOS**, reflection is not available — use
the `annproc` annotation processor (via KSP) to generate it at compile time. On JVM,
`annproc` is optional but improves startup time and allows excluding `kotlin-reflect`.
See [Annotation Processor](Annotations.md#annotation-processor-annproc) for setup
details and how to exclude `kotlin-reflect`. When using `annproc`, pass the generated
registrar to the constructor:

```kotlin
val stormify = Stormify(dataSource, GeneratedEntities)
```

**Native database libraries** — install only the ones you need. On iOS, only SQLite
is available. Supported on Linux/macOS/Windows: PostgreSQL, MariaDB/MySQL, Oracle, MSSQL, SQLite.

```bash
# Debian / Ubuntu
sudo apt install libsqlite3-0 libpq5 libmariadb3

# Arch Linux
sudo pacman -S sqlite postgresql-libs mariadb-libs
```

See [Database Configuration](Database_Configuration.md) for Windows and advanced setup.

## Basic Usage

### Configure Your Database

Stormify works with any JDBC `DataSource`. The examples below use HikariCP, but any connection pool or plain driver will work.

=== "Kotlin"

    ```kotlin
    import com.zaxxer.hikari.HikariConfig
    import com.zaxxer.hikari.HikariDataSource
    import onl.ycode.stormify.Stormify

    val config = HikariConfig("databaseConfig.properties")
    val dataSource = HikariDataSource(config)
    val stormify = Stormify(dataSource)
    ```

=== "Native"

    ```kotlin
    import onl.ycode.stormify.Stormify
    import onl.ycode.kdbc.KdbcDataSource

    // PostgreSQL
    val ds = KdbcDataSource("jdbc:postgresql://localhost:5432/mydb", "user", "pass")
    val stormify = Stormify(ds)

    // SQLite
    val ds = KdbcDataSource("jdbc:sqlite:/tmp/mydb.db")
    val stormify = Stormify(ds)
    ```

=== "Java"

    Java users should use `StormifyJ` and `TransactionContextJ` for idiomatic Java APIs
    with `Class<T>` parameters and `Consumer` callbacks.

    ```java
    import com.zaxxer.hikari.HikariConfig;
    import com.zaxxer.hikari.HikariDataSource;
    import onl.ycode.stormify.StormifyJ;

    HikariConfig config = new HikariConfig("databaseConfig.properties");
    HikariDataSource dataSource = new HikariDataSource(config);
    StormifyJ stormify = new StormifyJ(dataSource);
    ```

### Creating an Entity Class

Define a simple class. The library automatically maps fields based on their names.
For a table `CREATE TABLE test (id INT PRIMARY KEY, name VARCHAR(255))`:

=== "Kotlin"

    ```kotlin
    @DbTable("test")  // optional on JVM — class name is used by default
    data class Test(
        @DbField(primaryKey = true)
        var id: Int = 0,
        var name: String = ""
    )
    ```

=== "Java"

    ```java
    public class Test {
        @DbField(primaryKey = true)
        private int id;
        private String name;

        // getters and setters
    }
    ```

Mark primary keys with `@DbField(primaryKey = true)`, or register a
[primary key resolver](Primary_Keys.md#custom-primary-key-resolvers) to detect them by naming convention.

### Performing CRUD Operations

=== "Kotlin"

    ```kotlin
    // Create
    val record = stormify.create(Test(id = 1, name = "Test Entry"))

    // Read
    val results = stormify.read<Test>("SELECT * FROM test")

    // Update
    record.name = "Updated Entry"
    stormify.update(record)

    // Delete
    stormify.delete(record)
    ```

=== "Java"

    ```java
    // Create
    Test record = stormify.create(new Test(1, "Test Entry"));

    // Read
    List<Test> results = stormify.read(Test.class, "SELECT * FROM test");

    // Update
    record.setName("Updated Entry");
    stormify.update(record);

    // Delete
    stormify.delete(record);
    ```

### Using Transactions

=== "Kotlin"

    ```kotlin
    stormify.transaction {
        val user = create(User(email = "test@example.com"))
        create(Profile(userId = user.id, name = "Test User"))
        update(account)

        // All operations share the same connection and transaction
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

### Advanced Queries

=== "Kotlin"

    ```kotlin
    // Query with parameters
    val users = stormify.read<User>("SELECT * FROM users WHERE age > ?", 25)

    // Single result
    val user = stormify.readOne<User>("SELECT * FROM users WHERE id = ?", 1)

    // Find all with where clause
    val activeUsers = stormify.findAll<User>("WHERE status = ?", "active")

    // Find by ID
    val user = stormify.findById<User>(1)
    ```

=== "Java"

    ```java
    // Query with parameters
    List<User> users = stormify.read(User.class, "SELECT * FROM users WHERE age > ?", 25);

    // Single result
    User user = stormify.readOne(User.class, "SELECT * FROM users WHERE id = ?", 1);

    // Find by ID
    User user = stormify.findById(User.class, 1);
    ```

## Examples

The `examples/` directory contains self-contained demo applications:

| Example | Description | Run |
|---------|-------------|-----|
| **java** | Java POJOs with JPA + Stormify annotations | `mvn compile exec:java` |
| **kotlin-jvm** | Kotlin JVM with `by db()` delegates | `gradle run` |
| **kotlin-linux** | Native Linux binary (no JVM) | `gradle runDebugExecutableLinuxX64` |
| **kotlin-multiplatform** | Shared code running on JVM and native | `gradle jvmRun` |
| **android** | Compose app with ViewModel, CRUD, lazy refs, enums | `gradle :app:installDebug` |

Each example demonstrates CRUD operations, enum properties with custom values, entity references with lazy loading, transactions with rollback, and raw SQL queries using an in-memory SQLite database.

## Contributing

Contributions are welcome! Please check the [Contributing](Contributing.md) guide for instructions on how to get involved, report issues, or submit pull requests.

## License

Stormify is licensed under the [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0). You are free to use, modify, and distribute this library in accordance with the terms of the license.

---

Enjoy using Stormify? Please star this repository to show your support!
