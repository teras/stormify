# Database Configuration

Proper configuration of Stormify ensures optimal performance and seamless integration with your application. This section covers the essential configuration steps, including setting up the data source, configuring the environment, and adjusting logging and other settings.

## Data Source Configuration

Stormify connects to your database through a data source. On JVM, it accepts any JDBC-compatible data source. On native, it uses KDBC with JDBC-style connection URLs.

### Setting Up the Data Source

=== "Kotlin"

    **Using HikariCP:**

    ```kotlin
    import com.zaxxer.hikari.HikariConfig
    import com.zaxxer.hikari.HikariDataSource
    import onl.ycode.stormify.Stormify

    val config = HikariConfig("databaseConfig.properties")
    val dataSource = HikariDataSource(config)
    val stormify = Stormify(dataSource)
    ```

    **Using Apache DBCP:**

    ```kotlin
    import org.apache.commons.dbcp2.BasicDataSource
    import onl.ycode.stormify.Stormify

    val dataSource = BasicDataSource().apply {
        url = "jdbc:mysql://localhost:3306/yourdb"
        username = "username"
        password = "password"
    }
    val stormify = Stormify(dataSource)
    ```

=== "Java"

    **Using HikariCP:**

    ```java
    import com.zaxxer.hikari.HikariConfig;
    import com.zaxxer.hikari.HikariDataSource;
    import onl.ycode.stormify.StormifyJ;

    HikariConfig config = new HikariConfig("databaseConfig.properties");
    HikariDataSource dataSource = new HikariDataSource(config);
    StormifyJ stormify = new StormifyJ(dataSource);
    ```

    **Using Apache DBCP:**

    ```java
    import org.apache.commons.dbcp2.BasicDataSource;
    import onl.ycode.stormify.StormifyJ;

    BasicDataSource dataSource = new BasicDataSource();
    dataSource.setUrl("jdbc:mysql://localhost:3306/yourdb");
    dataSource.setUsername("username");
    dataSource.setPassword("password");
    StormifyJ stormify = new StormifyJ(dataSource);
    ```

=== "Native"

    On native, use `KdbcDataSource` with standard JDBC URLs:

    ```kotlin
    import onl.ycode.stormify.Stormify
    import onl.ycode.kdbc.KdbcDataSource

    // PostgreSQL
    val ds = KdbcDataSource("jdbc:postgresql://localhost:5432/mydb", "user", "pass")
    val stormify = Stormify(ds)

    // MariaDB / MySQL
    val ds = KdbcDataSource("jdbc:mariadb://localhost:3306/mydb", "user", "pass")
    val stormify = Stormify(ds)

    // SQLite
    val ds = KdbcDataSource("jdbc:sqlite:/tmp/mydb.db")
    val stormify = Stormify(ds)

    // Oracle
    val ds = KdbcDataSource("jdbc:oracle:thin:@localhost:1521/XEPDB1", "user", "pass")
    val stormify = Stormify(ds)

    // MS SQL Server
    val ds = KdbcDataSource("jdbc:sqlserver://localhost:1433;databaseName=mydb", "sa", "pass")
    val stormify = Stormify(ds)
    ```

    Database client libraries are loaded at runtime via `dlopen` — only the ones you
    actually use need to be installed.

### Using Different Data Sources

On JVM, Stormify is compatible with any JDBC data source. Simply configure the data source according to your requirements and create a `Stormify` (Kotlin) or `StormifyJ` (Java) instance with it.

## Environment Setup

Stormify can be configured through configuration files or programmatically within your application code.

### Configuration Files

You can store configuration settings in files such as `application.properties`. Common configuration options include database URL, username, password, and connection pool settings.

```properties
database.url=jdbc:mysql://localhost:3306/yourdb
database.username=username
database.password=password
database.pool.size=10
```

### Programmatic Configuration

=== "Kotlin"

    ```kotlin
    val config = HikariConfig().apply {
        jdbcUrl = "jdbc:mysql://localhost:3306/yourdb"
        username = "username"
        password = "password"
    }
    val dataSource = HikariDataSource(config)
    val stormify = Stormify(dataSource)
    ```

=== "Java"

    ```java
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl("jdbc:mysql://localhost:3306/yourdb");
    config.setUsername("username");
    config.setPassword("password");
    HikariDataSource dataSource = new HikariDataSource(config);
    StormifyJ stormify = new StormifyJ(dataSource);
    ```

## Logging Configuration

Stormify includes logging capabilities to help monitor SQL queries and diagnose issues.

### Enabling SQL Logging

