# Benchmarks

Stormify-native (Kotlin/Native bound to libpq, libmariadb, ODPI-C, FreeTDS,
libsqlite3) measured against plain Hibernate 6 backed by pgjdbc / mysql-
connector-j / ojdbc11 / mssql-jdbc / org.xerial sqlite-jdbc, all running
against the same Docker-hosted database instances. The bench is co-located
with the source: `benchmarks/runner/run.sh` orchestrates DDL prepare →
inserts → reads across the matrix of databases × implementations and writes
`benchmarks/results/results.csv`.

The data tables on this page are populated from that CSV by
`benchmarks/report/generate-benchmarks-md.py`. After a fresh bench run we
look at the regenerated tables, decide whether the prose still matches
reality, and edit it directly.

## Methodology

* **Schema**: two tables. `bench_parent` is small (default 1000 rows) and
  acts as the lookup target for the JOIN and N+1 scenarios. `bench_child`
  is the workhorse (default 10 000 rows) where every other scenario runs.
* **Iterations**: 500 per read scenario, lower for heavier write paths
  (50 for `update_bulk_*` and `tx_rollback`, 1000 separate transactions for
  `single_insert_1000`).
* **Statistic**: per-iteration `p50` (median). 5 warm-up iterations per
  scenario run before measurement begins (override with `BENCH_WARM`):
  same SQL, different parameter window per iter. The point is to put
  the **database** in the steady state a real production workload would
  already be in — buffer pool primed, query plan compiled, server-side
  prepared statement ready, driver JIT/AOT settled. No result-set data
  is cached on the ORM side because every iteration's params are new.
  A separate JIT pass against the dedicated `bench_warmup` table runs
  even earlier, so the measurement tables stay untouched until the
  first measured iter of the first scenario.
* **Setup**: every iteration runs in its own short transaction *only when
  the equivalent JPA path also opens one* (writes and `tx_rollback`).
  Read-only scenarios are not wrapped in a transaction on either side.
* **Connection**: a single persistent JDBC / KDBC connection per process;
  no pool churn included in the per-iter timing.
* **Data sizes**: defaults above; override via `BENCH_PARENTS` and
  `BENCH_CHILDREN`. The two insert-throughput phases (`insert_1000`,
  `insert_10000`) clear `bench_child` between runs and refill at the
  named row count; the final phase leaves `BENCH_CHILDREN` rows in
  place for the read scenarios.

A standalone HTML report with the same data plus chart-style visualisation
sits at `benchmarks/results/report.html` after each bench run.

## Cold start and memory footprint

The bench process logs both its time-to-first-query and resident-set-size
on every run. These don't measure database performance — they measure the
cost of spinning up the runtime that drives it.

<!-- STARTUP_RESULTS -->
**Cold start — process spawn to first query ready**

| | SQLite | MySQL | PostgreSQL | Oracle | SQL Server |
|---|---:|---:|---:|---:|---:|
| Stormify-native | 440μs | 3.1ms | 25.6ms | 25.0ms | 8.9ms |
| Hibernate (JPA) | 2531ms | 2446ms | 2103ms | 2357ms | 2194ms |
| Native is | <span style="color:#2e7d32">5753× faster</span> | <span style="color:#2e7d32">794× faster</span> | <span style="color:#2e7d32">82× faster</span> | <span style="color:#2e7d32">94× faster</span> | <span style="color:#2e7d32">246× faster</span> |

**Resident set size at startup**

| | SQLite | MySQL | PostgreSQL | Oracle | SQL Server |
|---|---:|---:|---:|---:|---:|
| Stormify-native | 7.0MB | 11.0MB | 13.7MB | 44.6MB | 15.0MB |
| Hibernate (JPA) | 314MB | 336MB | 327MB | 356MB | 356MB |
| Native is | <span style="color:#2e7d32">45× lighter</span> | <span style="color:#2e7d32">31× lighter</span> | <span style="color:#2e7d32">24× lighter</span> | <span style="color:#2e7d32">8× lighter</span> | <span style="color:#2e7d32">24× lighter</span> |

<!-- /STARTUP_RESULTS -->

JPA startup is dominated by JVM bootstrap + Hibernate metadata loading:
classpath scanning, entity-manager-factory construction, dialect detection,
all before the first query. Stormify-native ships as a static binary with
the entity descriptors already baked in by the annproc-generated
`GeneratedEntities`, so the same path collapses to "open the libpq /
libmariadb / ODPI-C / FreeTDS / sqlite3 connection, fire the query."

