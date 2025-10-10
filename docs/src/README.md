<p align="center">
  <img src="logo.png" alt="Stormify Logo" width="200" height="175">
</p>

# Stormify

Stormify is a flexible ORM library for Kotlin that simplifies database interactions with minimal configuration. It operates and performs CRUD operations on plain Kotlin classes without requiring extensive annotations or XML setups, as long as field names match database columns. This makes Stormify ideal for both small and large projects.

Designed for developers seeking a simple yet powerful ORM, Stormify excels in projects that favor convention over configuration, allowing for minimal setup and clean, straightforward code.

## Features

- **CRUD Operations**: Easily create, read, update, and delete records.
- **Annotation-Free Classes**: Perform operations with plain Kotlin classes without the need for extensive annotations or XML files.
- **Fine or Coarse Grain Definitions**: Define naming policies and primary key resolvers for standard naming patterns, or use annotations to handle special cases.
- **JPA Compatibility**: Support common JPA annotations to maintain compatibility and simplify integration.
- **Flexible Query Execution**: Execute custom and complex SQL queries and map results to Kotlin objects.
- **Transaction Management**: Support for nested transactions with rollback and commit capabilities via savepoints.
- **Support for Composite Keys**: Handle tables with composite primary keys effortlessly.
- **Kotlin Multiplatform**: Multiplatform support with JVM and Native targets.

## Installation

To use Stormify in your Kotlin project, add the library dependency to your build file. Stormify is available through common package managers like Maven and Gradle.

### Maven

```xml
<dependency>
    <groupId>onl.ycode</groupId>
    <artifactId>stormify-jvm</artifactId>
    <version>1.0.0</version>
</dependency>
```

### Gradle

```kotlin
implementation("onl.ycode:stormify-jvm:1.0.0")
```

## Basic Usage

### Configure Your Database

Ensure that your database is set up and accessible. Stormify supports any JDBC-compatible data source. For this example, we'll use HikariCP. Create a `databaseConfig.properties` file with the configuration parameters, add HikariCP to your classpath, and use the following code to initialize Stormify:

```kotlin
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import onl.ycode.stormify.Stormify

val config = HikariConfig("databaseConfig.properties")
val dataSource = HikariDataSource(config)
val stormify = Stormify(dataSource)
```

### Creating an Entity Class

To interact with the database, define a simple Kotlin class that does not need to extend any specific class. The library automatically maps fields based on their names. For example, for a table created as `CREATE TABLE test (id INT PRIMARY KEY, name VARCHAR(255));`, the corresponding class would be:

```kotlin
data class Test(
    var id: Int = 0,
    var name: String = ""
)
```

### Performing CRUD Operations

**Create a Record**:

```kotlin
val newRecord = Test(id = 1, name = "Test Entry")
stormify.create(newRecord)
```

**Read Records**:

```kotlin
val results: List<Test> = stormify.read("SELECT * FROM test")
println(results)
```

**Update a Record**:

```kotlin
newRecord.name = "Updated Entry"
stormify.update(newRecord)
```

**Delete a Record**:

```kotlin
stormify.delete(newRecord)
```

### Using Transactions

```kotlin
stormify.transaction {
    val user = create(User(email = "test@example.com"))
    create(Profile(userId = user.id, name = "Test User"))
    update(account)

    // All operations share the same connection and transaction
}
```

### Advanced Queries

**Query with Parameters**:

```kotlin
val users: List<User> = stormify.read("SELECT * FROM users WHERE age > ?", 25)
```

**Query Single Result**:

```kotlin
val user: User? = stormify.readOne("SELECT * FROM users WHERE id = ?", 1)
```

**Find All with Where Clause**:

```kotlin
val activeUsers: List<User> = stormify.findAll<User>("WHERE status = ?", "active")
```

**Find by ID**:

```kotlin
val user: User? = stormify.findById<User>(1)
```

## Contributing

Contributions are welcome! Please check the [Contributing](Contributing.md) guide for instructions on how to get involved, report issues, or submit pull requests.

## License

Stormify is licensed under the [Apache License 2.0](https://www.apache.org/licenses/LICENSE.2.0). You are free to use, modify, and distribute this library in accordance with the terms of the license.

---

Enjoy using Stormify? Please star this repository to show your support!