Stormify uses the logging framework configured for your application (SLF4J, Log4j, Log4j2, Commons Logging). The framework is auto-detected at runtime.

Example Logback configuration in `logback.xml`:

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

This configuration sets Stormify to log SQL statements at the DEBUG level.

### Custom Logger

You can replace the default logger:

=== "Kotlin"

    ```kotlin
    // Suppress all logging
    stormify.logger = SilentLogger()

    // Intercept log messages
    stormify.logger = WatchLogger(existingLogger) { level, message, throwable ->
        // Custom handling
    }
    ```

=== "Java"

    ```java
    // Suppress all logging
    stormify.setLogger(new SilentLogger());

    // Intercept log messages
    stormify.setLogger(new WatchLogger(existingLogger, (level, message, throwable) -> {
        // Custom handling
    }));
    ```

## Other Configuration Options

### Adjusting Connection Pooling Settings

Tuning connection pool settings such as the maximum pool size, idle connections, and connection timeout can greatly affect the performance of your application.

=== "Kotlin"

    ```kotlin
    val config = HikariConfig().apply {
        maximumPoolSize = 20
        idleTimeout = 30000 // 30 seconds
        connectionTimeout = 10000 // 10 seconds
    }
    ```

=== "Java"

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

## Application Server Deployment (JVM only)

`StormifyLifecycle` is a **JVM-only** cleanup facade. When Stormify is used inside a Jakarta EE / Java EE webapp (Payara, WildFly, Tomcat, GlassFish, Jetty), **where** you place `stormify-jvm.jar` on the classpath determines whether you need any shutdown cleanup.

### Recommended: package inside the WAR

Put `stormify-jvm.jar` in your application's `WEB-INF/lib/`. This is the default for any build that declares Stormify as a Maven / Gradle `implementation` dependency — the JAR ends up in the packaged WAR automatically. In this topology:

- Stormify's internal singletons (entity metadata registry, enum registry, default instance) live **inside** the webapp ClassLoader, next to your entity classes.
- On webapp undeploy, the entire webapp ClassLoader is garbage-collected together with everything it holds — registries, entity metadata, cached lambdas, all of it.
- **No cleanup hook is required.** You can skip the rest of this section.

### Shared classpath: call `StormifyLifecycle.clear()` on undeploy

If — and only if — you install `stormify-jvm.jar` into a shared server path such as:

- Payara / GlassFish: `${domain}/lib/`
- Tomcat: `$CATALINA_HOME/lib/`
- WildFly / JBoss: a shared module

…then the library's static singletons live in the **server's common ClassLoader**, but they will be populated with `KClass` keys and lambdas that come from the **webapp's ClassLoader**. Without explicit cleanup, those references keep the webapp ClassLoader alive across redeploys, causing a metaspace leak.

Register a single lifecycle listener that calls `StormifyLifecycle.clear()`:

=== "Jakarta EE"

    ```java
    import jakarta.servlet.ServletContextEvent;
    import jakarta.servlet.ServletContextListener;
    import jakarta.servlet.annotation.WebListener;
    import onl.ycode.stormify.StormifyLifecycle;

    @WebListener
    public class StormifyShutdownListener implements ServletContextListener {
        @Override
        public void contextDestroyed(ServletContextEvent sce) {
            StormifyLifecycle.clear();
        }
    }
    ```

    The `@WebListener` annotation is picked up automatically by the container — no entry in `web.xml` is needed.

=== "CDI"

    ```java
    import jakarta.annotation.PreDestroy;
    import jakarta.enterprise.context.ApplicationScoped;
    import onl.ycode.stormify.StormifyLifecycle;

    @ApplicationScoped
    public class StormifyCleanup {
        @PreDestroy
        public void onShutdown() {
            StormifyLifecycle.clear();
        }
    }
    ```

=== "Spring Boot WAR"

    ```java
    import jakarta.annotation.PreDestroy;
    import org.springframework.stereotype.Component;
    import onl.ycode.stormify.StormifyLifecycle;

    @Component
    public class StormifyCleanup {
        @PreDestroy
        public void onShutdown() {
            StormifyLifecycle.clear();
        }
    }
    ```

    Only needed when the Spring Boot application is deployed **as a WAR** to an external server. Fat-jar Spring Boot apps don't have a redeploy lifecycle and don't need this.

After `clear()` returns, Stormify is back in its initial state. If the same JVM hosts a new webapp deployment immediately afterwards, the next call to `new Stormify(...).asDefault()` re-initialises everything normally — the annproc-generated entity registrations are reapplied when the new webapp's classes are first touched.
