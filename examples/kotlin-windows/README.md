# Stormify Kotlin/Native Windows Demo

A self-contained Kotlin/Native application demonstrating Stormify ORM on Windows without a JVM.

## Highlights

This example runs as a **native Windows binary** (`mingwX64`) — no JVM required at runtime. It uses Stormify's native SQLite driver via KDBC. Entity metadata is generated at compile time by the KSP annotation processor (`annproc`), which is required on native platforms.

The `Task` entity extends `AutoTable` with `by db()` delegates for automatic lazy-loading. The `User` entity is a plain class — references to it must be populated explicitly via `stormify.populate()`.

## What it demonstrates

- **Native binary** — compiles to a standalone Windows executable
- **KSP annotation processor** — generates entity metadata at compile time
- **AutoTable vs plain class** — `Task` auto-populates references, `User` requires explicit `populate()`
- **CRUD operations** — create, findById, findAll, update, delete
- **Transaction DSL** with automatic rollback on exception
- **Raw SQL JOIN query** returning `Map<String, Any?>` results

## Requirements

The native SQLite driver loads `sqlite3.dll` at runtime. Download the 64-bit DLL
from [sqlite.org](https://www.sqlite.org/download.html) (*Precompiled Binaries for
Windows*) and place it next to the executable or on `PATH`.

## Build & Run

Release build (optimized, stripped):

```bash
gradle linkReleaseExecutableMingwX64
cp /path/to/sqlite3.dll build/bin/mingwX64/releaseExecutable/
cd build/bin/mingwX64/releaseExecutable
wine stormify-kotlin-windows-demo.exe   # or run natively on Windows
```

The `.exe` lives at `build/bin/mingwX64/releaseExecutable/stormify-kotlin-windows-demo.exe`.
