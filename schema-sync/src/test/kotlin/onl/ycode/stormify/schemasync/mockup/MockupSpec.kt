package onl.ycode.stormify.schemasync.mockup

/**
 * Reproducible synthetic universe used to stress-test schema-sync's diff
 * pipeline. Built from a fixed RNG seed so a fresh database can be
 * recreated bit-for-bit. The spec is dialect-agnostic — separate
 * renderers turn it into DDL + INSERT batches and into Kotlin entity
 * source files.
 *
 * The 1000-entity universe is laid out so every TUI filter combination
 * produces results: in-sync, missing-DB-field, missing-Kotlin-field,
 * type-conflict, default-conflict — including pairs, triples, and the
 * full quartet. Some entities also target views or synonyms instead of
 * base tables.
 */
data class MockupSpec(
    val tables: List<TableSpec>,
    val views: List<ViewSpec>,
    val synonyms: List<SynonymSpec>,
    val entities: List<EntitySpec>,
    val rowsPerTable: Int,
    val shape: MockupShape,
)

/** Cardinality profile. [MockupShape.FULL] is the user-facing default;
 *  [MockupShape.SMOKE] is a tiny variant the unit test exercises. */
data class MockupShape(
    val baseTables: Int,
    val views: Int,
    val synonyms: Int,
    val entities: Int,
    val sharedTables: Int,        // base tables targeted by 2 entities (1-2)
    val phantomEntities: Int,     // entities pointing to non-existent tables
    val viewMappedEntities: Int,  // entities whose @DbTable name is a view
    val synonymMappedEntities: Int, // entities whose @DbTable name is a synonym
    val syncedEntities: Int,      // entities that match DB exactly
    val singleIssueEntities: Int, // exactly 1 divergence flag
    val doubleIssueEntities: Int, // exactly 2 flags
    val tripleIssueEntities: Int, // exactly 3 flags
    val quadIssueEntities: Int,   // all 4 flags
) {
    /** Solo entities targeting base tables (1-1) = total − phantom −
     *  paired − view-mapped − synonym-mapped. */
    val soloBaseEntities: Int =
        entities - phantomEntities - sharedTables * 2 - viewMappedEntities - synonymMappedEntities

    /** Sum of every divergence-bucket budget; must add up to entities − phantom. */
    val nonPhantomTotal: Int = entities - phantomEntities

    init {
        require(soloBaseEntities >= 0) {
            "phantom + 2×shared + view-mapped + synonym-mapped exceeds total entities"
        }
        require(baseTables >= sharedTables + soloBaseEntities) {
            "baseTables ($baseTables) < sharedTables ($sharedTables) + soloBaseEntities ($soloBaseEntities)"
        }
        require(views >= viewMappedEntities) { "views < viewMappedEntities" }
        require(synonyms >= synonymMappedEntities) { "synonyms < synonymMappedEntities" }
        val divergenceBudget = syncedEntities + singleIssueEntities + doubleIssueEntities +
            tripleIssueEntities + quadIssueEntities
        require(divergenceBudget == nonPhantomTotal) {
            "divergence budget ($divergenceBudget) ≠ non-phantom total ($nonPhantomTotal)"
        }
    }

    companion object {
        // FULL: 800 base + 100 views + 100 synonyms = 1000 DB objects.
        // 1000 entities = 100 phantom + 200 paired + 50 views + 50 synonyms + 600 solo-base.
        // Of the 900 non-phantom entities: 150 synced + 200 single + 250 double + 200 triple + 100 quad.
        val FULL = MockupShape(
            baseTables = 800, views = 100, synonyms = 100,
            entities = 1000, sharedTables = 100,
            phantomEntities = 100, viewMappedEntities = 50, synonymMappedEntities = 50,
            syncedEntities = 150,
            singleIssueEntities = 200,
            doubleIssueEntities = 250,
            tripleIssueEntities = 200,
            quadIssueEntities = 100,
        )
        // SMOKE: 14 base + 4 views + 4 synonyms; 20 entities = 2 phantom + 4 paired + 2 views + 2 synonyms + 10 solo.
        // 18 non-phantom = 4 synced + 6 single + 4 double + 2 triple + 2 quad.
        val SMOKE = MockupShape(
            baseTables = 14, views = 4, synonyms = 4,
            entities = 20, sharedTables = 2,
            phantomEntities = 2, viewMappedEntities = 2, synonymMappedEntities = 2,
            syncedEntities = 4,
            singleIssueEntities = 6,
            doubleIssueEntities = 4,
            tripleIssueEntities = 2,
            quadIssueEntities = 2,
        )
    }
}

