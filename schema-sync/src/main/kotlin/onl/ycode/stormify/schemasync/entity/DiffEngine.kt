package onl.ycode.stormify.schemasync.entity

import onl.ycode.stormify.schemasync.db.normalizeDbDefault
import onl.ycode.stormify.schemasync.db.normalizeKotlinLiteral
import onl.ycode.stormify.schemasync.model.ColumnRef
import onl.ycode.stormify.schemasync.model.NamingPolicy
import onl.ycode.stormify.schemasync.model.TableStatus
import onl.ycode.stormify.schemasync.model.TypeFamily

/**
 * Pairs Kotlin entities with DB tables by table key and computes per-column
 * deltas. The slot identity is the lower-cased last-segment of the table
 * key — schema is treated as opaque qualification. A slot can have multiple
 * entity claims (multi-view, refactor leftovers, synonym aliases); the diff
 * is the *union* of all entity fields, with the primary entity (per the
 * tiebreaker rule) listed first.
 */
object DiffEngine {

    fun diff(
        entities: List<KotlinEntity>,
        dbColumnsByTable: Map<String, List<ColumnRef>>,
        dbTableKeys: List<String>,
        policy: NamingPolicy = NamingPolicy.LOWER_CASE_WITH_UNDERSCORES,
    ): List<TableDiff> {
        val entitiesBySegment: Map<String, List<KotlinEntity>> =
            entities.groupBy { segment(it.tableKey) }
        val dbColumnsBySegment = dbColumnsByTable.mapKeys { segment(it.key) }
        val dbKeysBySegment = dbTableKeys.associateBy { segment(it) }
        val allSegments = (entitiesBySegment.keys + dbKeysBySegment.keys).distinct().sorted()
        return allSegments.map { seg ->
            val claims = entitiesBySegment[seg].orEmpty()
            val displayKey = dbKeysBySegment[seg] ?: claims.firstOrNull()?.tableKey ?: seg
            val sorted = sortByTiebreaker(claims, setOf(seg), policy)
            diffTable(displayKey, sorted, dbColumnsBySegment[seg].orEmpty())
        }
    }

    /** Lower-cased last-segment of a `schema.table` key. The slot identity. */
    private fun segment(key: String): String = key.substringAfterLast('.').lowercase()

    /**
     * Sort entities so the primary (the recipient for new INSERT splices)
     * is first. Pools, in priority order:
     *   0) entities without an explicit `@DbTable(name=…)` override
     *   A) explicit name, snake'd className starts with any slot alias
     *   B) explicit name, snake'd className contains any slot alias
     *   C) everything else
     * Within a pool: shortest className simple-name, lex-tiebreaker.
     */
    private fun sortByTiebreaker(
        entities: List<KotlinEntity>,
        aliases: Set<String>,
        policy: NamingPolicy,
    ): List<KotlinEntity> {
        if (entities.size <= 1) return entities
        return entities.sortedWith(
            compareBy(
                { poolFor(it, aliases, policy) },
                { it.className.substringAfterLast('.').length },
                { it.className.substringAfterLast('.') },
            ),
        )
    }

    private fun poolFor(entity: KotlinEntity, aliases: Set<String>, policy: NamingPolicy): Int {
        if (!entity.explicitTableName) return 0
        val name = policy.fromKotlin(entity.className.substringAfterLast('.')).lowercase()
        if (aliases.any { name.startsWith(it) }) return 1
        if (aliases.any { it in name }) return 2
        return 3
    }

