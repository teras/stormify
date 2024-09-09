# Database Configuration

Proper configuration of Stormify ensures optimal performance and seamless integration with your application. This section covers the essential configuration steps, including setting up the data source, configuring the environment, and adjusting logging and other settings.

## Data Source Configuration

Stormify relies on a JDBC-compatible data source to connect to your database. You can use popular connection pooling libraries such as HikariCP, Apache DBCP, or any other JDBC data source.

### Setting Up the Data Source

1. **Using HikariCP**

   HikariCP is a high-performance JDBC connection pool. Below is an example of configuring HikariCP as the data source for Stormify.

   ```java
   import com.zaxxer.hikari.HikariConfig;
   import com.zaxxer.hikari.HikariDataSource;
   import static onl.ycode.stormify.StormifyManager.stormify;

   // Configure HikariCP using a properties file
   HikariConfig config = new HikariConfig("databaseConfig.properties");
   HikariDataSource dataSource = new HikariDataSource(config);

   // Set the data source for Stormify
   stormify().setDataSource(dataSource);
   ```

   In this example, replace `"databaseConfig.properties"` with the path to your HikariCP configuration file. You can also configure HikariCP programmatically by setting properties directly on the `HikariConfig` object.

2. **Using Apache DBCP**

   Apache DBCP is another widely-used connection pooling library. Below is an example of configuring Apache DBCP with Stormify.

   ```java
   import org.apache.commons.dbcp2.BasicDataSource;
   import static onl.ycode.stormify.StormifyManager.stormify;

   // Configure Apache DBCP
   BasicDataSource dataSource = new BasicDataSource();
   dataSource.setUrl("jdbc:mysql://localhost:3306/yourdb");
   dataSource.setUsername("username");
   dataSource.setPassword("password");

   // Set the data source for Stormify
   stormify().setDataSource(dataSource);
   ```

### Using Different JDBC Data Sources

Stormify is compatible with any JDBC data source. Simply configure the data source according to your requirements and set it using `stormify().setDataSource(dataSource);`.

## Environment Setup

Stormify can be configured through environment variables, configuration files, or programmatically within your application code.

### Configuration Files

You can store configuration settings in files such as `application.properties` or `application.yml`. Common configuration options include database URL, username, password, and connection pool settings.

Example `application.properties`:

```properties
database.url=jdbc:mysql://localhost:3306/yourdb
database.username=username
database.password=password
database.pool.size=10
```

### Programmatic Configuration

You can also configure Stormify programmatically by setting properties directly in your application code. This approach provides flexibility for dynamic environments.

```java
HikariConfig config = new HikariConfig();
config.setJdbcUrl("jdbc:mysql://localhost:3306/yourdb");
config.setUsername("username");
config.setPassword("password");

HikariDataSource dataSource = new HikariDataSource(config);
stormify().setDataSource(dataSource);
```

## Logging Configuration

Stormify includes logging capabilities to help monitor SQL queries and diagnose issues.

### Enabling SQL Logging

To enable SQL logging, you can adjust the logging settings of your application. Stormify uses the logging framework configured for your application (e.g., SLF4J, Log4j).

Example SLF4J configuration in `logback.xml`:

```xml
<configuration>
    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss} %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <logger name="onl.ycode.stormify" level="DEBUG" additivity="false">
        <appender-ref ref="STDOUT" />
    </logger>

    <root level="INFO">
        <appender-ref ref="STDOUT" />
    </root>
</configuration>
```

This configuration sets Stormify to log SQL statements at the DEBUG level.

### Debugging and Monitoring

Enable DEBUG logging for Stormify to trace query execution and inspect the parameters passed to each query. This can be helpful for troubleshooting and optimizing your database interactions.

## Other Configuration Options

### Adjusting Connection Pooling Settings

Tuning connection pool settings such as the maximum pool size, idle connections, and connection timeout can greatly affect the performance of your application.

Example HikariCP tuning:

```java
HikariConfig config = new HikariConfig();
config.setMaximumPoolSize(20);
config.setIdleTimeout(30000); // 30 seconds
config.setConnectionTimeout(10000); // 10 seconds
```

### Performance Tuning Tips

- **Optimize SQL Queries**: Ensure your queries are efficient and indexed properly.
- **Adjust Pool Sizes**: Balance pool sizes to match your application's workload and database capacity.
- **Monitor Connection Usage**: Use monitoring tools to keep an eye on connection usage and database performance.

Proper configuration of Stormify will help ensure that your application performs optimally and integrates smoothly with your database environment.
