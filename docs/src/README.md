<p align="center">
  <img src="logo.png" alt="Stormify Logo" width="200" height="175">
</p>

# Stormify

Stormify is a flexible ORM library for Java and Kotlin that simplifies database interactions with minimal configuration.
It operates and performs CRUD operations on plain Java objects (POJOs) without requiring extensive annotations or XML
setups, as long as field names match database columns. This makes Stormify ideal for both small and large projects.

Designed for developers seeking a simple yet powerful ORM, Stormify excels in projects that favor convention over
configuration, allowing for minimal setup and clean, straightforward code.

## Features

- **CRUD Operations**: Easily create, read, update, and delete records.
- **Annotation-Free POJOs**: Perform operations with plain Java objects without the need for extensive annotations or
  XML files.
- **Fine or coarse grain definitions**: Define naming policies and primary key resolvers, if there is a standard naming
  pattern, or annotations to handle special cases.
- **JPA Compatibility**: Support common JPA annotations to maintain compatibility and simplify integration.
- **Flexible Query Execution**: Execute custom and complex SQL queries and map results to Java objects.
- **Transaction Management**: Support for nested transactions with rollback and commit capabilities.
- **Support for Composite Keys**: Handle tables with composite primary keys effortlessly.
- **Kotlin Compatibility**: Fully compatible with Kotlin, allowing seamless integration in Kotlin-based projects.

## Installation

To use Stormify in your Java or Kotlin project, add the library dependency to your build file. Stormify is available
through common package managers like Maven and Gradle.

### Maven

=== "Java"

    ```xml
    <dependency>
        <groupId>onl.ycode.stormify</groupId>
        <artifactId>db</artifactId>
        <version>1.0.0</version>
    </dependency>
    ```

=== "Kotlin"

    ```xml
    <dependencies>
        <dependency>
            <groupId>onl.ycode.stormify</groupId>
            <artifactId>db</artifactId>
            <version>1.0.0</version>
        </dependency>
        <dependency>
            <groupId>onl.ycode.stormify</groupId>
            <artifactId>kotlin</artifactId>
            <version>1.0.0</version>
        </dependency>
    </dependencies>
    ```

### Gradle

=== "Java"

    ```groovy
    implementation 'onl.ycode.stormify:db:1.0.0'
    ```

=== "Kotlin"

    ```kotlin
    implementation("onl.ycode.stormify:db:1.0.0")
    implementation("onl.ycode.stormify:kotlin:1.0.0")
    ```

## Basic Usage

### Configure Your Database

Ensure that your database is set up and accessible. Stormify supports any
JDBC-compatible data source. For this example, to use HikariCP, create a `databaseConfig.properties` file with the
configuration parameters, add Hikari to your classpath and use the following code to initialize Stormify:

=== "Java"

    ```java
    import com.zaxxer.hikari.HikariConfig;
    import com.zaxxer.hikari.HikariDataSource;

    import static onl.ycode.stormify.StormifyManager.stormify;

    ...
    HikariConfig config = new HikariConfig("databaseConfig.properties");
    HikariDataSource dataSource = new HikariDataSource(config);

    stormify().setDataSource(dataSource);
    ```

=== "Kotlin"

    ```kotlin
    import com.zaxxer.hikari.HikariConfig
    import com.zaxxer.hikari.HikariDataSource

    import onl.ycode.stormify.StormifyManager.stormify

    ...
    val config = HikariConfig("databaseConfig.properties")
    val dataSource = HikariDataSource(config)

    stormify().setDataSource(dataSource)
    ```

### Creating a POJO

To interact with the database, define a simple POJO that does not need to extend any specific class. The library
automatically maps fields based on their names. For example, for a table creates as
`CREATE TABLE test (id INT PRIMARY KEY, name VARCHAR(255));`,
the corresponding POJO would be:

=== "Java"

    ```java
    public class Test {
        private int id;
        private String name;

        // Getters and setters
    }
    ```

=== "Kotlin"

    ```kotlin
    class Test(
        var id: Int,
        var name: String
    )
    ```

### Performing CRUD Operations

**Create a Record**:

=== "Java"

    ```java
    Test newRecord = new Test();
    newRecord.setId(1);
    newRecord.setName("Test Entry");
    stormify().create(newRecord);
    ```

=== "Kotlin"

    ```kotlin
    val newRecord = Test()
    newRecord.id = 1
    newRecord.name = "Test Entry"
    newRecord.create()
    ```

**Read Records**:

=== "Java"

    ```java
    List<Test> results = stormify().read(Test.class, "SELECT * FROM test");
    System.out.println(results);
    ```

=== "Kotlin"

    ```kotlin
    val results = "SELECT * FROM test".read<Test>()  
    println(results)
    ```

**Update a Record**:

=== "Java"

    ```java
    newRecord.setName("Updated Entry");
    stormify().update(newRecord);
    ```

=== "Kotlin"

    ```kotlin
    newRecord.name = "Updated Entry"
    newRecord.update()
    ```

**Delete a Record**:

=== "Java"

    ```java
    stormify().delete(newRecord);
    ```

=== "Kotlin"

    ```kotlin
    newRecord.delete()
    ```

## Contributing

Contributions are welcome! Please check the [Contributing](Contributing.md) guide for instructions on how to get
involved, report issues, or submit pull requests.

## License

Stormify is licensed under the [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0). You are free to use,
modify, and distribute this library in accordance with the terms of the license.

---

Enjoy using Stormify? Please star this repository to show your support!
