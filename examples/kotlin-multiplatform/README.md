# Stormify Kotlin Multiplatform Demo

A self-contained Kotlin Multiplatform application demonstrating Stormify ORM running the **same business logic** on JVM and every supported native target.

## Highlights

Entity classes and all demo logic live in `commonMain`, while only the DataSource creation is platform-specific:

- **JVM** uses SQLite via JDBC (`org.xerial:sqlite-jdbc`)
- **Linux native** (`linuxX64`) uses SQLite via KDBC's native driver (`KdbcDataSource`)
- **Windows native** (`mingwX64`) uses the same KDBC driver
- **macOS native** (`macosArm64`) uses the same KDBC driver

The **KSP annotation processor** generates entity metadata at compile time, which is required for native and works on JVM as well. Both entities extend `AutoTable` with `by db()` delegates for transparent lazy-loading of references.

## What it demonstrates

- **Shared code** — entities and business logic in `commonMain`
- **Platform-specific DataSource** — only the entry point differs per platform
- **KSP annotation processor** — generates `TableInfo` for both targets
- **Entity references** with lazy loading (`AutoTable` + `by db()`)
- **CRUD operations** — create, findById, findAll, update, delete
- **Transaction DSL** with automatic rollback on exception
- **Raw SQL JOIN query** returning `Map<String, Any?>` results

## Requirements

Native targets load the platform's SQLite library at runtime:

- **Linux** — install `libsqlite3-0` (Debian/Ubuntu: `sudo apt install libsqlite3-0`; Arch/Manjaro: `sudo pacman -S sqlite`).
- **Windows** — download `sqlite3.dll` (64-bit) from [sqlite.org](https://www.sqlite.org/download.html) and place it next to the `.exe` or on `PATH`.
- **macOS** — `libsqlite3.dylib` is bundled with the OS; nothing to install.
- **JVM** — the SQLite JDBC driver is included as a Maven dependency; no system library needed.

## Run on JVM

```bash
gradle jvmRun
```

## Linux native (release)

```bash
gradle linkReleaseExecutableLinuxX64
./build/bin/linuxX64/releaseExecutable/stormify-kotlin-multiplatform-demo.kexe
```

Or build + run in one step (debug): `gradle runDebugExecutableLinuxX64`.

## Windows native (release)

```bash
gradle linkReleaseExecutableMingwX64
cp /path/to/sqlite3.dll build/bin/mingwX64/releaseExecutable/
cd build/bin/mingwX64/releaseExecutable
wine stormify-kotlin-multiplatform-demo.exe   # or run natively on Windows
```

## macOS native (release)

```bash
gradle linkReleaseExecutableMacosArm64
./build/bin/macosArm64/releaseExecutable/stormify-kotlin-multiplatform-demo.kexe
```

Or build + run in one step (debug): `gradle runDebugExecutableMacosArm64`.
