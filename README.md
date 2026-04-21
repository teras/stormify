<p align="center">
  <img src="docs/src/logo.png" alt="Stormify Logo" width="200" height="175">
</p>

# Stormify

<p align="center">
  <a href="https://central.sonatype.com/artifact/onl.ycode/stormify-jvm"><img src="https://img.shields.io/maven-central/v/onl.ycode/stormify-jvm?style=flat-square&logo=apachemaven&logoColor=white&label=Maven%20Central&color=c71a36" alt="Maven Central"></a>
  <a href="https://github.com/teras/stormify/blob/main/LICENSE.md"><img src="https://img.shields.io/badge/License-Apache%202.0-blue?style=flat-square" alt="License"></a>
  <img src="https://img.shields.io/badge/Kotlin-2.2.20-7f52ff?style=flat-square&logo=kotlin&logoColor=white" alt="Kotlin">
  <img src="https://img.shields.io/badge/Platforms-JVM%20%7C%20Native%20%7C%20Android%20%7C%20iOS-4c8cbf?style=flat-square" alt="Platforms">
</p>

Stormify is a flexible ORM library for Kotlin Multiplatform that simplifies database interactions with minimal configuration. It operates and performs CRUD operations on plain Kotlin classes without requiring extensive annotations or XML setups, as long as field names match database columns.

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
- **Paginated Views**: `PagedList<T>` for UI grids (ZK/Compose/Swing) and `PagedQuery<T>` for stateless REST endpoints — filters, sorting, FK traversal, aggregations, facet counts, and streaming iteration over very large result sets.
- **Stored Procedures**: Call stored procedures with input, output, and bidirectional parameters.
- **Support for Composite Keys**: Handle tables with composite primary keys effortlessly.

<p align="center">
  <a href="https://stormify.org/"><img src="https://img.shields.io/badge/Homepage-stormify.org-1e88e5?style=flat-square&logo=firefox&logoColor=white" alt="Homepage"></a>
  <a href="https://stormify.org/docs/2.1.0/"><img src="https://img.shields.io/badge/Docs-Guide-43a047?style=flat-square&logo=readthedocs&logoColor=white" alt="Docs"></a>
  <a href="https://stormify.org/docs/2.1.0/api-stormify/"><img src="https://img.shields.io/badge/API-Reference-fb8c00?style=flat-square&logo=kotlin&logoColor=white" alt="API"></a>
  <a href="https://github.com/teras/stormify-examples/tree/2.1.0"><img src="https://img.shields.io/badge/Examples-Samples-8e24aa?style=flat-square&logo=github&logoColor=white" alt="Examples"></a>
</p>

## Requirements

- **JVM**: Java 11 or later (Stormify is built with a Java 11 toolchain).
- **Android**: minimum API 21 (Android 5.0).
- **Native**: glibc 2.31+ on Linux, macOS 11+, iOS 14+, Windows 10+.

## Installation

### Maven

```xml
<dependency>
    <groupId>onl.ycode</groupId>
    <artifactId>stormify-jvm</artifactId>
    <version>2.1.0</version>
</dependency>
```

### Gradle (JVM)

```kotlin
implementation("onl.ycode:stormify-jvm:2.1.0")
ksp("onl.ycode:annproc:2.1.0")              // optional on JVM
```

### Gradle (Android)

```kotlin
implementation("onl.ycode:stormify-android:2.1.0")
ksp("onl.ycode:annproc:2.1.0")              // required on Android
```

### Gradle (Native)

```kotlin
// Pick the artifact for your target platform:
implementation("onl.ycode:stormify-linuxx64:2.1.0")        // Linux x64
implementation("onl.ycode:stormify-linuxarm64:2.1.0")      // Linux ARM64
implementation("onl.ycode:stormify-mingwx64:2.1.0")        // Windows x64
implementation("onl.ycode:stormify-macosarm64:2.1.0")      // macOS (Apple Silicon)
implementation("onl.ycode:stormify-macosx64:2.1.0")        // macOS (Intel)
implementation("onl.ycode:stormify-iosarm64:2.1.0")        // iOS (device)
implementation("onl.ycode:stormify-iossimulatorarm64:2.1.0") // iOS simulator (Apple Silicon)
implementation("onl.ycode:stormify-iosx64:2.1.0")          // iOS simulator (Intel Mac)
ksp("onl.ycode:annproc:2.1.0")                             // required (no reflection on native)
```

