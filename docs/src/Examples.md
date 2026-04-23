# Examples

Ready-to-run sample projects for Stormify live in a dedicated repository:
[stormify-examples](https://github.com/teras/stormify-examples).

They are also wired into the main Stormify repository as a git submodule under `examples/`, so you can either clone them standalone or pull them alongside the library sources.

## Getting the examples

Clone the examples repository and step into the folder you are interested in:

```bash
git clone -b 2.1.2-SNAPSHOT https://github.com/teras/stormify-examples.git
cd stormify-examples/<example-folder>
```

Or pull them as a submodule inside the main Stormify checkout:

```bash
git clone https://github.com/teras/stormify.git
cd stormify
git submodule update --init --recursive
cd examples
```

Every example targets Stormify `2.1.2-SNAPSHOT` and uses SQLite as the default database (no server required), with the exception of the REST/frontend combo which can point at any configured backend. Each subfolder is a self-contained Gradle/Maven/npm project with its own `README.md` detailing entities, setup, and any platform-specific notes.

## Available examples

### [kotlin-jvm](https://github.com/teras/stormify-examples/tree/main/kotlin-jvm)

The shortest path to a working Stormify app on the JVM. A single Kotlin file wires SQLite via JDBC, defines two entities that extend `AutoTable` with `by db()` and `by lazyDetails()` delegates, and runs a full CRUD scenario (create/find/update/delete) plus a transaction with rollback and a raw SQL JOIN query. Good starting point if you want to see idiomatic Kotlin Stormify code with no platform ceremony.

Run:

```bash
cd kotlin-jvm
gradle run
```

### [java](https://github.com/teras/stormify-examples/tree/main/java)

The same CRUD/transactions scenario as `kotlin-jvm`, written in plain Java and built with Maven. Its distinctive feature is **mix-and-match annotations**: one entity uses standard JPA (`@Id`, `@GeneratedValue`) while another uses Stormify's own (`@DbTable`, `@DbField`) — both interoperate in the same project. Lazy references are resolved via explicit `populate()` calls (instead of Kotlin's delegate syntax), which makes the underlying mechanism more visible.

Run:

```bash
cd java
mvn compile exec:java
```

### [android](https://github.com/teras/stormify-examples/tree/main/android)

A minimal Compose app (two entities, one ViewModel, one screen — readable in five minutes) that uses Android's built-in SQLite through the `Stormify(SQLiteDatabase)` convenience constructor. It demonstrates the KSP-generated `TableInfo` (mandatory on Android, since full reflection isn't available), CRUD operations, a transaction with rollback, lazy references via `AutoTable`, and enum-as-int storage via `DbValue` so enum order changes don't corrupt data. Min SDK 21.

Run: open the `android/` folder in Android Studio, or from the command line:

```bash
cd android
gradle :app:installDebug
```

### [ios](https://github.com/teras/stormify-examples/tree/main/ios)

A SwiftUI app backed by a Kotlin Multiplatform shared module. The Kotlin side owns entities and demo logic; Swift calls into the generated framework. Uses platform SQLite via `KdbcDataSource` with Kotlin/Native cinterop, and KSP for entity metadata (required on native). A practical template if you want to share persistence logic with an existing Kotlin codebase while keeping a native SwiftUI frontend. Requires macOS and Xcode.

Run: open `ios/iosApp/iosApp.xcodeproj` in Xcode and launch on a simulator or device.

### [kotlin-linux](https://github.com/teras/stormify-examples/tree/main/kotlin-linux)

A standalone Kotlin/Native Linux binary (`linuxX64`) — no JVM needed at runtime — using Stormify's native SQLite driver via KDBC. Entity metadata is generated at compile time by the KSP annotation processor. The sample intentionally uses both an `AutoTable` entity (with `by db()` auto-population) and a plain-class entity (requiring explicit `populate()`), so you can see both styles side by side.

Run:

```bash
cd kotlin-linux
gradle runDebugExecutableLinuxX64
```

### [kotlin-macos](https://github.com/teras/stormify-examples/tree/main/kotlin-macos)

Same shape as `kotlin-linux`, but a standalone Kotlin/Native macOS binary for both `macosArm64` (Apple Silicon) and `macosX64` (Intel), sharing code through the `macosMain` intermediate source set. Uses native SQLite via KDBC. Build requires macOS.

Run:

```bash
cd kotlin-macos
gradle runDebugExecutableMacosArm64   # or MacosX64
```

### [kotlin-windows](https://github.com/teras/stormify-examples/tree/main/kotlin-windows)

Same shape again, this time a standalone Kotlin/Native Windows binary (`mingwX64`) using the native SQLite driver via KDBC. No JVM required at runtime. Builds on Linux with a MinGW toolchain; can be run natively on Windows or under Wine.

Run (on Windows, or under Wine):

```bash
cd kotlin-windows
gradle runDebugExecutableMingwX64
```

### [kotlin-multiplatform](https://github.com/teras/stormify-examples/tree/main/kotlin-multiplatform)

The most comprehensive example: the **same business logic** in `commonMain` runs on JVM, Linux (`linuxX64`, `linuxArm64`), Windows (`mingwX64`), and macOS (`macosArm64`, `macosX64`). Only the DataSource creation is platform-specific — JVM uses SQLite via JDBC, native targets use `KdbcDataSource`. Both entities extend `AutoTable` with `by db()` and `by lazyDetails()` delegates. The one to study once you want to share persistence code across every target Stormify supports.

Run on any available target:

```bash
cd kotlin-multiplatform
gradle run                                # JVM
gradle runDebugExecutableLinuxX64         # Linux x64
gradle runDebugExecutableMacosArm64       # macOS Apple Silicon
gradle runDebugExecutableMingwX64         # Windows
```

### [kotlin-rest](https://github.com/teras/stormify-examples/tree/main/kotlin-rest)

A Ktor REST server backed by Stormify over SQLite. It shows how Stormify fits into a realistic web-service layout — entity model, DTOs, service layer, routes, seed data — and exposes `POST /search` endpoints that take Stormify `PageSpec` payloads for paged/filtered/sorted queries. This is the one to look at when you want to see Stormify beyond a toy `main()` and inside the kind of structure a production service would have.

The companion [`frontend-react`](https://github.com/teras/stormify-examples/tree/main/frontend-react) folder contains a React + TypeScript admin UI (Vite, MUI Data Grid, TanStack Query, React Router) that consumes this backend through the same `PageSpec`/`PagedResponse` contract — useful if you want to see what a real client for these paged endpoints looks like.

Run the backend:

```bash
cd kotlin-rest
gradle run
```

Then, in a separate terminal, run the frontend pointing at it:

```bash
cd frontend-react
npm install
npm run dev
```
