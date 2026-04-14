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

    Database client libraries are loaded at runtime via `dlopen` / `LoadLibrary` —
    only the ones you actually use need to be installed. See the
    [Native Runtime Libraries](#native-runtime-libraries) section below for
    per-database installation instructions.

=== "Android"

    On Android, wrap the platform `SQLiteDatabase`:

    ```kotlin
    import android.content.Context
    import onl.ycode.stormify.Stormify

    val db = context.openOrCreateDatabase("mydb.db", Context.MODE_PRIVATE, null)
    val stormify = Stormify(db)
    ```

    The `Stormify(SQLiteDatabase)` convenience function automatically wraps the
    database in an `AndroidDataSource`. Foreign keys are off by default in Android
    SQLite — enable them with `db.execSQL("PRAGMA foreign_keys = ON")` before
    creating the Stormify instance.

### Default Instance

Several Stormify APIs — the **CRUDTable** and **Extension** styles for CRUD
operations, [AutoTable lazy stubs](References.md#fresh-construction-vs-lazy-stubs),
and [`PagedList`](PagedList.md#quick-start) — operate without receiving an explicit
`Stormify` reference on every call. They look up a **default instance** that you
register once during application startup:

=== "Kotlin"

    ```kotlin
    val stormify = Stormify(dataSource)
    stormify.asDefault()
    ```

=== "Java"

    ```java
    StormifyJ stormify = new StormifyJ(dataSource);
    stormify.asDefault();
    ```

After `asDefault()`, any code that needs an implicit `Stormify` (without an
attached instance of its own) picks up this one. Construct as many `Stormify`
instances as you need for explicit use; only the most recent `asDefault()` call
wins for the library-wide default.

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

## Native Runtime Libraries

On native targets (Linux x64, Linux ARM64, Windows x64, macOS Apple Silicon, macOS
Intel), Stormify loads database client libraries dynamically at runtime. Only install
the libraries for the databases you actually use. If a library is missing, Stormify
reports "driver not available" for that database — other drivers continue to work
normally.

**iOS** is SQLite-only and uses the platform's built-in `libsqlite3` — nothing to
install. The rest of this section does not apply to iOS.

### SQLite

No external library needed on most systems — SQLite is typically bundled with the OS.

=== "Linux"

    ```bash
    # Debian / Ubuntu
    sudo apt install libsqlite3-0

    # Arch Linux / Manjaro
    sudo pacman -S sqlite
    ```

=== "Windows"

    Download `sqlite3.dll` from [sqlite.org](https://www.sqlite.org/download.html)
    (Precompiled Binaries for Windows, 64-bit DLL) and place it next to your
    application or on `PATH`.

=== "macOS"

    SQLite ships with macOS — no installation needed.

### PostgreSQL

=== "Linux"

    ```bash
    # Debian / Ubuntu
    sudo apt install libpq5

    # Arch Linux / Manjaro
    sudo pacman -S postgresql-libs
    ```

=== "Windows"

    Download the [PostgreSQL binaries zip](https://www.postgresql.org/download/windows/)
    from EDB and extract `libpq.dll` (and its dependencies `libssl-3-x64.dll`,
    `libcrypto-3-x64.dll`, `libintl-9.dll`, `libiconv-2.dll`) from the `pgsql/bin/`
    directory. Place them next to your application or on `PATH`.

=== "macOS"

    ```bash
    brew install libpq
    ```

    Homebrew does not symlink `libpq` into the default library path by default.
    Either run `brew link --force libpq` or add its lib directory to
    `DYLD_LIBRARY_PATH` at runtime.

### MariaDB / MySQL

The MariaDB Connector/C client library works with both MariaDB and MySQL servers.

=== "Linux"

    ```bash
    # Debian / Ubuntu
    sudo apt install libmariadb3

    # Arch Linux / Manjaro
    sudo pacman -S mariadb-libs
    ```

=== "Windows"

    Extract `libmariadb.dll` from the
    [MariaDB Server zip](https://mariadb.org/download/) (`lib/` directory)
    or install from the
    [MariaDB Connector/C MSI](https://mariadb.com/downloads/connectors/).
    Place next to your application or on `PATH`.

    For MySQL 8+ servers, you also need the `caching_sha2_password.dll`
    authentication plugin (included in the Connector/C MSI) in the same
    directory as `libmariadb.dll`.

=== "macOS"

    ```bash
    brew install mariadb-connector-c
    ```

### MS SQL Server (via FreeTDS)

=== "Linux"

    ```bash
    # Debian / Ubuntu
    sudo apt install libsybdb5

    # Arch Linux / Manjaro
    sudo pacman -S freetds
    ```

=== "Windows"

    FreeTDS does not provide official Windows builds. Install via
    [MSYS2](https://www.msys2.org/):

    ```bash
    pacman -S mingw-w64-x86_64-freetds
    ```

    Copy `libsybdb-5.dll` from `mingw64/bin/` next to your application.

=== "macOS"

    ```bash
    brew install freetds
    ```

### Oracle

Oracle support requires two components:

1. **ODPI-C** (Oracle Database Programming Interface for C) — a thin open-source
   C wrapper (Apache 2.0 / UPL 1.0 license).
2. **Oracle Instant Client** — Oracle's proprietary client library (free download,
   no redistribution).

=== "Linux"

    ```bash
    # 1. Build and install ODPI-C from source
    git clone --depth 1 --branch v5.6.4 https://github.com/oracle/odpi.git
    cd odpi && make -j4 && sudo make install PREFIX=/usr/local && cd .. && rm -rf odpi

    # 2. Download Oracle Instant Client basic-lite
    #    https://www.oracle.com/database/technologies/instant-client/linux-x86-64-downloads.html
    wget https://download.oracle.com/otn_software/linux/instantclient/instantclient-basiclite-linuxx64.zip
    sudo unzip instantclient-basiclite-linuxx64.zip -d /opt/oracle
    echo /opt/oracle/instantclient_* | sudo tee /etc/ld.so.conf.d/oracle.conf
    sudo ldconfig
    ```

=== "Windows"

    ```bash
    # 1. Build ODPI-C from source (requires MinGW or Visual Studio)
    git clone --depth 1 --branch v5.6.4 https://github.com/oracle/odpi.git
    cd odpi
    gcc -shared -o odpic.dll src/*.c -Iinclude -DDPI_DLL_EXPORT -O2
    # Place odpic.dll next to your application

    # 2. Download Oracle Instant Client basic-lite for Windows x64
    #    https://www.oracle.com/database/technologies/instant-client/winx64-64-downloads.html
    # Unzip and place oci.dll (and supporting ora*.dll files) next to your application or on PATH
    ```

=== "macOS"

    ```bash
    # 1. Build and install ODPI-C from source
    git clone --depth 1 --branch v5.6.4 https://github.com/oracle/odpi.git
    cd odpi && make -j4 && sudo make install PREFIX=/usr/local && cd .. && rm -rf odpi

    # 2. Download Oracle Instant Client basic-lite for macOS
    #    https://www.oracle.com/database/technologies/instant-client/macos-arm64-downloads.html  (Apple Silicon)
    #    https://www.oracle.com/database/technologies/instant-client/macos-intel-x86-downloads.html  (Intel)
    # Unzip somewhere (e.g. /opt/oracle/instantclient_*) and add that directory to
    # DYLD_LIBRARY_PATH at runtime.
    ```

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
