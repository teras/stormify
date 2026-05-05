# Mockup generator

Synthetic but reproducible "chaos" used to stress-test schema-sync's
diff pipeline. The generator builds a deterministic spec from a fixed
RNG seed, materialises it inside a target database, and emits the
corresponding Kotlin entity files.

## DB-side cardinalities (`MockupShape.FULL`)

| Object       | Count | Notes |
|--------------|------:|-------|
| Base tables  |   800 | 700 entity-mapped (1-1 or 1-2) + 100 unmapped (DB_ONLY). |
| Views        |   100 | 50 entity-mapped + 50 DB_ONLY. |
| Synonyms     |   100 | 50 entity-mapped + 50 DB_ONLY. Real synonyms on Oracle/MSSQL; duplicate tables elsewhere. |
| Rows / table | 1000 | Configurable via `--rows-per-table`. |

## Entity-side breakdown (1000 total)

| Bucket               | Count | What it exercises |
|----------------------|------:|-------------------|
| Phantom              |  100 | `@DbTable(name = "ghost_…")` — table doesn't exist in DB → ENTITY_ONLY status. |
| Paired (1-2)         |  200 | 100 base tables shared by 2 entities each. |
| View-mapped          |   50 | `@DbTable(name = "v_…")` — entity targets a view. |
| Synonym-mapped       |   50 | `@DbTable(name = "syn_…")` — entity targets a synonym. |
| Solo base (1-1)      |  600 | One entity per base table. |

Of the **900 non-phantom** entities, divergence flags are distributed:

| Issue count | Entities | Composition |
|-------------|---------:|-------------|
| 0 (in sync) |  150 | column-for-column match incl. defaults. |
| 1           |  200 | one of {missing-DB-field, missing-Kotlin-field, type-conflict, default-conflict}. |
| 2           |  250 | every `(4 choose 2) = 6` combo populated. |
| 3           |  200 | every `(4 choose 3) = 4` combo populated. |
| 4           |  100 | all four flags lit at once. |

**Every TUI filter combination produces matches.**

## Running

```bash
# Single dialect
./schema-sync/demo/run.sh sqlite        # in-process SQLite
./schema-sync/demo/run.sh postgresql    # needs ./testing/test.sh up postgresql
./schema-sync/demo/run.sh oracle        # real synonyms on Oracle
./schema-sync/demo/run.sh mssql         # real synonyms on MSSQL
# … plus postgresql9 mysql mysql5 mariadb oracle11

# Direct invocation (custom URL/credentials)
gradle :schema-sync:seedMockup --args="\
    --jdbc-url=jdbc:postgresql://localhost:15432/stormify_test \
    --user=stormify --password=Stormify1! \
    --entities-dir=/tmp/mockup-entities \
    --shape=full \
    --rows-per-table=1000"
```

## Reproducibility

`seed=42` is hardcoded in `MockupBuilder`. Re-running with the same
shape produces the same database object names, same column types, same
entity files — byte-for-byte. `MockupBuilderTest` asserts this.

## Dialect compatibility notes

The builder skips DB-default flavours that aren't portable:
- `TEXT/BLOB DEFAULT …` is rejected by MySQL & MSSQL (skipped).
- `DATE DEFAULT CURRENT_TIMESTAMP` is rejected by MySQL/MariaDB (skipped).
- `INSERT … VALUES (…), (…)` is rejected by Oracle (rendered as `INSERT ALL … SELECT 1 FROM dual`).
- Oracle requires `DEFAULT <expr>` *before* `NOT NULL` (rendering enforces this universally).
