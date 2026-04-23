# Installation

## Requirements

- **JVM**: Java 8 or later.
- **Android**: minimum API 21 (Android 5.0), compile SDK 34.
- **Linux native**: glibc 2.31+ (x86_64 or ARM64).
- **Windows native**: Windows 10 or later (mingwX64 build).
- **macOS native**: macOS 11+ on Apple Silicon or Intel.
- **iOS native**: iOS 14+ (device, simulator Intel, simulator Apple Silicon).
- **Kotlin**: 2.2.20 for consumers that compile against Stormify APIs.

## Artifacts

All artifacts are published to Maven Central under the `onl.ycode` group.

=== "Gradle (JVM)"

    ```kotlin
    implementation("onl.ycode:stormify-jvm:2.2.0-SNAPSHOT")
    ksp("onl.ycode:annproc:2.2.0-SNAPSHOT")              // optional on JVM
    ```

=== "Gradle (Android)"

    ```kotlin
    implementation("onl.ycode:stormify-android:2.2.0-SNAPSHOT")
    ksp("onl.ycode:annproc:2.2.0-SNAPSHOT")              // required on Android
    ```

=== "Gradle (Native)"

    Pick the artifact matching your target platform:

    ```kotlin
    implementation("onl.ycode:stormify-linuxx64:2.2.0-SNAPSHOT")          // Linux x64
    implementation("onl.ycode:stormify-linuxarm64:2.2.0-SNAPSHOT")        // Linux ARM64
    implementation("onl.ycode:stormify-mingwx64:2.2.0-SNAPSHOT")          // Windows x64
    implementation("onl.ycode:stormify-macosarm64:2.2.0-SNAPSHOT")        // macOS (Apple Silicon)
    implementation("onl.ycode:stormify-macosx64:2.2.0-SNAPSHOT")          // macOS (Intel)
    implementation("onl.ycode:stormify-iosarm64:2.2.0-SNAPSHOT")          // iOS (device)
    implementation("onl.ycode:stormify-iossimulatorarm64:2.2.0-SNAPSHOT") // iOS simulator (Apple Silicon)
    implementation("onl.ycode:stormify-iosx64:2.2.0-SNAPSHOT")            // iOS simulator (Intel Mac)

    ksp("onl.ycode:annproc:2.2.0-SNAPSHOT")                               // required (no reflection on native)
    ```

=== "Maven"

    ```xml
    <dependency>
        <groupId>onl.ycode</groupId>
        <artifactId>stormify-jvm</artifactId>
        <version>2.2.0-SNAPSHOT</version>
    </dependency>
    ```

Supported native databases: **PostgreSQL, MariaDB/MySQL, Oracle, MSSQL, SQLite**.
On iOS, only SQLite is available (App Store restrictions).

## Entity Metadata: `annproc` and `GeneratedEntities`

!!! tip "On JVM? You can skip the rest of this page."

    `stormify-jvm` ships with `kotlin-reflect`, which discovers entity metadata
    at runtime — no annotations required on your classes. Everything below is
    only relevant on Native/Android/iOS, or on JVM when you want faster startup
    and a leaner classpath (see
    [Excluding `kotlin-reflect` (JVM)](#excluding-kotlin-reflect-jvm)).

Stormify needs entity metadata (field names, types, primary keys) to perform
ORM operations. On Native/Android/iOS there is no runtime reflection, so the
metadata has to be generated at compile time by the `annproc` KSP processor —
it scans `@DbTable` and JPA `@Entity` annotations and emits the registrar
object that Stormify picks up at startup.

### KSP Setup

Add the KSP plugin and the `annproc` dependency:

=== "Gradle (Kotlin)"

    ```kotlin
    plugins {
        id("com.google.devtools.ksp") version "2.2.20-2.0.2"
    }

    dependencies {
        ksp("onl.ycode:annproc:2.2.0-SNAPSHOT")
    }
    ```

=== "Gradle (Groovy)"

    ```groovy
    plugins {
        id 'com.google.devtools.ksp' version '2.2.20-2.0.2'
    }

    dependencies {
        ksp 'onl.ycode:annproc:2.2.0-SNAPSHOT'
    }
    ```

KSP requires the Kotlin compiler, so pure Java/Maven projects without a Kotlin
compilation step cannot use `annproc` — they rely on `kotlin-reflect` instead.

### Wiring the generated registrar

The processor emits an `EntityRegistrar` object in the
`onl.ycode.stormify.generated` package. Pass it to the `Stormify` constructor:

```kotlin
import onl.ycode.stormify.generated.GeneratedEntities

val stormify = Stormify(dataSource, GeneratedEntities)
```

The generated package and class names (`GeneratedEntities`, `Paths`) are
overridable via KSP options — see
[Annotations › Annotation Processor](Annotations.md#annotation-processor-annproc)
for details and advanced usage.

### Excluding `kotlin-reflect` (JVM)

When using `annproc` on JVM, `kotlin-reflect` is no longer needed at runtime and
can be excluded:

=== "Gradle (Kotlin)"

    ```kotlin
    implementation("onl.ycode:stormify-jvm:2.2.0-SNAPSHOT") {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-reflect")
    }
    ```

=== "Gradle (Groovy)"

    ```groovy
    implementation('onl.ycode:stormify-jvm:2.2.0-SNAPSHOT') {
        exclude group: 'org.jetbrains.kotlin', module: 'kotlin-reflect'
    }
    ```

## Native Runtime Libraries

On native targets (Linux x64, Linux ARM64, Windows x64, macOS Apple Silicon, macOS
Intel), Stormify loads database client libraries dynamically at runtime, and only
when a database is actually used. Install the libraries for the databases you
need. If the client library for a database you use is missing, Stormify reports
"driver not available" for that database — other drivers continue to work, and
databases you never touch are never probed.

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

### MS SQL Server / Sybase ASE (via FreeTDS)

FreeTDS implements the TDS protocol used by both Microsoft SQL Server and SAP
(formerly Sybase) Adaptive Server Enterprise — the same `kdbc-freetds` driver
connects to either.

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

