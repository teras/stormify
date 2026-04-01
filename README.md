<p align="center">
  <img src="docs/src/logo.png" alt="Stormify Logo" width="200" height="175">
</p>

# Stormify

Stormify is a flexible ORM library for Java and Kotlin that performs CRUD operations on plain Java objects (POJOs)
without requiring extensive annotations or XML setups, as long as property names match database columns.
Convention over configuration, minimal setup, clean code.

[Documentation](https://stormify.org/docs/) | [Homepage](https://stormify.org)

## Features

- **CRUD Operations**: Create, read, update, and delete records, including batch operations.
- **Annotation-Free POJOs**: Work with plain Java objects without annotations or XML files.
- **Flexible Naming**: Define naming policies and primary key resolvers, or use annotations for special cases.
- **JPA Compatibility**: Support common JPA annotations for seamless integration.
- **Raw SQL**: Execute custom SQL queries and map results to Java objects.
- **Transactions**: Nested transactions with automatic savepoint-based rollback.
- **Composite Keys**: Handle tables with composite primary keys.
- **Auto-Population**: Lazy-load entity fields on demand using `AutoTable`, with batch optimization.
- **Stored Procedures**: Execute stored procedures with IN, OUT, and INOUT parameters.
- **Cursor-Based Reading**: Stream large result sets row by row.
- **Parent-Child Queries**: Retrieve child records of a parent entity with a single call.
- **Custom Type Conversions**: Register custom converters between Java and database types.
- **Kotlin Support**: Dedicated extension functions and property delegates.

## Tested Databases

MySQL, MariaDB, PostgreSQL, Oracle (11g+), SQL Server (2012+), and SQLite.

## Installation

### Maven

```xml
<dependency>
    <groupId>onl.ycode.stormify</groupId>
    <artifactId>db</artifactId>
    <version>1.2.0</version>
</dependency>
```

For Kotlin, also add:

```xml
<dependency>
    <groupId>onl.ycode.stormify</groupId>
    <artifactId>kotlin</artifactId>
    <version>1.2.0</version>
</dependency>
```

### Gradle

```groovy
implementation 'onl.ycode.stormify:db:1.2.0'
implementation 'onl.ycode.stormify:kotlin:1.2.0'  // Kotlin only
```

## Quick Start

### Configure Your Database

Stormify works with any JDBC `DataSource`. Here's an example using HikariCP:

```java
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import static onl.ycode.stormify.StormifyManager.stormify;

HikariConfig config = new HikariConfig("databaseConfig.properties");
HikariDataSource dataSource = new HikariDataSource(config);

stormify().setDataSource(dataSource);
```

### Define a POJO

Define a POJO with getter/setter pairs matching your database columns. For a table created as
`CREATE TABLE test (id INT PRIMARY KEY, name VARCHAR(255));`:

```java
public class Test {
    private int id;
    private String name;

    // Getters and setters
}
```

### CRUD Operations

```java
// Create
Test record = new Test();
record.setId(1);
record.setName("Test Entry");
stormify().create(record);

// Read
List<Test> results = stormify().read(Test.class, "SELECT * FROM test");

// Update
record.setName("Updated Entry");
stormify().update(record);

// Delete
stormify().delete(record);
```

See the [full documentation](https://stormify.org/docs/) for Kotlin examples, transactions, batch operations,
lazy loading, stored procedures, and more.

## Contributing

Contributions are welcome! See the [Contributing](docs/src/Contributing.md) guide.

## License

[Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)
