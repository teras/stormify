---
hide:
  - toc
---

# Comparison with Other ORMs

This page compares Stormify to the most commonly chosen ORM and SQL-mapping
libraries in the Kotlin and Java ecosystems. All tools listed here are
credible choices for real projects — the comparison is meant to help you
decide which one fits your use case, not to argue one is universally
"better" than another.

The comparison covers:

- **Kotlin-native ORMs** — Exposed (JetBrains), Ktorm, Komapper
- **Kotlin Multiplatform SQL** — SQLDelight (SQL-first code generator, not strictly an ORM, but solves overlapping problems)
- **Java ORMs** — Hibernate (the de-facto JPA implementation) and jOOQ for context

## Feature Matrix

| | **Stormify** | Exposed | Ktorm | Komapper | SQLDelight | jOOQ | Hibernate |
|---|---|---|---|---|---|---|---|
| **Platform** | JVM · Android · Linux · Windows · macOS · iOS | JVM · Android | JVM | JVM | JVM · Android · native · iOS · JS | JVM | JVM |
| **Native DB drivers** *(no JVM / JDBC)* | ✓ PostgreSQL, MariaDB/MySQL, Oracle, MSSQL, SQLite | — | — | — | ✓ SQLite only | — | — |
| **Paged queries with filters + facets + FK traversal** | ✓ `PagedList` / `PagedQuery` | — | — | — | — (Android Paging 3 only) | — | — |
| **Plain classes as entities** *(no required supertype / annotations / codegen)* | ✓ any class | △ `Table` + POJO (DSL) or `IntEntity` base class (DAO) | △ `Entity<E>` interface (or data class since 2.5) | △ data class + `@KomapperEntity` | △ generated from SQL | △ generated from schema | △ POJO + `@Entity` |
| **Annotations required** | optional | — (DSL-based) | — (DSL-based) | required (`@KomapperEntity`) | — (SQL-first) | — (generated from schema) | required (`@Entity`, …) |
| **Runtime reflection** | optional (KSP can replace it) | — | — | — (KSP) | — (SQL-first) | — | required (or build-time bytecode enhancement) |
| **Compile-time metadata** *(via KSP / codegen)* | ✓ (`annproc`) | — | — | ✓ | ✓ (SQL → Kotlin) | ✓ (from schema) | — |
| **JPA-annotation compatible** | ✓ (`@Id`, `@Table`, `@Column`, …) | — | — | — | — | — | defines JPA |
| **Suspend / coroutines API** | ✓ built-in pool + native cancel | ✓ (`newSuspendedTransaction`) | — | ✓ (R2DBC) | ✓ | — | — |
| **Lazy reference delegates** | `by db()` / `by lazyDetails()` | DAO mode (`Referenced`) | — (eager `references()` instead) | — | — | — | `@OneToMany(LAZY)` |
| **Stored procedures with in/out/inout** | ✓ | manual SQL | manual SQL | — | — | ✓ | ✓ |

## Where each library excels

=== "Stormify"

    - One ORM API across JVM, Android, native Linux/Windows/macOS, and iOS — plain Kotlin classes, no DSL to learn.
    - Native database drivers (no JVM, no JDBC required on native platforms).
    - Ship fully native, JVM-free applications — a single self-contained executable on Linux/Windows/macOS, or a native iOS app — with the full ORM baked in.
    - Paged queries with facet filtering, sorting, FK traversal, aggregations, and streaming — in the core library, not a plugin.
    - Drop-in friendly for projects that already use JPA annotations.

=== "Exposed"

    - The default Kotlin DSL most teams reach for; very mature on JVM.
    - Strong DAO mode for Active-Record style if you want it.
    - Rich type-safe query DSL with join/subquery/CTE support.

=== "Ktorm"

    - Similar Kotlin-DSL philosophy to Exposed, with a slightly more fluent syntax for some operations.
    - No reflection; entities use a proxy interface.

=== "Komapper"

    - KSP-generated metadata — no reflection, fast startup.
    - First-class R2DBC support if you're building a reactive stack.

=== "SQLDelight"

    - SQL-first: you write .sq files, the library generates Kotlin.
    - True Kotlin Multiplatform with great ergonomics for SQLite.
    - Not strictly an ORM — no automatic CRUD, no JPA surface.

=== "jOOQ"

    - Type-safe SQL DSL generated from your schema — most accurate reflection of SQL you can get in Java / Kotlin.
    - Not an ORM in the Active-Record sense; complements tools like Hibernate or stands alone.

=== "Hibernate"

    - The reference enterprise Java ORM; pairs with the entire Jakarta EE / Spring Data ecosystem.
    - Sophisticated caching, dirty-checking, and cascade semantics.
    - JVM only; relies on reflection or bytecode enhancement.

## When Stormify is a good fit

- You want **first-class, safe paged queries with facets** — no hand-written SQL snippets for filters and sorts — for UI grids and REST endpoints, built into the library rather than assembled from pieces.
- You need **one codebase across JVM, Android, native Linux/Windows/macOS, and iOS**.
- You want **plain Kotlin or Java classes** without a DSL or heavy annotation surface, but you may already have JPA annotations in existing code.
- You need **suspend-based transactions with a built-in connection pool** and **cancellation wired down to the native driver**.

## When a different library may be a better fit

- **Deeper integration with the JPA ecosystem** — prefer Hibernate.
- **Pure type-safe SQL on the JVM, no ORM layer** — prefer jOOQ.
- **SQLite-only Kotlin Multiplatform with SQL-first workflow** — prefer SQLDelight.
- **Kotlin DSL-based queries on the JVM** — prefer Exposed.

!!! note "Accuracy"

    This matrix summarizes features at a high level as of early 2026. If you
    spot an error or an out-of-date cell, please open an issue on
    [github.com/teras/stormify](https://github.com/teras/stormify/issues).