The RSS comparison reflects the same thing: a JVM heap settles at a few
hundred megabytes regardless of workload size; the native binary holds
just the C-library state plus live result-set buffers.

## Read scenarios

Per-iteration durations in microseconds. Lower is better. The "speedup"
row is `jpa / native` — values **above** 1.0 mean Stormify-native is
faster (a 5.0× speedup means native finished in one fifth of the JPA
time). Each scenario exposes p50 / p95 / p99 as separate tabs.

<!-- READ_RESULTS -->
### findById

_PK lookup, one-row entity hydration._

```sql
SELECT * FROM bench_child WHERE id = ?
```

=== "p50"

    | | SQLite | MySQL | PostgreSQL | Oracle | SQL Server |
    |---|---:|---:|---:|---:|---:|
    | Stormify-native | 9μs | 72μs | 133μs | 31μs | 132μs |
    | Hibernate (JPA) | 81μs | 391μs | 135μs | 225μs | 316μs |
    | Speedup (higher=better) | <span style="color:#2e7d32">8.80x ✓</span> | <span style="color:#2e7d32">5.44x ✓</span> | 1.01x ~ | <span style="color:#2e7d32">7.17x ✓</span> | <span style="color:#2e7d32">2.39x ✓</span> |

=== "p95"

    | | SQLite | MySQL | PostgreSQL | Oracle | SQL Server |
    |---|---:|---:|---:|---:|---:|
    | Stormify-native | 20μs | 95μs | 394μs | 92μs | 282μs |
    | Hibernate (JPA) | 248μs | 1285μs | 782μs | 667μs | 755μs |
    | Speedup (higher=better) | <span style="color:#2e7d32">12.25x ✓</span> | <span style="color:#2e7d32">13.51x ✓</span> | <span style="color:#2e7d32">1.98x ✓</span> | <span style="color:#2e7d32">7.25x ✓</span> | <span style="color:#2e7d32">2.68x ✓</span> |

=== "p99"

    | | SQLite | MySQL | PostgreSQL | Oracle | SQL Server |
    |---|---:|---:|---:|---:|---:|
    | Stormify-native | 30μs | 129μs | 607μs | 122μs | 434μs |
    | Hibernate (JPA) | 363μs | 1838μs | 1221μs | 955μs | 1353μs |
    | Speedup (higher=better) | <span style="color:#2e7d32">12.30x ✓</span> | <span style="color:#2e7d32">14.26x ✓</span> | <span style="color:#2e7d32">2.01x ✓</span> | <span style="color:#2e7d32">7.83x ✓</span> | <span style="color:#2e7d32">3.12x ✓</span> |


### list_window_1000

_1000-row windowed SELECT into a typed list._

```sql
SELECT * FROM bench_child WHERE id BETWEEN ? AND ?
```

=== "p50"

    | | SQLite | MySQL | PostgreSQL | Oracle | SQL Server |
    |---|---:|---:|---:|---:|---:|
    | Stormify-native | 922μs | 1063μs | 897μs | 1122μs | 901μs |
    | Hibernate (JPA) | 5996μs | 48.5ms | 30.1ms | 40.7ms | 44.4ms |
    | Speedup (higher=better) | <span style="color:#2e7d32">6.51x ✓</span> | <span style="color:#2e7d32">45.58x ✓</span> | <span style="color:#2e7d32">33.59x ✓</span> | <span style="color:#2e7d32">36.29x ✓</span> | <span style="color:#2e7d32">49.31x ✓</span> |

=== "p95"

    | | SQLite | MySQL | PostgreSQL | Oracle | SQL Server |
    |---|---:|---:|---:|---:|---:|
    | Stormify-native | 1154μs | 3248μs | 2645μs | 1912μs | 1974μs |
    | Hibernate (JPA) | 7800μs | 110.9ms | 126.9ms | 128.3ms | 99.4ms |
    | Speedup (higher=better) | <span style="color:#2e7d32">6.76x ✓</span> | <span style="color:#2e7d32">34.16x ✓</span> | <span style="color:#2e7d32">47.98x ✓</span> | <span style="color:#2e7d32">67.08x ✓</span> | <span style="color:#2e7d32">50.35x ✓</span> |

