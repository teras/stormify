# Stormify Kotlin Multiplatform Demo

A self-contained Kotlin Multiplatform application demonstrating Stormify ORM running the **same business logic** on both JVM and native Linux.

## Highlights

Entity classes and all demo logic live in `commonMain`, while only the DataSource creation is platform-specific:

- **JVM** uses SQLite via JDBC (`org.xerial:sqlite-jdbc`)
- **Linux native** uses SQLite via KDBC's native driver (`KdbcDataSource`)

The **KSP annotation processor** generates entity metadata at compile time, which is required for native and works on JVM as well. Both entities extend `AutoTable` with `by db()` delegates for transparent lazy-loading of references.

## What it demonstrates

- **Shared code** — entities and business logic in `commonMain`
- **Platform-specific DataSource** — only the entry point differs per platform
- **KSP annotation processor** — generates `TableInfo` for both targets
- **Entity references** with lazy loading (`AutoTable` + `by db()`)
- **CRUD operations** — create, findById, findAll, update, delete
- **Transaction DSL** with automatic rollback on exception
- **Raw SQL JOIN query** returning `Map<String, Any?>` results

## Run on JVM

```bash
gradle jvmRun
```

## Run on Linux native

```bash
gradle runDebugExecutableLinuxX64
```
