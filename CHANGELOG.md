# Changelog

Stormify release history.

## [2.6.0] — 2026-05-17

### Added
- **Cursor API for row-by-row streaming.** Transparent streaming across the
  KDBC drivers (JDBC, native Postgres, MariaDB, Oracle) with per-driver
  dispatch — large result sets can be processed without materialising the
  whole list. Per-driver `cursorFetchSize` defaults are tuned to match each
  backend's natural batch size.
- **`java.time` and Kotlin UUID scalars** registered out of the box in
  `TypeConversion`: `LocalDate`, `LocalDateTime`, `Instant`,
  `OffsetDateTime`, `ZonedDateTime`, `kotlin.uuid.Uuid`, and
  `java.util.UUID` round-trip through the column plan without manual
  converters.
- **Benchmark suite** under `benchmarks/` comparing Stormify against JPA
  across CRUD and read-heavy scenarios, runnable on JVM and native targets.
- **Schema-sync headless modes** — `--export-sql` and `--export-kt` flags
  with category and glob filters, suitable for CI and scripted use with no
  TUI required.
- **Schema-sync packaged installers** — AppImage on Linux, ZIP on Windows,
  signed + notarized `.app` inside a `.tar.gz` on macOS; attached to a
  draft GitHub release on tag push.
- **Schema-sync TUI on Windows** renders natively against the Windows
  console (no MinTTY required), with assorted reliability fixes around
  focus/exit-stack handling.
- `AutoTable.hydrate()` — `protected` at-most-once lazy-load helper.
  Subclasses that hand-write getters/setters (typically Java) call this to
  trigger the same lazy-load behaviour that the Kotlin `db` delegate
  applies automatically.
- **`SuspendStormify.withConnection { }`** — a pooled-connection scope
  without transaction semantics. Until now the only way to borrow from the
  suspend pool was `transaction { }`, which forced BEGIN/COMMIT (and its
  `SQLException` wrapping) onto plain reads. `withConnection` borrows a
  pooled connection in auto-commit mode, publishes it to the ambient
  blocking API exactly like a transaction does, and propagates exceptions
  **unchanged** — application exceptions no longer evict healthy
  connections, and only coroutine cancellation does. The two scopes nest
  in every combination: `withConnection` inside either scope reuses the
  ambient connection, `transaction` inside `transaction` opens a savepoint
  (unchanged), and `transaction` inside `withConnection` runs a full
  BEGIN/COMMIT cycle on the borrowed connection.
- **`stormify.suspending` shared pool.** `suspending` is now a lazy
  property on `Stormify` (tuned by the new `poolConfig` constructor
  parameter), so the whole process shares one pool by default. The
  `suspending(config)` factory extension is removed — code that needs a
  separate pool uses the new public `SuspendStormify(stormify, config)`
  constructor. `Stormify.closeSuspending()` closes the shared pool at
  shutdown without creating it when it was never used.

