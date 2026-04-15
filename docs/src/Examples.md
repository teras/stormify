# Examples

Ready-to-run sample projects for Stormify live in a dedicated repository:
[stormify-examples](https://github.com/teras/stormify-examples).

They are also wired into the main Stormify repository as a git submodule under `examples/`, so you can either clone them standalone or pull them alongside the library sources.

## Getting the examples

Standalone clone:

```bash
git clone -b 2.1.0 https://github.com/teras/stormify-examples.git
cd stormify-examples
```

Or as a submodule inside the main Stormify checkout:

```bash
git clone https://github.com/teras/stormify.git
cd stormify
git submodule update --init --recursive
cd examples
```

Every example targets Stormify `2.1.0` and uses SQLite as the default database (no server required). Each subfolder is a self-contained Gradle/Maven project with its own `README.md` detailing entities, setup, and any platform-specific notes.

## Available examples

### kotlin-jvm

Minimal Kotlin JVM application using SQLite over JDBC. Demonstrates CRUD, transactions, and lazy-loaded references via `AutoTable` with `by db()` delegates.

Run:

```bash
cd kotlin-jvm
gradle run
```

### java

Same scenario as `kotlin-jvm`, written in Java. Showcases mixing **JPA annotations** (`@Id`, `@GeneratedValue`) on one entity and **Stormify annotations** (`@DbTable`, `@DbField`) on another in the same project. Uses explicit `populate()` calls for lazy references.

Run:

```bash
cd java
mvn compile exec:java
```

### android

Compose-based Android app using the platform's built-in SQLite through the `Stormify(SQLiteDatabase)` convenience constructor. Demonstrates KSP-generated `TableInfo` (mandatory on Android), CRUD, transactions with rollback, and `AutoTable` lazy references. Min SDK 21.

Run: open the `android/` folder in Android Studio and launch on a device or emulator. Or from the command line:

```bash
cd android
gradle :app:installDebug
```

### ios

SwiftUI app backed by a Kotlin Multiplatform shared module. The Kotlin side owns entities and demo logic; Swift calls into the generated framework. Uses platform SQLite via `KdbcDataSource` with Kotlin/Native cinterop. Requires macOS and Xcode.

Run: open `ios/iosApp/iosApp.xcodeproj` in Xcode and run on a simulator or device.

### kotlin-linux

Standalone Kotlin/Native Linux binary (`linuxX64`) using Stormify's native SQLite driver via KDBC. No JVM required at runtime. Entity metadata is generated at compile time by the KSP annotation processor.

Run:

```bash
cd kotlin-linux
gradle runDebugExecutableLinuxX64
```

### kotlin-macos

Standalone Kotlin/Native macOS binary for both `macosArm64` (Apple Silicon) and `macosX64` (Intel), sharing code through the `macosMain` source set. Uses native SQLite via KDBC. Requires macOS to build.

Run:

```bash
cd kotlin-macos
gradle runDebugExecutableMacosArm64   # or Macos X64
```

### kotlin-windows

Standalone Kotlin/Native Windows binary (`mingwX64`) using the native SQLite driver via KDBC. No JVM required at runtime.

Run (on Windows, or under Wine):

```bash
cd kotlin-windows
gradle runDebugExecutableMingwX64
```

### kotlin-multiplatform

The most comprehensive example: the **same business logic** in `commonMain` runs on JVM, Linux (`linuxX64`, `linuxArm64`), Windows (`mingwX64`), and macOS (`macosArm64`, `macosX64`). Only the DataSource creation is platform-specific — JVM uses SQLite via JDBC, native targets use `KdbcDataSource`. Both entities extend `AutoTable` with `by db()` delegates.

Run on any available target:

```bash
cd kotlin-multiplatform
gradle run                                # JVM
gradle runDebugExecutableLinuxX64         # Linux x64
gradle runDebugExecutableMacosArm64       # macOS Apple Silicon
gradle runDebugExecutableMingwX64         # Windows
```

## What's next

Each example's `README.md` in the submodule goes deeper into entity design, platform notes, and build requirements (e.g. native toolchains, Xcode, Android SDK). Start with `kotlin-jvm` or `java` if you want the shortest path to a working Stormify app; move to `kotlin-multiplatform` once you want to share logic across targets.