    private fun diffTable(
        tableKey: String,
        entities: List<KotlinEntity>,
        dbColumns: List<ColumnRef>,
    ): TableDiff {
        if (entities.isEmpty()) {
            val deltas = dbColumns.map { ColumnDelta(it.name, ColumnDelta.Kind.DB_ONLY, null, it) }
            return TableDiff(tableKey, emptyList(), dbColumns, TableStatus.DB_ONLY, deltas)
        }

        // Union of all fields across claimants, keyed by lower-cased column name.
        // Primary's field wins when multiple entities map to the same column.
        val fieldByLower: Map<String, EntityField> = buildMap {
            // Iterate in tiebreaker order; putIfAbsent so primary wins.
            for (entity in entities) {
                for (field in entity.fields) {
                    putIfAbsent(field.column.lowercase(), field)
                }
            }
        }
        val dbByLower = dbColumns.associateBy { it.name.lowercase() }

        if (dbColumns.isEmpty()) {
            val deltas = fieldByLower.values.map { f ->
                ColumnDelta(f.column, ColumnDelta.Kind.ENTITY_ONLY, f, null)
            }
            return TableDiff(tableKey, entities, dbColumns, TableStatus.ENTITY_ONLY, deltas)
        }

        val allLower = (dbByLower.keys + fieldByLower.keys).distinct()
        val deltas = allLower.map { lower ->
            val field = fieldByLower[lower]
            val col = dbByLower[lower]
            val name = field?.column ?: col?.name ?: lower
            when {
                field != null && col != null -> {
                    val reason = mismatchReason(field, col)
                    val defaultMismatch = defaultMismatch(field, col)
                    if (reason != null) ColumnDelta(name, ColumnDelta.Kind.TYPE_MISMATCH, field, col, reason, defaultMismatch)
                    else ColumnDelta(name, ColumnDelta.Kind.SYNCED, field, col, null, defaultMismatch)
                }
                field != null -> ColumnDelta(name, ColumnDelta.Kind.ENTITY_ONLY, field, null)
                else -> ColumnDelta(name, ColumnDelta.Kind.DB_ONLY, null, col)
            }
        }
        val structurallySynced = deltas.all { it.kind == ColumnDelta.Kind.SYNCED }
        val anyDefaultMismatch = deltas.any { it.defaultMismatch != null }
        val status = if (structurallySynced && !anyDefaultMismatch) {
            TableStatus.SYNCED
        } else {
            TableStatus.DIFF
        }
        return TableDiff(tableKey, entities, dbColumns, status, deltas)
    }

    /**
     * Returns null when the entity field and DB column are compatible enough
     * to treat as SYNCED, or a short reason string when they diverge in a way
     * the user should see.
     *
     * Resolution differences within the same category (Int vs Long, VARCHAR(50)
     * vs VARCHAR(100), nullable vs not-null, …) are NOT mismatches —
     * schema-sync's reduced scope does not emit ALTER COLUMN TYPE/NULLABILITY,
     * so such rows are treated as SYNCED rather than warning-only.
     */
    private fun mismatchReason(field: EntityField, col: ColumnRef): String? {
        val fFam = field.family
        val cFam = col.family
        if (fFam == null || cFam == null) return null
        if (fFam == cFam) return capacityReason(field, col)
        if (isBigDecimal(field.type) && cFam in NUMERIC_FAMILIES) return null
        if (fFam == TypeFamily.BOOLEAN && isLegacyBoolean(col)) return null
        return "type"
    }

    private fun isLegacyBoolean(col: ColumnRef): Boolean {
        val size = col.precision ?: return false
        return when (col.family) {
            TypeFamily.INTEGRAL -> size <= 1
            TypeFamily.TEXT -> size <= 1
            else -> false
        }
    }

    /** Maximum decimal digits each integral Kotlin type can hold (digits of
     *  the max representable value). */
    private val KOTLIN_DIGITS: Map<String, Int> = mapOf(
        "Byte" to 3,
        "Short" to 5,
        "Int" to 10, "Integer" to 10,
        "Long" to 19,
        "BigInteger" to Int.MAX_VALUE,
    )

    /** Above this the driver is almost certainly reporting a bogus COLUMN_SIZE
     *  (SQLite returns 2_000_000_000 for plain INTEGER). 38 is the SQL standard
     *  upper bound for DECIMAL precision. */
    private const val MAX_REASONABLE_PRECISION = 38

    private fun capacityReason(field: EntityField, col: ColumnRef): String? {
        val simple = field.type.substringBefore('<').substringAfterLast('.').trim()
        val ktDigits = KOTLIN_DIGITS[simple] ?: return null
        val dbDigits = col.precision?.takeIf { it in 1..MAX_REASONABLE_PRECISION } ?: return null
        return if (dbDigits > ktDigits)
            "capacity (${field.type} holds $ktDigits digits, column needs $dbDigits)"
        else null
    }

    /** Compares the entity-side Kotlin literal with the DB-side raw default
     *  after normalization. Returns null when both are absent or compare
     *  equal; otherwise returns the (entity, db) pair as the user should see
     *  it on the diff line. */
    private fun defaultMismatch(field: EntityField, col: ColumnRef): Pair<String?, String?>? {
        val ent = normalizeKotlinLiteral(field.defaultLiteral)
        val db = normalizeDbDefault(col.defaultValue)
        if (ent == null && db == null) return null
        if (ent != null && db != null && ent == db) return null
        return field.defaultLiteral to col.defaultValue
    }

    private val NUMERIC_FAMILIES = setOf(TypeFamily.INTEGRAL, TypeFamily.DECIMAL)

    private fun isBigDecimal(type: String): Boolean =
        type.substringBefore('<').substringAfterLast('.').trim() == "BigDecimal"
}
