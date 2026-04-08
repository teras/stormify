# Stormify Kotlin/Native Linux Demo

A self-contained Kotlin/Native application demonstrating Stormify ORM on Linux without a JVM.

## Highlights

This example runs as a **native Linux binary** — no JVM required at runtime. It uses Stormify's native SQLite driver via KDBC. Entity metadata is registered manually via an `EntityRegistrar` (in a real project, the KSP annotation processor generates this automatically).

Both entities extend `AutoTable` with `by db()` delegates for transparent lazy-loading of references.

## What it demonstrates

- **Native binary** — compiles to a standalone Linux executable
- **Manual entity registration** — shows what KSP generates under the hood
- **Entity references** with lazy loading (`AutoTable` + `by db()`)
- **CRUD operations** — create, findById, findAll, update, delete
- **Transaction DSL** with automatic rollback on exception
- **Raw SQL JOIN query** returning `Map<String, Any?>` results

## Build & Run

```bash
gradle runDebugExecutableLinuxX64
```