=== "p99"

    | | SQLite | MySQL | PostgreSQL | Oracle | SQL Server |
    |---|---:|---:|---:|---:|---:|
    | Stormify-native | 1456μs | 4021μs | 3492μs | 2130μs | 2994μs |
    | Hibernate (JPA) | 11.3ms | 180.7ms | 163.6ms | 181.5ms | 203.7ms |
    | Speedup (higher=better) | <span style="color:#2e7d32">7.77x ✓</span> | <span style="color:#2e7d32">44.93x ✓</span> | <span style="color:#2e7d32">46.86x ✓</span> | <span style="color:#2e7d32">85.23x ✓</span> | <span style="color:#2e7d32">68.05x ✓</span> |


### join_child_parent

_500-row JOIN projected to a typed DTO._

```sql
SELECT c.id AS cid, c.value, p.name FROM bench_child c
  JOIN bench_parent p ON c.parent_id = p.id
  WHERE c.id BETWEEN ? AND ?
```

=== "p50"

    | | SQLite | MySQL | PostgreSQL | Oracle | SQL Server |
    |---|---:|---:|---:|---:|---:|
    | Stormify-native | 254μs | 394μs | 372μs | 30.3ms | 552μs |
    | Hibernate (JPA) | 266μs | 581μs | 313μs | 30.2ms | 410μs |
    | Speedup (higher=better) | 1.05x ~ | <span style="color:#2e7d32">1.47x ✓</span> | <span style="color:#c62828">0.84x ✗</span> | 0.99x ~ | <span style="color:#c62828">0.74x ✗</span> |

=== "p95"

    | | SQLite | MySQL | PostgreSQL | Oracle | SQL Server |
    |---|---:|---:|---:|---:|---:|
    | Stormify-native | 342μs | 502μs | 1791μs | 32.6ms | 1071μs |
    | Hibernate (JPA) | 346μs | 763μs | 619μs | 32.4ms | 509μs |
    | Speedup (higher=better) | 1.01x ~ | <span style="color:#2e7d32">1.52x ✓</span> | <span style="color:#c62828">0.35x ✗</span> | 0.99x ~ | <span style="color:#c62828">0.48x ✗</span> |

=== "p99"

    | | SQLite | MySQL | PostgreSQL | Oracle | SQL Server |
    |---|---:|---:|---:|---:|---:|
    | Stormify-native | 387μs | 856μs | 2909μs | 44.0ms | 1480μs |
    | Hibernate (JPA) | 359μs | 1371μs | 672μs | 41.3ms | 571μs |
    | Speedup (higher=better) | 0.93x ~ | <span style="color:#2e7d32">1.60x ✓</span> | <span style="color:#c62828">0.23x ✗</span> | 0.94x ~ | <span style="color:#c62828">0.39x ✗</span> |


### n_plus_1_50

_50-row child page + bulk-fetch parents (the JPA-friendly N+1 size)._

```sql
-- Stormify (2 round-trips):
SELECT * FROM bench_child  WHERE id BETWEEN ? AND ?     -- 50 rows
SELECT * FROM bench_parent WHERE id IN (?, ?, …)
-- JPA via lazy @ManyToOne (51 round-trips):
SELECT * FROM bench_child  WHERE id BETWEEN ? AND ?     -- 50 rows
SELECT * FROM bench_parent WHERE id = ?                 -- ×50
```

=== "p50"

    | | SQLite | MySQL | PostgreSQL | Oracle | SQL Server |
    |---|---:|---:|---:|---:|---:|
    | Stormify-native | 99μs | 184μs | 175μs | 434μs | 740μs |
    | Hibernate (JPA) | 315μs | 2572μs | 1389μs | 1766μs | 2199μs |
    | Speedup (higher=better) | <span style="color:#2e7d32">3.18x ✓</span> | <span style="color:#2e7d32">13.94x ✓</span> | <span style="color:#2e7d32">7.94x ✓</span> | <span style="color:#2e7d32">4.07x ✓</span> | <span style="color:#2e7d32">2.97x ✓</span> |