### Changed
- **`AutoTable` auto-hydration is now ownership-based.** A user-constructed
  entity is never auto-loaded from the database. Library-constructed shadow
  references (FK stubs produced by a parent's read) continue to auto-hydrate
  on first `db` property access.
- `AutoTable.markPopulated()` was renamed to `AutoTable.markHydrated()` for
  vocabulary consistency.
- **Published libraries target JVM 8 bytecode**, broadening the set of
  runtimes that can consume them. `schema-sync` and the test/benchmark
  modules target JVM 11.
- The `schema-sync` demo now runs against a dedicated `stormify_demo`
  database, isolated from the conformance suite so the two no longer share
  state.
- **Android minimum is now API 26** (Android 8.0), the floor imposed by
  `java.time` usage in the shared JVM/Android source set. (The 2.5.x line
  had silently raised the floor from 21 to 28; 26 is the actual technical
  minimum and restores part of the lost device coverage.)
- **Suspend `transaction { }` now reports failures as `SQLException`**, the
  same contract the blocking API always had: the original throwable (from
  the database or from user code inside the block) is kept as `cause`.
  Coroutine cancellation is exempt and still propagates unwrapped.

### Fixed
- Reading a text or binary column into a numeric field no longer yields `0`
  on the native drivers and on Android. The C getters parse with
  `strtoll`/`strtod` and `Cursor.getInt` behaves the same way — both answer
  `0` for input they cannot parse and report no error, so a column of words
  read into an `Int` field silently became zero on those platforms while the
  same read failed on JVM. The numeric getters are now taken only when the
  column actually holds a number; otherwise the value is read in the
  column's own type and converted, which refuses what it cannot represent.
- Reading a numeric column into a `CharArray` works on every database.
  `TypeConversion` derives the `CharArray` conversions from the `String`
  ones, so anything with a text form has a character form.
- MySQL/MariaDB `BOOLEAN` columns (stored as `TINYINT(1)`) are reported as
  booleans by the native driver, matching both JDBC drivers.
- Paged-list and paged-query window counts no longer assume the driver hands
  back a `kotlin.Number`, and are converted through `TypeConversion`.
- Reflection-based entity discovery (Maven/plain-JVM projects without the
  annotation processor) again honors `javax.persistence.Column`
  (`name`/`insertable`/`updatable`) — it had been looking up a nonexistent
  `javax.persistence.Facet` annotation since 2.1.0.
- Batch `create()` no longer assigns a generated key to the wrong entity
  when the single item needing a database-generated id is not the last in
  the batch; the key is now read immediately after that item's own insert.
- JDBC typed `getObject` calls no longer receive primitive Java classes
  (`boolean.class` & friends) when the requested `KClass` originates from
  `typeOf<T>()` — drivers that reject primitive class tokens now get the
  boxed type, restoring proper typed conversions (e.g. SQLite
  `TEXT '1'` → `Boolean true`).
- Scalar reads whose selected column is SQL NULL now fail with a clear
  `SQLException` naming the target type and suggesting SQL-side handling
  (`COALESCE`), instead of the generic "Expecting type … but found null".
- `update()` / `delete()` with a null primary key now fail immediately with a
  clear `SQLException` instead of silently affecting zero rows.
- Batch `create()` with sequence-generated ids now fails with a clear
  `SQLException` when the database returns fewer sequence values than
  requested, instead of an `IndexOutOfBoundsException`.
- An empty collection passed as a query parameter now fails with a clear
  `SQLException` ("cannot be expanded into an IN list") instead of producing
  invalid `IN ()` SQL and a driver-level syntax error.
- A cursor query's `fetchSize` no longer leaks into subsequent non-cursor
  uses of the same SQL through the prepared-statement cache; the driver
  default is restored when the statement is returned to the cache.
- `update()` on an entity with no updatable fields now fails with a clear
  `SQLException` instead of emitting invalid `UPDATE … SET  WHERE …` SQL.
- The annotation processor and the Gradle plugin's source generator now report
  a clear error at build time when two entities, two enums, or an entity and
  an enum share the same simple name in different packages, instead of
  emitting colliding declarations that fail with a cryptic "redeclaration"
  or "conflicting import" error.
- Unmatched-column diagnostics and `@DbField` KDoc no longer refer to columns
  as "Facet" — a leftover of an old rename that pointed users at the
  unrelated `biglist.Facet` API.
- Suspend `transaction { }` no longer leaks the internal
  `HealthyConnectionException` marker to callers when an exception crosses
  a dispatcher boundary; the connection-health signal is unwrapped layer
  by layer at the pool boundary.

### Removed
- `PagedList.add(entity)` / `PagedList.remove(entity)` — misleading names
  inherited from the original Java API: they never touched the database, they
  merely set/cleared the selection (`remove` even ignored its argument). Use
  the `selected` property directly.
- `PagedList.set(index, element)` — mutated only the cached page in memory
  while looking exactly like `java.util.List.set`, suggesting persistence that
  never happened; the change vanished silently on the next `refresh()`. To
  show updated data, persist via `update(entity)` and call `refresh()`.
- `AutoTable.populate()` (the public direct-call API) and
  `Stormify.populate(entity)` / `StormifyJ.populate(entity)` (the
  manager-level loaders) were removed in favour of the single direct-call
  entry point `Stormify.refresh(entity)` / `StormifyJ.refresh(entity)`,
  equivalent to JPA's `EntityManager.refresh(entity)`. Every call performs
  a SELECT and overwrites the in-memory fields.

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

[2.6.0]: https://github.com/teras/stormify/compare/v2.5.1...v2.6.0
[2.5.1]: https://github.com/teras/stormify/compare/v2.5.0...v2.5.1
[2.5.0]: https://github.com/teras/stormify/compare/v2.1.1...v2.5.0
[2.1.1]: https://github.com/teras/stormify/compare/v2.1.0...v2.1.1
[2.1.0]: https://github.com/teras/stormify/compare/v2.0.0...v2.1.0
[2.0.0]: https://github.com/teras/stormify/compare/v1.3.0...v2.0.0
[1.3.0]: https://github.com/teras/stormify/compare/V1.0...v1.3.0
[1.0.0]: https://github.com/teras/stormify/releases/tag/V1.0
