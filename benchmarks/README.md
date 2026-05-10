# Stormify benchmarks

Compare Stormify (native linuxX64) against plain Hibernate 6 (JVM jar, optionally GraalVM native-image) across 5 databases.

## Layout

```
benchmarks/
├── schema/               # DDL per DB (sqlite, postgresql, mysql, oracle, mssql)
├── stormify-native/      # KMP linuxX64 executable
├── jpa-jvm/              # Plain Hibernate 6, fat jar via shadow
│   └── native-image-build.sh   # Optional: build GraalVM native-image
├── runner/run.sh         # Orchestrator (bash, no Gradle at runtime)
├── report/               # HTML report generator (Python, Chart.js)
└── results/              # CSV + report.html (gitignore-able)
```

## Build

```bash
cd benchmarks
gradle :stormify-native:linkReleaseExecutableLinuxX64
gradle :jpa-jvm:shadowJar
# Optional: GraalVM native-image
sdk install java 21.0.11-graal && sdk use java 21.0.11-graal
./jpa-jvm/native-image-build.sh
```

## Run

Containers (use the project's `testing/test.sh` to start the DBs you want):

```bash
../testing/test.sh up postgresql mysql oracle mssql
```

Then:

```bash
./runner/run.sh                            # all DBs, all impls, default sizes
./runner/run.sh sqlite postgresql          # subset
IMPLS="stormify-native jpa-jvm" ./runner/run.sh sqlite
BENCH_PARENTS=1000 BENCH_CHILDREN=10000 ./runner/run.sh
```

Outputs:
- `results/results.csv` — raw per-iteration timings
- `results/report.html` — interactive HTML with p50/p95/p99 charts and full stats
- `results/run.log` — execution log

## Tunables (env vars)

| Var              | Default | Meaning                                  |
|------------------|---------|------------------------------------------|
| `BENCH_PARENTS`  | 1000    | Rows in `bench_parent`                   |
| `BENCH_CHILDREN` | 10000   | Rows in `bench_child` left for the read scenarios |
| `BENCH_WARM`     | 5       | Warm-up iterations per scenario          |
| `BENCH_ITERS`    | 500     | Measured iterations per read scenario    |
| `BENCH_HOST`     | localhost | Database host (containers expose ports) |
| `IMPLS`          | `stormify-native jpa-jvm jpa-native` | Which implementations to run (missing ones are skipped) |
| `DBS`            | `sqlite postgresql mysql oracle mssql` | Override DB list |

## Scenarios

**Inserts** (single-shot timed totals):
- `insert_1000`, `insert_10000` — bulk-insert phases against `bench_child`,
  the latter leaving `BENCH_CHILDREN` rows in place for the reads
- `single_insert_1000` — 1000 separate one-row transactions (round-trip cost)

**Reads** (per-iteration p50/p95/p99):
- `findById` — primary-key lookup
- `list_window_1000` — 1000-row select
- `join_child_parent` — 2-table JOIN projected to a typed DTO
- `n_plus_1_50`, `n_plus_1_1000` — N children + parent lookup
- `paged_scan` — cursor / streaming over 5000 rows
- `complex_filter` — multi-condition WHERE
- `update_bulk_sql` — single `UPDATE … WHERE` statement
- `update_bulk_orm` — entity-level batch update
- `tx_rollback` — transaction that rolls back

## What it measures

For each (db, impl, scenario):
- Wall-clock per iteration (CLOCK_MONOTONIC on native, `System.nanoTime` on JVM)
- Cold-start time from process launch to "ready for queries"
- Peak RSS (from `/proc/self/status`)

The HTML report renders log-scale grouped bar charts (p50/p95/p99 togglable) and a full stats table.