=== "p95"

    | | SQLite | MySQL | PostgreSQL | Oracle | SQL Server |
    |---|---:|---:|---:|---:|---:|
    | Stormify-native | 125μs | 252μs | 343μs | 1225μs | 2288μs |
    | Hibernate (JPA) | 337μs | 4702μs | 2822μs | 3626μs | 8913μs |
    | Speedup (higher=better) | <span style="color:#2e7d32">2.71x ✓</span> | <span style="color:#2e7d32">18.67x ✓</span> | <span style="color:#2e7d32">8.24x ✓</span> | <span style="color:#2e7d32">2.96x ✓</span> | <span style="color:#2e7d32">3.90x ✓</span> |

=== "p99"

    | | SQLite | MySQL | PostgreSQL | Oracle | SQL Server |
    |---|---:|---:|---:|---:|---:|
    | Stormify-native | 214μs | 380μs | 384μs | 1496μs | 3253μs |
    | Hibernate (JPA) | 360μs | 8114μs | 3546μs | 6150μs | 15.2ms |
    | Speedup (higher=better) | <span style="color:#2e7d32">1.68x ✓</span> | <span style="color:#2e7d32">21.34x ✓</span> | <span style="color:#2e7d32">9.24x ✓</span> | <span style="color:#2e7d32">4.11x ✓</span> | <span style="color:#2e7d32">4.66x ✓</span> |


### n_plus_1_1000

_1000-row child N+1 — extreme case; JPA fires 1001 round-trips._

```sql
-- Stormify (2 round-trips):
SELECT * FROM bench_child  WHERE id BETWEEN ? AND ?     -- 1000 rows
SELECT * FROM bench_parent WHERE id IN (?, ?, …)
-- JPA via lazy @ManyToOne (1001 round-trips):
SELECT * FROM bench_child  WHERE id BETWEEN ? AND ?     -- 1000 rows
SELECT * FROM bench_parent WHERE id = ?                 -- ×1000
```

=== "p50"

    | | SQLite | MySQL | PostgreSQL | Oracle | SQL Server |
    |---|---:|---:|---:|---:|---:|
    | Stormify-native | 1766μs | 2026μs | 1763μs | 2781μs | — |
    | Hibernate (JPA) | 6302μs | 47.3ms | 31.3ms | 50.2ms | 42.4ms |
    | Speedup (higher=better) | <span style="color:#2e7d32">3.57x ✓</span> | <span style="color:#2e7d32">23.37x ✓</span> | <span style="color:#2e7d32">17.73x ✓</span> | <span style="color:#2e7d32">18.04x ✓</span> | — |

=== "p95"

    | | SQLite | MySQL | PostgreSQL | Oracle | SQL Server |
    |---|---:|---:|---:|---:|---:|
    | Stormify-native | 1969μs | 2889μs | 7374μs | 3407μs | — |
    | Hibernate (JPA) | 6672μs | 111.6ms | 122.3ms | 171.2ms | 83.3ms |
    | Speedup (higher=better) | <span style="color:#2e7d32">3.39x ✓</span> | <span style="color:#2e7d32">38.62x ✓</span> | <span style="color:#2e7d32">16.58x ✓</span> | <span style="color:#2e7d32">50.23x ✓</span> | — |

=== "p99"

    | | SQLite | MySQL | PostgreSQL | Oracle | SQL Server |
    |---|---:|---:|---:|---:|---:|
    | Stormify-native | 2948μs | 3122μs | 8643μs | 4596μs | — |
    | Hibernate (JPA) | 7202μs | 199.4ms | 169.4ms | 210.4ms | 141.5ms |
    | Speedup (higher=better) | <span style="color:#2e7d32">2.44x ✓</span> | <span style="color:#2e7d32">63.86x ✓</span> | <span style="color:#2e7d32">19.60x ✓</span> | <span style="color:#2e7d32">45.78x ✓</span> | — |


### paged_scan

_5000-row server-side cursor stream (fetchSize = 50)._

```sql
SELECT * FROM bench_child WHERE id BETWEEN ? AND ?
```

=== "p50"

    | | SQLite | MySQL | PostgreSQL | Oracle | SQL Server |
    |---|---:|---:|---:|---:|---:|
    | Stormify-native | 3765μs | 4086μs | 3351μs | 5653μs | 4215μs |
    | Hibernate (JPA) | 11.6ms | 52.6ms | 40.9ms | 41.2ms | 49.1ms |
    | Speedup (higher=better) | <span style="color:#2e7d32">3.08x ✓</span> | <span style="color:#2e7d32">12.86x ✓</span> | <span style="color:#2e7d32">12.19x ✓</span> | <span style="color:#2e7d32">7.28x ✓</span> | <span style="color:#2e7d32">11.65x ✓</span> |

