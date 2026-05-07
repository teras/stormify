# Changelog

Stormify release history.

## [2.5.1] — 2026-05-07

### Fixed
- Entity creation when the primary key property name requires
  camelCase ↔ snake_case mapping (e.g. `userId` ↔ `user_id`).
  The generated INSERT no longer mis-resolves the id column.

## [2.5.0] — 2026-05-01

### Added
- **`stormify-gradle-plugin`** that auto-wires KSP, `annproc`, and the
  runtime dependency for JVM, Android, and Kotlin Multiplatform
  projects, with a lifecycle-aware generated entity registrar so the
  generated `TableInfo` is wired in without manual setup.
- `initSql` parameter on `KdbcDataSource` — single SQL statement
  executed on every freshly opened connection.
- `sqlState` and `errorCode` properties on `kdbc.SQLException`,
  populated from the underlying driver (JDBC on JVM/Android, native
  vtable on Kotlin/Native) when available.
- Single-argument constructors for `Stormify` and `StormifyJ` to
  support Spring XML dependency injection.
- Per-source-set tables placement planner for the annotation
  processor: intermediate consolidation, expect/actual/plain emission,
  hard-fail wiring reflection, and a scenario test matrix.
- Suspend transaction stress test and a cross-driver bind type matrix
  test suite.

### Changed
- **Ambient transaction registry** replaces the `TransactionContext`
  receiver. `transaction { ... }` now installs a thread-local active
  context, so CRUD calls work directly inside the block without the
  receiver. Nested transactions and savepoints behave the same as
  before.
- Tests moved into their own module for cleaner build separation.
- Pooled connections on native and Android are now bound to a
  long-lived single-thread dispatcher, giving consistent suspend
  semantics and reliable cancellation.
- Placeholder scanning is quote- and comment-aware: `?` inside string
  literals and SQL comments is no longer counted as a parameter, and
  list expansion respects the same rules.
- Normalized bind type coercions across all drivers; bind errors now
  include parameter index and value context for easier debugging.
- Examples REST DB generator cleaned up.

### Removed
- `TransactionContext` class and its receiver-style extension methods
  — superseded by the ambient transaction registry. Code that
  previously took `TransactionContext` as a receiver or parameter
  must be updated to use the ambient context instead.

## [2.1.1] — 2026-04-22

### Added
- KSP options for the generated code's package and class names
  (default: `onl.ycode.stormify.generated`).
- `@Throws(SQLException::class)` annotations on public `Stormify` and
  `TransactionContext` methods for clean Java interop.
- `register` hook on `LogFramework` so consumers can plug in custom
  logging backends.

### Changed
- `LogFramework` converted from a sealed enum into an open abstract
  class; `LogLevel` extracted into its own file.
- `Stormify` class is now `final`; tightened visibility across
  `annproc` and `logger` modules.
- Unified stored-procedure factories under the `Sp` companion with
  `Class<T>` overloads on JVM/Android.
- Stormify main sources now throw `SQLException` directly instead of
  `error()` / `IllegalStateException`.

### Removed
- `gradle syncReadmeUrls` task — superseded by `bump-version.sh`
  handling README URL rewrites directly.

## [2.1.0] — 2026-04-21

### Added
- Google-like free-text search across entity fields.
- Single-roundtrip pagination via window functions with distinct-safe count.
- Log level support in the logger abstraction.
- Sybase / FreeTDS coverage alongside MS SQL Server.

### Changed
- Unified `SqlGenerator` and `Converter` into a single typealias and
  formalized the `OrderedNode` AST for query building.
- Renamed `Paged*Base` classes to `AbstractPaged*` for idiomatic Kotlin.
- Split temporal facets into `date` / `time` / `timestamp` with
  dialect-aware casts and UTC-anchored KDBC binding.
- Replaced the old strict-DB flag with a three-state
  `UnmatchedColumnPolicy` (`IGNORE` / `WARN` / `THROW`).
- Hardened Maven Central publishing workflow (merged multi-host staging,
  re-signed root KMP modules, USER_MANAGED Central Portal upload).
- Improved numeric type handling and smart string-to-number parsing.

### Fixed
- Oracle pagination with quoted internal aliases now uses dialect-aware
  formatters.
- macOS and iOS targets build and test correctly across Apple Silicon
  and Intel simulators.

## [2.0.0] — 2026-04-14