Supported native databases: **PostgreSQL, MariaDB/MySQL, Oracle, MSSQL, SQLite**. On iOS, only SQLite is available.

**Entity metadata**: On JVM, entity metadata is discovered at runtime via `kotlin-reflect`
(included as a transitive dependency). On Native/Android/iOS, use the `annproc` annotation
processor (via KSP) to generate it at compile time. On JVM, `annproc` is optional but
improves startup time and allows excluding `kotlin-reflect`. When using `annproc`, pass
the generated registrar to the constructor:

```kotlin
val stormify = Stormify(dataSource, GeneratedEntities)
```

> **Upgrading from V1?** See the [V1 to V2 migration guide](docs/src/Migration_V1_to_V2.md).

## Basic Usage

### Configure Your Database

Stormify works with any JDBC `DataSource`. The examples below use HikariCP, but any connection pool or plain driver will work.

**Kotlin (JVM):**

```kotlin
val config = HikariConfig("databaseConfig.properties")
val dataSource = HikariDataSource(config)
val stormify = Stormify(dataSource)
```

**Java:**

```java
HikariConfig config = new HikariConfig("databaseConfig.properties");
HikariDataSource dataSource = new HikariDataSource(config);
StormifyJ stormify = new StormifyJ(dataSource);
```

**Android:**

```kotlin
val db = context.openOrCreateDatabase("mydb.db", Context.MODE_PRIVATE, null)
val stormify = Stormify(db)
```

**Native:**

```kotlin
val ds = KdbcDataSource("jdbc:postgresql://localhost:5432/mydb", "user", "pass")
val stormify = Stormify(ds)
```

### Creating an Entity Class

Define a simple Kotlin class. The library automatically maps fields based on their names.
For a table `CREATE TABLE test (id INT PRIMARY KEY, name VARCHAR(255))`:

```kotlin
@DbTable("test")  // optional on JVM — class name is used by default
data class Test(
    @DbField(primaryKey = true)
    var id: Int = 0,
    var name: String = ""
)
```

Mark primary keys with `@DbField(primaryKey = true)`, or register a primary key resolver to detect them by naming convention.

### Performing CRUD Operations

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

### Using Transactions

**Kotlin:**

```kotlin
stormify.transaction {
    val user = create(User(email = "test@example.com"))
    create(Profile(userId = user.id, name = "Test User"))
    update(account)
}
```

**Java:**

```java
stormify.transaction(tx -> {
    User user = tx.create(new User("test@example.com"));
    tx.create(new Profile(user.getId(), "Test User"));
    tx.update(account);
});
```

### Advanced Queries

```kotlin
// Query with parameters
val users = stormify.read<User>("SELECT * FROM users WHERE age > ?", 25)

// Single result
val user = stormify.readOne<User>("SELECT * FROM users WHERE id = ?", 1)

// Find by ID
val user = stormify.findById<User>(1)
```

## Examples

Runnable example projects live in a separate repository: [stormify-examples](https://github.com/teras/stormify-examples/tree/2.1.0). They cover JVM (Kotlin & Java), Android, iOS, Kotlin/Native (Linux, Windows, macOS), and Kotlin Multiplatform.

Clone them standalone:

```bash
git clone -b 2.1.0 https://github.com/teras/stormify-examples.git
```

Or pull them directly inside this repo as a submodule:

```bash
git submodule update --init --recursive
```

Each subfolder is a self-contained project with its own `README.md` explaining how to build and run it. See the [Examples overview](docs/src/Examples.md) for a short description of each.

## Documentation

Full documentation is available at [stormify.org/docs](https://stormify.org/docs/2.1.0/).

## Contributing

Contributions are welcome! Please check the [Contributing](docs/src/Contributing.md) guide for instructions on how to get involved, report issues, or submit pull requests.

## License

Stormify is licensed under the [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0). You are free to use, modify, and distribute this library in accordance with the terms of the license.

---

Enjoy using Stormify? Please star this repository to show your support!