=== "p95"

    | | SQLite | MySQL | PostgreSQL | Oracle | SQL Server |
    |---|---:|---:|---:|---:|---:|
    | Stormify-native | 4124μs | 5421μs | 5077μs | 5961μs | 4796μs |
    | Hibernate (JPA) | 12.3ms | 121.3ms | 169.2ms | 134.1ms | 131.5ms |
    | Speedup (higher=better) | <span style="color:#2e7d32">2.97x ✓</span> | <span style="color:#2e7d32">22.37x ✓</span> | <span style="color:#2e7d32">33.33x ✓</span> | <span style="color:#2e7d32">22.50x ✓</span> | <span style="color:#2e7d32">27.42x ✓</span> |

=== "p99"

    | | SQLite | MySQL | PostgreSQL | Oracle | SQL Server |
    |---|---:|---:|---:|---:|---:|
    | Stormify-native | 4160μs | 5721μs | 5485μs | 6443μs | 6013μs |
    | Hibernate (JPA) | 14.3ms | 161.5ms | 213.1ms | 164.4ms | 211.9ms |
    | Speedup (higher=better) | <span style="color:#2e7d32">3.44x ✓</span> | <span style="color:#2e7d32">28.23x ✓</span> | <span style="color:#2e7d32">38.85x ✓</span> | <span style="color:#2e7d32">25.51x ✓</span> | <span style="color:#2e7d32">35.24x ✓</span> |


### complex_filter

_Three-predicate filter on bench_child._

```sql
SELECT * FROM bench_child
  WHERE status = ? AND value BETWEEN ? AND ?
    AND id BETWEEN ? AND ?
```

=== "p50"

    | | SQLite | MySQL | PostgreSQL | Oracle | SQL Server |
    |---|---:|---:|---:|---:|---:|
    | Stormify-native | 551μs | 950μs | 710μs | 9866μs | 547μs |
    | Hibernate (JPA) | 1320μs | 5946μs | 5837μs | 5950μs | 4823μs |
    | Speedup (higher=better) | <span style="color:#2e7d32">2.40x ✓</span> | <span style="color:#2e7d32">6.26x ✓</span> | <span style="color:#2e7d32">8.22x ✓</span> | <span style="color:#c62828">0.60x ✗</span> | <span style="color:#2e7d32">8.82x ✓</span> |

=== "p95"

    | | SQLite | MySQL | PostgreSQL | Oracle | SQL Server |
    |---|---:|---:|---:|---:|---:|
    | Stormify-native | 589μs | 1344μs | 2537μs | 10.7ms | 1222μs |
    | Hibernate (JPA) | 1483μs | 20.4ms | 23.7ms | 12.7ms | 6126μs |
    | Speedup (higher=better) | <span style="color:#2e7d32">2.52x ✓</span> | <span style="color:#2e7d32">15.20x ✓</span> | <span style="color:#2e7d32">9.36x ✓</span> | <span style="color:#2e7d32">1.19x ✓</span> | <span style="color:#2e7d32">5.01x ✓</span> |

=== "p99"

    | | SQLite | MySQL | PostgreSQL | Oracle | SQL Server |
    |---|---:|---:|---:|---:|---:|
    | Stormify-native | 625μs | 1750μs | 3623μs | 18.2ms | 1907μs |
    | Hibernate (JPA) | 1524μs | 27.9ms | 29.9ms | 20.8ms | 9022μs |
    | Speedup (higher=better) | <span style="color:#2e7d32">2.44x ✓</span> | <span style="color:#2e7d32">15.93x ✓</span> | <span style="color:#2e7d32">8.26x ✓</span> | <span style="color:#2e7d32">1.14x ✓</span> | <span style="color:#2e7d32">4.73x ✓</span> |


### update_bulk_sql

_Single UPDATE touching ~100 rows, raw SQL._

```sql
BEGIN;
  UPDATE bench_child SET status = ? WHERE id BETWEEN ? AND ?;
COMMIT;
```

