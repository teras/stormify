# Database Configuration

## Supported Databases

Stormify auto-detects the database dialect from the JDBC connection metadata. The following databases are tested and
supported:

| Database | Versions | Dialect |
|----------|----------|---------|
| MySQL | 5.7+, 8.x | `MYSQL_OLD`, `MYSQL_NEW` |
| MariaDB | 10.2+, 10.3+, 11.x | `MARIA_DB_OLD`, `MARIA_DB_NEW` |
| PostgreSQL | 10+ | `POSTGRESQL` |
| Oracle | 11g (11.2), 12c+ (12.1+), 21c | `ORACLE_OLD`, `ORACLE_NEW` |
| SQL Server | 2012+, 2017+, 2022 | `SQL_SERVER_OLD`, `SQL_SERVER_NEW` |
| SQLite | All versions | `SQLITE` |

!!! tip "Dialect override"
    If auto-detection fails (e.g., when using a database proxy), you can set the dialect manually:
    ```java
    stormify().setSqlDialect(SqlDialect.ORACLE_OLD);
    ```

## Data Source Configuration

Stormify relies on a JDBC-compatible `javax.sql.DataSource` to connect to your database. You can use any connection
pooling library such as HikariCP, Apache DBCP, or any other JDBC data source.

!!! warning
    The data source must be set before any database operations can be performed, and it can only be set once.
    To release it, use `closeDataSource()`.

### Setting Up the Data Source

**Using HikariCP**

HikariCP is a high-performance JDBC connection pool:

```java
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import static onl.ycode.stormify.StormifyManager.stormify;

HikariConfig config = new HikariConfig();
config.setJdbcUrl("jdbc:mysql://localhost:3306/yourdb");
config.setUsername("username");
config.setPassword("password");

HikariDataSource dataSource = new HikariDataSource(config);
stormify().setDataSource(dataSource);
```

You can also configure HikariCP from a properties file:

```java
HikariConfig config = new HikariConfig("databaseConfig.properties");
HikariDataSource dataSource = new HikariDataSource(config);
stormify().setDataSource(dataSource);
```

**Using Apache DBCP**

```java
import org.apache.commons.dbcp2.BasicDataSource;
import static onl.ycode.stormify.StormifyManager.stormify;

BasicDataSource dataSource = new BasicDataSource();
dataSource.setUrl("jdbc:mysql://localhost:3306/yourdb");
dataSource.setUsername("username");
dataSource.setPassword("password");

stormify().setDataSource(dataSource);
```

Stormify is compatible with any `javax.sql.DataSource` implementation.

### Configuration

!!! tip
    Naming policies and primary key resolvers can be set at any time before the first query.

```java
stormify().setNamingPolicy(NamingPolicy.camelCase);
stormify().registerPrimaryKeyResolver(10, (table, field) -> field.equals("id"));
stormify().setDataSource(dataSource);
```

### Deferred Initialization

Use `onInit` to register callbacks that run when the data source is first accessed. This is useful when a framework
manages the data source lifecycle and you need to run initialization code at startup:

```java
stormify().onInit(() -> {
    // Runs once, on first getDataSource() call
    System.out.println("Stormify initialized with dialect: " + stormify().getSqlDialect());
});
```

### Closing the Data Source

When the application shuts down, close the data source to release connections:

```java
stormify().closeDataSource();
```

!!! tip
    If the data source implements `Closeable` or `AutoCloseable`, it will be closed automatically.

## Logging Configuration

Stormify uses a built-in logging abstraction that auto-detects the logging framework available at runtime. The
detection order is:

1. **SLF4J** (if a non-NOP implementation is present)
2. **Log4J** (legacy v1)
3. **Log4J2**
4. **Apache Commons Logging**
5. **System.out** (fallback)

By default, Stormify creates a logger named `"Stormify"`. All SQL queries are logged at DEBUG level.

### Enabling SQL Logging

To see the SQL statements Stormify executes, configure your logging framework to enable DEBUG for the `Stormify`
logger.

Example SLF4J configuration in `logback.xml`:

```xml
<configuration>
    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss} %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <logger name="Stormify" level="DEBUG" additivity="false">
        <appender-ref ref="STDOUT" />
    </logger>

    <root level="INFO">
        <appender-ref ref="STDOUT" />
    </root>
</configuration>
```

### Custom Logger

You can replace the default logger with your own:

```java
import onl.ycode.logger.LogManager;

stormify().setLogger(LogManager.getLogger(MyApp.class));
```

Or use a `SilentLogger` to suppress all Stormify logging:

```java
stormify().setLogger(new SilentLogger());
```

Or use a `WatchLogger` to intercept log messages:

```java
stormify().setLogger(new WatchLogger(existingLogger, (level, message, throwable) -> {
    // Custom handling of log messages
}));
```
