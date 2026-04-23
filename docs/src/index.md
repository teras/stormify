# Stormify

Stormify is an ORM library for Kotlin Multiplatform. Plain Kotlin data classes
become database entities by convention — no DSL, no XML, no annotations needed
until the schema forces your hand. Raw SQL sits alongside the ORM without
entity registration. Paged queries with filters, sorting, FK traversal, and
facets are part of the core rather than a plugin, lazy-loaded references
resolve on first access, and the suspend-based API ties coroutine cancellation
to the database driver's own cancel primitive.

The same API runs on JVM (Java & Kotlin), Android, Linux (x64 & ARM64), Windows,
macOS, and iOS. JVM and Android build on JDBC and the platform SQLite; the
native targets connect to PostgreSQL, MariaDB/MySQL, Oracle, MS SQL Server, and
SQLite through the standard C client libraries, without a JDBC layer in between.
The code you write against Stormify travels across every target the Kotlin
toolchain supports.

## Features

- **Kotlin Multiplatform**: JVM, Android, Linux (x64 & ARM64), Windows x64, macOS
  (Apple Silicon & Intel), and iOS (device + simulators) — one API, one set of
  entity classes.
- **Native database access**: direct access to PostgreSQL, MariaDB/MySQL, Oracle,
  MS SQL Server, and SQLite on Linux, Windows, and macOS without JVM or JDBC in
  the loop.
- **Android & iOS**: full ORM on Android's built-in SQLite and iOS `libsqlite3`,
  with compile-time metadata via the `annproc` KSP processor.
- **CRUD operations**: create, read, update, delete — with batch variants for
  bulk workloads.
- **Annotation-free classes**: plain Kotlin data classes work out of the box;
  reach for annotations only when the defaults don't fit.
- **JPA compatibility**: common `javax.persistence` annotations (`@Id`, `@Table`,
  `@Column`, `@JoinColumn`, `@SequenceGenerator`, `@Enumerated`, `@Transient`) are
  honored, so existing JPA entities drop in with little to no change.
- **Flexible query execution**: parameterized SQL mapped straight to Kotlin
  objects, with automatic collection expansion for `IN (?)` clauses.
- **Transactions**: nested via database savepoints, with automatic rollback on
  exceptions.
- **Coroutines**: suspend-based transaction API with a built-in connection pool;
  cancellation is wired to native database cancel primitives.
- **Enum properties**: stored as ordinals, strings, or custom integer mappings.
- **Lazy loading**: parent references via `by db()` and child collections via
  `by lazyDetails()` auto-load when first accessed.
- **Paginated views**: [`PagedList<T>`](PagedList.md) for UI grids (ZK / Compose /
  Swing) and [`PagedQuery<T>`](PagedQuery.md) for stateless REST endpoints —
  filters, sorting, FK traversal, aggregations, facet counts, and streaming over
  very large result sets.
- **Stored procedures**: input, output, and bidirectional parameters.
- **Composite primary keys**: multi-column keys are first-class.

## Quick Start

A complete, five-minute walkthrough from zero to your first query.

### 1. Add the dependency

=== "Gradle (JVM)"

    ```kotlin
    implementation("onl.ycode:stormify-jvm:2.1.2-SNAPSHOT")
    ```

=== "Maven (JVM)"

    ```xml
    <dependency>
        <groupId>onl.ycode</groupId>
        <artifactId>stormify-jvm</artifactId>
        <version>2.1.2-SNAPSHOT</version>
    </dependency>
    ```

For Android, native targets, or the `annproc` KSP processor, see
[Installation](Installation.md).

### 2. Define an entity

Given a table `CREATE TABLE user (id INT PRIMARY KEY, name VARCHAR(255), email VARCHAR(255))`:

=== "Kotlin"

    ```kotlin
    import onl.ycode.stormify.DbField
    import onl.ycode.stormify.DbTable

    @DbTable         // Optional on JVM; required on Native/Android/iOS
    data class User(
        @DbField(primaryKey = true)
        var id: Int = 0,
        var name: String = "",
        var email: String = ""
    )
    ```

=== "Java"

    ```java
    import onl.ycode.stormify.DbField;
    import onl.ycode.stormify.DbTable;

    @DbTable  // Optional on JVM
    public class User {
        @DbField(primaryKey = true)
        private int id;
        private String name;
        private String email;

        // getters and setters
    }
    ```

Field names auto-map to columns via the naming policy (snake_case by default).
Override with `@DbField(name = "...")` or `@DbTable(name = "...")` when the
schema doesn't match.

### 3. Create a `Stormify` instance

=== "Kotlin"

    ```kotlin
    import com.zaxxer.hikari.HikariConfig
    import com.zaxxer.hikari.HikariDataSource
    import onl.ycode.stormify.Stormify

    val dataSource = HikariDataSource(HikariConfig("databaseConfig.properties"))
    val stormify = Stormify(dataSource)
    ```

=== "Java"

    ```java
    import com.zaxxer.hikari.HikariConfig;
    import com.zaxxer.hikari.HikariDataSource;
    import onl.ycode.stormify.StormifyJ;

    HikariConfig config = new HikariConfig("databaseConfig.properties");
    HikariDataSource dataSource = new HikariDataSource(config);
    StormifyJ stormify = new StormifyJ(dataSource);
    ```

Any `javax.sql.DataSource` works — connection pool, plain driver, anything that
speaks JDBC. Native, Android, and iOS setups are covered in
[Configuration](Configuration.md).

### 4. Perform CRUD in a transaction

=== "Kotlin"

    ```kotlin
    stormify.transaction {
        // Create
        val alice = create(User(id = 1, name = "Alice", email = "alice@example.com"))

        // Read
        val all = read<User>("SELECT * FROM user")
        val one = findById<User>(1)

        // Update
        alice.email = "alice@new.example.com"
        update(alice)

        // Delete
        delete(alice)
    }
    ```

=== "Java"

    ```java
    stormify.transaction(tx -> {
        // Create
        User alice = new User();
        alice.setId(1);
        alice.setName("Alice");
        alice.setEmail("alice@example.com");
        tx.create(alice);

        // Read
        List<User> all = tx.read(User.class, "SELECT * FROM user");
        User one = tx.findById(User.class, 1);

        // Update
        alice.setEmail("alice@new.example.com");
        tx.update(alice);

        // Delete
        tx.delete(alice);
    });
    ```

The block shares a single connection across all operations, commits on normal
return, and rolls back on any thrown exception. Nested `transaction { ... }`
calls use savepoints automatically.

## Next Steps

- **[Installation](Installation.md)** — artifacts for every platform, KSP
  setup, native runtime libraries.
- **[Database Configuration](Configuration.md)** — data source wiring, logging,
  connection pooling, application-server deployment.
- **[Entity Mapping](Entity_Mapping.md)** and **[Annotations](Annotations.md)** —
  naming policies, primary key resolvers, `@DbTable` / `@DbField` / JPA.
- **[CRUD](CRUD.md)**, **[Transactions](Transactions.md)**,
  **[Raw Queries](Raw_Queries.md)** — the core runtime API.
- **[PagedList](PagedList.md)** and **[PagedQuery](PagedQuery.md)** — pagination,
  filtering, sorting, facets.
- **[KDBC Overview](KDBC_Overview.md)** — the native database layer Stormify
  builds on.
- **[Examples](Examples.md)** — runnable sample projects across every platform.
- **[Migrating from v1 to v2](Migration_V1_to_V2.md)** — upgrading from Stormify 1.x.

Stormify is licensed under the [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0).