=== "p50"

    | | SQLite | MySQL | PostgreSQL | Oracle | SQL Server |
    |---|---:|---:|---:|---:|---:|
    | Stormify-native | 90μs | 1294μs | 1786μs | 608μs | 1439μs |
    | Hibernate (JPA) | 703μs | 1863μs | 1297μs | 1235μs | 1561μs |
    | Speedup (higher=better) | <span style="color:#2e7d32">7.83x ✓</span> | <span style="color:#2e7d32">1.44x ✓</span> | <span style="color:#c62828">0.73x ✗</span> | <span style="color:#2e7d32">2.03x ✓</span> | 1.09x ~ |

=== "p95"

    | | SQLite | MySQL | PostgreSQL | Oracle | SQL Server |
    |---|---:|---:|---:|---:|---:|
    | Stormify-native | 106μs | 1514μs | 3067μs | 870μs | 2248μs |
    | Hibernate (JPA) | 991μs | 2459μs | 4055μs | 1860μs | 2194μs |
    | Speedup (higher=better) | <span style="color:#2e7d32">9.36x ✓</span> | <span style="color:#2e7d32">1.62x ✓</span> | <span style="color:#2e7d32">1.32x ✓</span> | <span style="color:#2e7d32">2.14x ✓</span> | 0.98x ~ |

=== "p99"

    | | SQLite | MySQL | PostgreSQL | Oracle | SQL Server |
    |---|---:|---:|---:|---:|---:|
    | Stormify-native | 136μs | 1589μs | 3378μs | 935μs | 2312μs |
    | Hibernate (JPA) | 1099μs | 2792μs | 22.7ms | 2192μs | 2315μs |
    | Speedup (higher=better) | <span style="color:#2e7d32">8.10x ✓</span> | <span style="color:#2e7d32">1.76x ✓</span> | <span style="color:#2e7d32">6.73x ✓</span> | <span style="color:#2e7d32">2.35x ✓</span> | 1.00x ~ |


### update_bulk_orm

_ORM batch update — load 100 children, mutate, flush._

```sql
BEGIN;
  SELECT * FROM bench_child WHERE id BETWEEN ? AND ?;
  -- driver-batched per-row UPDATE (mutate, flush)
  UPDATE bench_child SET status = ? WHERE id = ?;   -- ×100
COMMIT;
```

=== "p50"

    | | SQLite | MySQL | PostgreSQL | Oracle | SQL Server |
    |---|---:|---:|---:|---:|---:|
    | Stormify-native | 509μs | 4698μs | 3757μs | 1271μs | 13.6ms |
    | Hibernate (JPA) | 1254μs | 7905μs | 4164μs | 9531μs | 6287μs |
    | Speedup (higher=better) | <span style="color:#2e7d32">2.46x ✓</span> | <span style="color:#2e7d32">1.68x ✓</span> | 1.11x ~ | <span style="color:#2e7d32">7.50x ✓</span> | <span style="color:#c62828">0.46x ✗</span> |

=== "p95"

    | | SQLite | MySQL | PostgreSQL | Oracle | SQL Server |
    |---|---:|---:|---:|---:|---:|
    | Stormify-native | 633μs | 6677μs | 6274μs | 1497μs | 32.4ms |
    | Hibernate (JPA) | 1610μs | 24.4ms | 11.6ms | 31.1ms | 7332μs |
    | Speedup (higher=better) | <span style="color:#2e7d32">2.54x ✓</span> | <span style="color:#2e7d32">3.66x ✓</span> | <span style="color:#2e7d32">1.85x ✓</span> | <span style="color:#2e7d32">20.79x ✓</span> | <span style="color:#c62828">0.23x ✗</span> |

=== "p99"

    | | SQLite | MySQL | PostgreSQL | Oracle | SQL Server |
    |---|---:|---:|---:|---:|---:|
    | Stormify-native | 850μs | 8185μs | 6508μs | 1604μs | 37.8ms |
    | Hibernate (JPA) | 1643μs | 43.2ms | 13.6ms | 40.9ms | 7688μs |
    | Speedup (higher=better) | <span style="color:#2e7d32">1.93x ✓</span> | <span style="color:#2e7d32">5.28x ✓</span> | <span style="color:#2e7d32">2.10x ✓</span> | <span style="color:#2e7d32">25.49x ✓</span> | <span style="color:#c62828">0.20x ✗</span> |


### tx_rollback

_BEGIN + UPDATE + ROLLBACK round-trip mechanics._