/** A base table. */
data class TableSpec(
    val name: String,
    val columns: List<ColumnSpec>,
)

data class ColumnSpec(
    val name: String,
    val type: ColumnType,
    val nullable: Boolean,
    val default: ColumnDefault?,
)

/** Logical column types. Per-dialect DDL strings live in [MockupSql]. */
enum class ColumnType {
    PK_BIGINT,
    VARCHAR_50, VARCHAR_255, TEXT,
    INT, BIGINT, SMALLINT,
    DECIMAL_10_2, DECIMAL_18_4, DOUBLE_,
    BOOLEAN_,
    DATE_, TIMESTAMP_,
    BLOB_,
}

/** Default-value flavours. Each carries enough info to produce both the
 *  DB DDL form and the matching Kotlin literal — required so SYNCED
 *  entities have inits that compare equal to the DB default. */
sealed class ColumnDefault {
    object Zero : ColumnDefault()
    object EmptyString : ColumnDefault()
    object FalseBool : ColumnDefault()
    object NowTs : ColumnDefault()
    data class IntLit(val value: Int) : ColumnDefault()
    data class StrLit(val value: String) : ColumnDefault()
}

data class ViewSpec(val name: String, val baseTable: String)

/** A synonym (Oracle/MSSQL) or a duplicate table on dialects without
 *  synonym support. The renderer decides which form to emit. */
data class SynonymSpec(val name: String, val baseTable: String)

/** Where in the DB the entity is *trying* to map. PHANTOM means the
 *  name doesn't resolve to anything in the DB. */
enum class TargetKind { TABLE, VIEW, SYNONYM, PHANTOM }

/** A Kotlin entity to emit. The class name doubles as the Kotlin
 *  filename. [tableName] is what the entity claims to map to;
 *  [flags] explains how it differs from the DB-side schema. */
data class EntitySpec(
    val className: String,
    val tableName: String,
    val targetKind: TargetKind,
    val fields: List<EntityField>,
    val flags: DivergenceFlags,
)

data class EntityField(
    val propName: String,
    val columnName: String,
    val kotlinType: String,
    val nullable: Boolean,
    val primaryKey: Boolean = false,
    /** Kotlin source fragment used as the field's `=` initialiser.
     *  Drives schema-sync's default-mismatch detection: when this is
     *  null, the emitter falls back to a type-appropriate zero value
     *  (`""`, `0L`, …); when set, the literal lands verbatim in the
     *  emitted file. */
    val initLiteral: String? = null,
)

/** Independent divergence axes. An entity may carry any subset; an
 *  empty set means the entity is structurally and default-wise in
 *  sync with its DB counterpart. */
data class DivergenceFlags(
    val missingDbField: Boolean = false,    // entity has a column the DB lacks
    val missingKotlinField: Boolean = false,// DB has a column the entity lacks
    val typeConflict: Boolean = false,
    val defaultConflict: Boolean = false,
) {
    val issueCount: Int = listOf(missingDbField, missingKotlinField, typeConflict, defaultConflict).count { it }
    val isSynced: Boolean = issueCount == 0

    companion object {
        val SYNCED = DivergenceFlags()
    }
}
