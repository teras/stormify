# Stormify Kotlin/Native Linux Demo

A self-contained Kotlin/Native application demonstrating Stormify ORM on Linux without a JVM.

## Highlights

This example runs as a **native Linux binary** — no JVM required at runtime. It uses Stormify's native SQLite driver via KDBC. Entity metadata is generated at compile time by the KSP annotation processor (`annproc`), which is required on native platforms.

The `Task` entity extends `AutoTable` with `by db()` delegates for automatic lazy-loading. The `User` entity is a plain class — references to it must be populated explicitly via `stormify.populate()`.

## What it demonstrates

- **Native binary** — compiles to a standalone Linux executable
- **KSP annotation processor** — generates entity metadata at compile time
- **AutoTable vs plain class** — `Task` auto-populates references, `User` requires explicit `populate()`
- **CRUD operations** — create, findById, findAll, update, delete
- **Transaction DSL** with automatic rollback on exception
- **Raw SQL JOIN query** returning `Map<String, Any?>` results

## Requirements

The native SQLite driver loads `libsqlite3.so.0` at runtime:

```bash
# Debian / Ubuntu
sudo apt install libsqlite3-0

# Arch / Manjaro
sudo pacman -S sqlite
```

## Build & Run

Release build (optimized, stripped):

```bash
gradle linkReleaseExecutableLinuxX64
./build/bin/linuxX64/releaseExecutable/stormify-kotlin-linux-demo.kexe
```

Or build + run in one step (debug):

```bash
gradle runDebugExecutableLinuxX64
```