```sql
BEGIN;
  UPDATE bench_child SET status = ? WHERE id = ?;
ROLLBACK;
```

=== "p50"

    | | SQLite | MySQL | PostgreSQL | Oracle | SQL Server |
    |---|---:|---:|---:|---:|---:|
    | Stormify-native | 33μs | 495μs | 98μs | 374μs | 289μs |
    | Hibernate (JPA) | 199μs | 2015μs | 230μs | 662μs | 356μs |
    | Speedup (higher=better) | <span style="color:#2e7d32">6.04x ✓</span> | <span style="color:#2e7d32">4.07x ✓</span> | <span style="color:#2e7d32">2.34x ✓</span> | <span style="color:#2e7d32">1.77x ✓</span> | <span style="color:#2e7d32">1.23x ✓</span> |

=== "p95"

    | | SQLite | MySQL | PostgreSQL | Oracle | SQL Server |
    |---|---:|---:|---:|---:|---:|
    | Stormify-native | 36μs | 588μs | 108μs | 422μs | 382μs |
    | Hibernate (JPA) | 286μs | 4124μs | 344μs | 816μs | 522μs |
    | Speedup (higher=better) | <span style="color:#2e7d32">8.03x ✓</span> | <span style="color:#2e7d32">7.02x ✓</span> | <span style="color:#2e7d32">3.18x ✓</span> | <span style="color:#2e7d32">1.93x ✓</span> | <span style="color:#2e7d32">1.37x ✓</span> |

=== "p99"

    | | SQLite | MySQL | PostgreSQL | Oracle | SQL Server |
    |---|---:|---:|---:|---:|---:|
    | Stormify-native | 46μs | 693μs | 124μs | 459μs | 450μs |
    | Hibernate (JPA) | 445μs | 4627μs | 398μs | 879μs | 658μs |
    | Speedup (higher=better) | <span style="color:#2e7d32">9.76x ✓</span> | <span style="color:#2e7d32">6.68x ✓</span> | <span style="color:#2e7d32">3.21x ✓</span> | <span style="color:#2e7d32">1.92x ✓</span> | <span style="color:#2e7d32">1.46x ✓</span> |



<!-- /READ_RESULTS -->

## Insert scenarios

Two flavours: bulk inserts measure a single transaction with many rows
(total wall-clock is reported as one `total` row in the CSV), single
inserts measure 1000 separate one-row transactions and report the per-iter
p50.

<!-- INSERT_RESULTS -->
### insert_1000

_1000 children inserted in one transaction._

```sql
BEGIN;
  INSERT INTO bench_child (id, parent_id, status, value, payload, created_at)
    VALUES (?, ?, ?, ?, ?, ?);   -- ×1000, driver-batched
COMMIT;
```

| | SQLite | MySQL | PostgreSQL | Oracle | SQL Server |
|---|---:|---:|---:|---:|---:|
| Stormify-native | 3489μs | 42.4ms | 15.8ms | 33.9ms | 60.9ms |
| Hibernate (JPA) | 110.2ms | 438.5ms | 208.1ms | 281.7ms | 300.1ms |
| Speedup (higher=better) | <span style="color:#2e7d32">31.58x ✓</span> | <span style="color:#2e7d32">10.33x ✓</span> | <span style="color:#2e7d32">13.19x ✓</span> | <span style="color:#2e7d32">8.32x ✓</span> | <span style="color:#2e7d32">4.93x ✓</span> |

### insert_10000

_10 000 children inserted in one transaction, chunked at 5000._

```sql
BEGIN;
  INSERT INTO bench_child (id, parent_id, status, value, payload, created_at)
    VALUES (?, ?, ?, ?, ?, ?);   -- ×10 000, batched
COMMIT;
```

| | SQLite | MySQL | PostgreSQL | Oracle | SQL Server |
|---|---:|---:|---:|---:|---:|
| Stormify-native | 36.2ms | 677.5ms | 122.0ms | 61.5ms | 593.1ms |
| Hibernate (JPA) | 233.6ms | 1758.9ms | 897.1ms | 3105.5ms | 1345.7ms |
| Speedup (higher=better) | <span style="color:#2e7d32">6.44x ✓</span> | <span style="color:#2e7d32">2.60x ✓</span> | <span style="color:#2e7d32">7.35x ✓</span> | <span style="color:#2e7d32">50.49x ✓</span> | <span style="color:#2e7d32">2.27x ✓</span> |

