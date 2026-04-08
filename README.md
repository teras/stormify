<p align="center">
  <img src="docs/src/logo.png" alt="Stormify Logo" width="200" height="175">
</p>

# Stormify

Stormify is a flexible ORM library for Kotlin Multiplatform that simplifies database interactions with minimal configuration. It operates and performs CRUD operations on plain Kotlin classes without requiring extensive annotations or XML setups, as long as field names match database columns.

Designed for developers seeking a simple yet powerful ORM, Stormify excels in projects that favor convention over configuration, allowing for minimal setup and clean, straightforward code.

## Features

- **Kotlin Multiplatform**: JVM (Java & Kotlin) and Linux native — same API, no JVM required on native.
- **Native Database Access**: Direct access to PostgreSQL, MariaDB/MySQL, Oracle, MSSQL, and SQLite on Linux without JVM or JDBC.
- **CRUD Operations**: Easily create, read, update, and delete records, with batch variants for bulk operations.
- **Annotation-Free Classes**: Perform operations with plain Kotlin classes without the need for extensive annotations or XML files.
- **Fine or Coarse Grain Definitions**: Define naming policies and primary key resolvers for standard naming patterns, or use annotations to handle special cases.
- **JPA Compatibility**: Support common JPA annotations to maintain compatibility and simplify integration.
- **Flexible Query Execution**: Execute custom and complex SQL queries and map results to Kotlin objects, with automatic collection parameter expansion for `IN` clauses.
- **Transaction Management**: Support for nested transactions with rollback and commit capabilities via savepoints.
- **Coroutines**: Suspend-based transaction API with a built-in connection pool, coroutine cancellation wired to native database cancel primitives.
- **Enum Properties**: Enum fields stored as integers (ordinal or custom values via `DbValue`) or strings (`@DbField(enumAsString = true)`).
- **Lazy Loading**: Reference fields with `by db()` delegates for automatic lazy loading of related entities.
- **Stored Procedures**: Call stored procedures with input, output, and bidirectional parameters.
- **Support for Composite Keys**: Handle tables with composite primary keys effortlessly.

## Installation

### Maven

```xml
<dependency>
    <groupId>onl.ycode</groupId>
    <artifactId>stormify-jvm</artifactId>
    <version>2.0.0</version>
</dependency>
```

### Gradle (JVM)

```kotlin
implementation("onl.ycode:stormify-jvm:2.0.0")
ksp("onl.ycode:annproc:2.0.0")              // optional on JVM
```

### Gradle (Native)

```kotlin
implementation("onl.ycode:stormify-linuxx64:2.0.0")
ksp("onl.ycode:annproc:2.0.0")              // required (no reflection on native)
```

Supported native databases: **PostgreSQL, MariaDB/MySQL, Oracle, MSSQL, SQLite** — loaded at runtime via `dlopen`.

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

**Native:**

```kotlin
val ds = KdbcDataSource("jdbc:postgresql://localhost:5432/mydb", "user", "pass")
val stormify = Stormify(ds)
```

### Creating an Entity Class

Define a simple Kotlin class. The library automatically maps fields based on their names.
For a table `CREATE TABLE test (id INT PRIMARY KEY, name VARCHAR(255))`:

```kotlin
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

## Documentation

Full documentation is available at [stormify.org/docs](https://stormify.org/docs/).

## Contributing

Contributions are welcome! Please check the [Contributing](docs/src/Contributing.md) guide for instructions on how to get involved, report issues, or submit pull requests.

## License

Stormify is licensed under the [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0). You are free to use, modify, and distribute this library in accordance with the terms of the license.

---

Enjoy using Stormify? Please star this repository to show your support!