First Kotlin Multiplatform release. This is a major version bump with
breaking changes; see [Migration_V1_to_V2](docs/src/Migration_V1_to_V2.md).

### Added
- **Kotlin Multiplatform targets**: JVM, Android (API 21+), Linux x64 /
  ARM64, Windows (mingwX64), macOS (Apple Silicon + Intel), and iOS
  (device, simulator Intel, simulator Apple Silicon).
- **Native database drivers** via a unified `libkdbc` C library behind a
  single vtable: SQLite, PostgreSQL (libpq), MariaDB/MySQL (libmariadb),
  Oracle (ODPI-C), MS SQL Server (FreeTDS). No JVM, no JDBC.
- **Suspend transaction API** (`SuspendStormify`) with a built-in
  coroutine-aware connection pool, prewarm, validation, background
  cleanup, graceful shutdown, and coroutine cancellation wired to native
  database cancel primitives (`kdbc_cancel`).
- **Stored procedure API** (`Sp`) with IN / OUT / INOUT parameters for
  PostgreSQL, Oracle, MSSQL, MariaDB, and MySQL.
- **Enum support** with ordinal, custom `DbValue` integer, and string
  storage modes; case-insensitive matching.
- **PagedList** and **PagedQuery** for UI grids and stateless REST
  endpoints — filters, sorting, FK traversal, aggregations, facet
  counts, streaming `forEach` over very large result sets, and
  save/restore state.
- **Annotation processor** (`annproc`) based on KSP to generate
  `TableInfo` registrations at compile time (required for native and
  Android; optional on JVM).
- **Android** support with Robolectric test coverage and a working
  Compose example app.
- **Transaction results**: `transaction { ... }` returns the block's
  value.
- Reference paths and type-safe `lazyDetails` for Java consumers.
- Multi-encoding test suite including Oracle 11g with Greek
  `EL8ISO8859P7` charset.
- Boolean bindings across all drivers.

### Changed
- Converters moved from `stormify` into `kdbc`; binary `ionspin`
  conversions; converter classes under `onl.ycode.kdbc.converters`.
- Examples are published as an external repository
  ([stormify-examples](https://github.com/teras/stormify-examples)) and
  linked via a git submodule.
- Library compiles to Java 8 bytecode for consumer compatibility; built
  with JDK 11 / tested with JDK 17.
- Single macOS-based publish workflow replaced with a multi-host CI
  that aggregates platform-specific klibs into one KMP deployment.

### Fixed
- Oracle 11g cancel hang — skip rollback/close after `OCIBreak` (no
  `OCIReset` in ODPI-C).
- Reflection, reference resolution, and batch update for JVM entity
  handling.
- `kdbc_stmt_reset` now clears batches, OUT-parameter values, and
  generated-key state.
- UUID generated keys for PostgreSQL and MSSQL.
- Android suspend transaction cancellation; added scoped `asDefault`.

## [1.3.0] — 2026-04-01

### Added
- **Batch operations**: batch insert, batch update, batch delete.
- **Multi-database support**: auto-increment handling, vendor-specific
  types, case-insensitive columns, dialect detection fixes.
- **Docker-based multi-database testing** infrastructure covering 10
  database configurations.
- More temporal datatypes.
- Batch population and reference deduplication for auto-populated
  entities.

### Changed
- Explicit column selection in generated queries (no more `SELECT *`).

### Fixed
- SQL-related bugs in composite / missing primary-key resolution.
- HikariCP dependency scoped to test-only.

## [1.0.0] — 2025-01-29

Initial public release on Maven Central.

- CRUD on plain Kotlin classes with convention-based field mapping.
- Transactions with commit / rollback and nested savepoints.
- JPA annotation compatibility (`@Id`, `@Table`, `@Column`, …).
- JVM / JDBC-only, reflection-based entity discovery.

[2.5.1]: https://github.com/teras/stormify/compare/v2.5.0...v2.5.1
[2.5.0]: https://github.com/teras/stormify/compare/v2.1.1...v2.5.0
[2.1.1]: https://github.com/teras/stormify/compare/v2.1.0...v2.1.1
[2.1.0]: https://github.com/teras/stormify/compare/v2.0.0...v2.1.0
[2.0.0]: https://github.com/teras/stormify/compare/v1.3.0...v2.0.0
[1.3.0]: https://github.com/teras/stormify/compare/V1.0...v1.3.0
[1.0.0]: https://github.com/teras/stormify/releases/tag/V1.0
