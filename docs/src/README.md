<p align="center">
  <img src="logo.png" alt="Stormify Logo" width="200" height="175">
</p>

# Stormify

Stormify is a flexible ORM library for Kotlin Multiplatform that simplifies database interactions with minimal configuration. It operates and performs CRUD operations on plain Kotlin classes without requiring extensive annotations or XML setups, as long as field names match database columns.

Designed for developers seeking a simple yet powerful ORM, Stormify excels in projects that favor convention over configuration, allowing for minimal setup and clean, straightforward code.

## Features

- **Kotlin Multiplatform**: JVM and Linux native — same API, no JVM required on native.
- **Native Database Access**: Direct access to PostgreSQL, MariaDB/MySQL, Oracle, MSSQL, and SQLite on Linux without JVM or JDBC.
- **CRUD Operations**: Easily create, read, update, and delete records.
- **Annotation-Free Classes**: Perform operations with plain Kotlin classes without the need for extensive annotations or XML files.
- **Fine or Coarse Grain Definitions**: Define naming policies and primary key resolvers for standard naming patterns, or use annotations to handle special cases.
- **JPA Compatibility**: Support common JPA annotations to maintain compatibility and simplify integration.
- **Flexible Query Execution**: Execute custom and complex SQL queries and map results to Kotlin objects.
- **Transaction Management**: Support for nested transactions with rollback and commit capabilities via savepoints.
- **Support for Composite Keys**: Handle tables with composite primary keys effortlessly.

## Installation

=== "Kotlin"

    **Gradle:**

    ```kotlin
    implementation("onl.ycode:stormify-jvm:2.0.0")
    ```

    **Maven:**

    ```xml
    <dependency>
        <groupId>onl.ycode</groupId>
        <artifactId>stormify-jvm</artifactId>
        <version>2.0.0</version>
    </dependency>
    ```

=== "Java"

    **Gradle:**

    ```groovy
    implementation 'onl.ycode:stormify-jvm:2.0.0'
    ```

    **Maven:**

    ```xml
    <dependency>
        <groupId>onl.ycode</groupId>
        <artifactId>stormify-jvm</artifactId>
        <version>2.0.0</version>
    </dependency>
    ```

    Java users should use `StormifyJ` and `TransactionContextJ` for idiomatic Java APIs
    with `Class<T>` parameters and `Consumer` callbacks.

=== "Native"

    For native Linux applications (no JVM required):

    ```kotlin
    implementation("onl.ycode:stormify-linuxx64:2.0.0")
    ```

    Supported databases: **PostgreSQL, MariaDB/MySQL, Oracle, MSSQL, SQLite**.

    Database client libraries are loaded at runtime via `dlopen` — only install the
    ones you need:

    ```bash
    # Debian / Ubuntu
    sudo apt install libsqlite3-0 libpq5 libmariadb3

    # Arch Linux
    sudo pacman -S sqlite postgresql-libs mariadb-libs
    ```

    Annotation processing via KSP is **required** on native (no reflection):

    ```kotlin
    plugins {
        id("com.google.devtools.ksp")
    }

    dependencies {
        ksp("onl.ycode:annproc:2.0.0")
    }
    ```

## Basic Usage

### Configure Your Database

=== "Kotlin"

    ```kotlin
    import com.zaxxer.hikari.HikariConfig
    import com.zaxxer.hikari.HikariDataSource
    import onl.ycode.stormify.Stormify

    val config = HikariConfig("databaseConfig.properties")
    val dataSource = HikariDataSource(config)
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

### Creating an Entity Class

Define a simple class. The library automatically maps fields based on their names.
For a table `CREATE TABLE test (id INT PRIMARY KEY, name VARCHAR(255))`:

=== "Kotlin"

    ```kotlin
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
[primary key resolver](Core_concepts.md#custom-primary-key-resolvers) to detect them by naming convention.

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

## Contributing

Contributions are welcome! Please check the [Contributing](Contributing.md) guide for instructions on how to get involved, report issues, or submit pull requests.

## License

Stormify is licensed under the [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0). You are free to use, modify, and distribute this library in accordance with the terms of the license.

---

Enjoy using Stormify? Please star this repository to show your support!