### single_insert_1000

_1000 separate 1-row transactions (per-iter p50)._

```sql
BEGIN;
  INSERT INTO bench_child (id, parent_id, status, value, payload, created_at)
    VALUES (?, ?, ?, ?, ?, ?);
COMMIT;
```

=== "p50"

    | | SQLite | MySQL | PostgreSQL | Oracle | SQL Server |
    |---|---:|---:|---:|---:|---:|
    | Stormify-native | 21μs | 2192μs | 1135μs | 937μs | 751μs |
    | Hibernate (JPA) | 55μs | 1297μs | 1627μs | 1317μs | 1576μs |
    | Speedup (higher=better) | <span style="color:#2e7d32">2.58x ✓</span> | <span style="color:#c62828">0.59x ✗</span> | <span style="color:#2e7d32">1.43x ✓</span> | <span style="color:#2e7d32">1.40x ✓</span> | <span style="color:#2e7d32">2.10x ✓</span> |

=== "p95"

    | | SQLite | MySQL | PostgreSQL | Oracle | SQL Server |
    |---|---:|---:|---:|---:|---:|
    | Stormify-native | 29μs | 3615μs | 2226μs | 1687μs | 2258μs |
    | Hibernate (JPA) | 90μs | 1642μs | 3002μs | 2311μs | 3324μs |
    | Speedup (higher=better) | <span style="color:#2e7d32">3.07x ✓</span> | <span style="color:#c62828">0.45x ✗</span> | <span style="color:#2e7d32">1.35x ✓</span> | <span style="color:#2e7d32">1.37x ✓</span> | <span style="color:#2e7d32">1.47x ✓</span> |

=== "p99"

    | | SQLite | MySQL | PostgreSQL | Oracle | SQL Server |
    |---|---:|---:|---:|---:|---:|
    | Stormify-native | 40μs | 4157μs | 2664μs | 2053μs | 3147μs |
    | Hibernate (JPA) | 141μs | 2195μs | 3574μs | 3019μs | 4568μs |
    | Speedup (higher=better) | <span style="color:#2e7d32">3.50x ✓</span> | <span style="color:#c62828">0.53x ✗</span> | <span style="color:#2e7d32">1.34x ✓</span> | <span style="color:#2e7d32">1.47x ✓</span> | <span style="color:#2e7d32">1.45x ✓</span> |



<!-- /INSERT_RESULTS -->

## Caveats and why native can lose to JDBC

Stormify-native ships ahead on most scenarios, but not all. The points
below cover both the measurement caveats and the structural reasons the
native side can fall behind a JIT-warmed JDBC driver. Specific scenarios
that visibly hit one or more of these in the latest run are flagged with
`✗` in the tables above.

* **Single-run variance.** The bench runs on a normal desktop, not
  isolated hardware. Run-to-run variance is typically ~10% on tight
  scenarios and can spike to 50%+ on heavier write paths under disk
  contention. On scenarios that finish in tens of microseconds, a
  single OS jitter spike is enough to flip the speedup, so single-run
  reports below 1.0× in those rows usually recover above 1.0× under
  multi-run aggregation — see the `p95` tab and the HTML report.
* **JPA tuning held neutral.** Hibernate 6 runs with no second-level
  cache, no query cache, and `useLocalSessionState` /
  `useLocalTransactionState` left at mysql-connector-j defaults. Tuning
  either side further would skew the comparison.
* **Per-cell hydration cost.** Every column read crosses the
  Kotlin/Native ↔ C boundary (≈ 50 ns) and `toKString()` copies bytes
  for every text column; JDBC drivers stay inside the JVM and reuse
  the receive buffer between rows. Both costs scale linearly with row
  count and column count, so the heaviest reads are where the gap is
  most visible.
* **AOT vs JIT compilation.** Kotlin/Native is LLVM-AOT, no profile-
  guided inlining. JDBC drivers benefit from runtime specialisation
  once the JVM has warmed up; the AOT output for `when` blocks and
  setter dispatch can be slower than the equivalent JIT'd code.
* **No protocol-level pipelining in some C client libraries.** Several
  request/response C client libraries don't expose a way to pack
  multiple commands per TCP write. The matching JDBC drivers speak the
  wire protocol directly and can — a structural advantage we can't
  match without bypassing the C library.
