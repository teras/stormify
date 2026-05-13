package onl.ycode.stormify.schemasync.db

import onl.ycode.stormify.schemasync.classifier.ClassificationCache
import onl.ycode.stormify.schemasync.entity.ColumnDelta
import onl.ycode.stormify.schemasync.entity.EntityField
import onl.ycode.stormify.schemasync.entity.KotlinEntity
import onl.ycode.stormify.schemasync.entity.TableDiff
import onl.ycode.stormify.schemasync.model.DefaultsProfile
import onl.ycode.stormify.schemasync.model.SlotCategory
import onl.ycode.stormify.schemasync.model.SlotProfile
import onl.ycode.stormify.schemasync.model.TableStatus
import java.time.LocalDate

/**
 * Produces a `migration.sql` file from the diff result. Generates additive-only
 * statements with dialect-aware syntax: ALTER TABLE … ADD [COLUMN] for ENTITY_ONLY
 * columns inside an existing table, CREATE TABLE for ENTITY_ONLY tables.
 *
 * Per-dialect quirks handled here:
 *  - MSSQL drops the `COLUMN` keyword in `ALTER TABLE … ADD`.
 *  - SQLite cannot ADD a `NOT NULL` column without a `DEFAULT`; we emit a TODO
 *    instead of broken SQL when no default can be inferred.
 *  - SQLite/MSSQL templates that already include `PRIMARY KEY` aren't duplicated.
 */
object MigrationGenerator {

    /** [acceptColumn] gates which per-column SQL lines are emitted. */
    fun render(
        diffs: List<TableDiff>,
        profile: SlotProfile,
        defaults: DefaultsProfile,
        cache: ClassificationCache,
        dialect: Dialect = Dialect.GENERIC,
        entities: List<KotlinEntity> = emptyList(),
        acceptColumn: (tableKey: String, columnName: String) -> Boolean = { _, _ -> true },
    ): RenderResult {
        val (sb, stats) = buildSql(diffs, profile, defaults, cache, dialect, entities, acceptColumn)
        return RenderResult(sb.toString(), stats)
    }

    data class RenderResult(val sql: String, val stats: Stats)

    /** Public entry point for the live diff pane. Returns just the actionable
     *  ALTER TABLE statements for [diff], no header comments and no stats. */
    fun alterStatementsFor(
        diff: TableDiff,
        profile: SlotProfile,
        defaults: DefaultsProfile,
        cache: ClassificationCache,
        dialect: Dialect = Dialect.GENERIC,
        entities: List<KotlinEntity> = emptyList(),
        acceptColumn: (columnName: String) -> Boolean = { true },
    ): List<String> = alterStatementsForInternal(
        diff, profile, defaults.mergedFor(dialect.tomlKey), cache, dialect, entities, acceptColumn,
    ).statements

    /** Public entry point for the live diff pane. Returns the full CREATE TABLE
     *  block as a list of lines, or empty when nothing classifiable. */
    fun createTableStatementFor(
        diff: TableDiff,
        profile: SlotProfile,
        defaults: DefaultsProfile,
        cache: ClassificationCache,
        dialect: Dialect = Dialect.GENERIC,
        entities: List<KotlinEntity> = emptyList(),
        acceptColumn: (columnName: String) -> Boolean = { true },
    ): List<String> = createTableStatementForInternal(
        diff, profile, defaults.mergedFor(dialect.tomlKey), cache, dialect, entities, acceptColumn,
    ).statements

    private data class AlterResult(val statements: List<String>, val unclassified: Int)
    private data class CreateResult(val statements: List<String>, val unclassified: Int, val skippedFields: Int)

    /** Resolves `var owner: User` (FK) → ` REFERENCES user(id)` clause. */
    private fun fkClauseFor(
        field: EntityField,
        entityByClassName: Map<String, KotlinEntity>,
        dialect: Dialect,
    ): String? {
        val ref = field.referencedEntity ?: return null
        val target = entityByClassName[ref] ?: return null
        val pk = target.fields.firstOrNull { it.primaryKey } ?: return null
        return " REFERENCES ${quoteIdent(target.tableKey, dialect)}(${quoteIdent(pk.column, dialect)})"
    }

    private fun classNameIndex(entities: List<KotlinEntity>): Map<String, KotlinEntity> =
        entities.associateBy { it.className } +
            entities.associateBy { it.className.substringAfterLast('.') }

    private fun createTableStatementForInternal(
        diff: TableDiff,
        profile: SlotProfile,
        effectiveDefaults: DefaultsProfile,
        cache: ClassificationCache,
        dialect: Dialect,
        entities: List<KotlinEntity>,
        acceptColumn: (columnName: String) -> Boolean,
    ): CreateResult {
        if (diff.status != TableStatus.ENTITY_ONLY) return CreateResult(emptyList(), 0, 0)
        if (diff.entities.isEmpty()) return CreateResult(emptyList(), 0, 0)
        val byName = classNameIndex(entities)
        var unclassified = 0
        // Union of all entity fields across the slot, primary-first so its
        // spelling/type wins on duplicate column names.
        val unionFields = diff.entities
            .asSequence()
            .flatMap { it.fields.asSequence() }
            .distinctBy { it.column.lowercase() }
            .filter { acceptColumn(it.column) }
            .toList()
        val cols = unionFields.mapNotNull { field ->
            val ddl = ddlForField(field, profile, effectiveDefaults, cache, diff.tableKey)
            if (ddl == null) { unclassified++; return@mapNotNull null }
            val nullClause = if (field.nullable) "" else " NOT NULL"
            val ddlHasPk = ddl.contains("PRIMARY KEY", ignoreCase = true)
            val pkClause = if (field.primaryKey && !ddlHasPk) " PRIMARY KEY" else ""
            val defaultClause = defaultClauseFor(field, dialect, effectiveDefaults)?.let { " DEFAULT $it" } ?: ""
            val fkClause = fkClauseFor(field, byName, dialect) ?: ""
            "    ${quoteIdent(field.column, dialect)} $ddl$defaultClause$nullClause$pkClause$fkClause"
        }
        if (cols.isEmpty()) return CreateResult(emptyList(), unclassified, 0)
        val out = mutableListOf<String>()
        out += "CREATE TABLE ${quoteIdent(diff.tableKey, dialect)} ("
        cols.forEachIndexed { i, line ->
            out += if (i < cols.size - 1) "$line," else line
        }
        out += ");"
        return CreateResult(out, unclassified, unionFields.size - cols.size)
    }

    /** Shared core used by both [alterStatementsFor] (live diff pane) and
     *  [buildSql] (the on-disk migration writer) so they emit byte-identical
     *  SQL and tally unclassified columns the same way. */
    private fun alterStatementsForInternal(
        diff: TableDiff,
        profile: SlotProfile,
        effectiveDefaults: DefaultsProfile,
        cache: ClassificationCache,
        dialect: Dialect,
        entities: List<KotlinEntity>,
        acceptColumn: (columnName: String) -> Boolean,
    ): AlterResult {
        if (diff.status != TableStatus.DIFF) return AlterResult(emptyList(), 0)
        val byName = classNameIndex(entities)
        val out = mutableListOf<String>()
        var unclassified = 0
        for (delta in diff.columnDeltas) {
            if (delta.kind != ColumnDelta.Kind.ENTITY_ONLY) continue
            if (!acceptColumn(delta.name)) continue
            val field = delta.entityField ?: continue
            val ddl = ddlForField(field, profile, effectiveDefaults, cache, diff.tableKey)
            if (ddl == null) { unclassified++; continue }
            if (!dialect.supportsAlterAddPrimaryKey && ddl.contains("PRIMARY KEY", ignoreCase = true)) {
                unclassified++; continue
            }
            val defaultClause = defaultClauseFor(field, dialect, effectiveDefaults)
            if (!dialect.supportsAlterAddNotNullWithoutDefault && !field.nullable && defaultClause == null) {
                unclassified++; continue
            }
            out += alterAdd(dialect, diff.tableKey, field.column, ddl, field.nullable, defaultClause,
                fkClauseFor(field, byName, dialect))
        }
        return AlterResult(out, unclassified)
    }

    private fun buildSql(
        diffs: List<TableDiff>,
        profile: SlotProfile,
        defaults: DefaultsProfile,
        cache: ClassificationCache,
        dialect: Dialect,
        entities: List<KotlinEntity>,
        acceptColumn: (tableKey: String, columnName: String) -> Boolean,
    ): Pair<StringBuilder, Stats> {
        val effectiveDefaults = defaults.mergedFor(dialect.tomlKey)
        val sb = StringBuilder()
        var addedColumns = 0
        var createdTables = 0
        var unclassifiedFields = 0

        sb.appendLine("-- schema-sync migration")
        sb.appendLine("-- Generated ${LocalDate.now()} for ${dialect.tomlKey}")
        sb.appendLine()

        for (diff in diffs) {
            when (diff.status) {
                TableStatus.SYNCED -> {
                    // skip silently
                }
                TableStatus.DIFF -> {
                    val result = alterStatementsForInternal(
                        diff, profile, effectiveDefaults, cache, dialect, entities,
                        { col -> acceptColumn(diff.tableKey, col) },
                    )
                    unclassifiedFields += result.unclassified
                    if (result.statements.isNotEmpty()) {
                        sb.appendLine("-- ── ${diff.tableKey} ──")
                        for (stmt in result.statements) sb.appendLine(stmt)
                        addedColumns += result.statements.size
                        sb.appendLine()
                    }
                }
                TableStatus.ENTITY_ONLY -> {
                    val entity = diff.primary!!
                    val result = createTableStatementForInternal(
                        diff, profile, effectiveDefaults, cache, dialect, entities,
                        { col -> acceptColumn(diff.tableKey, col) },
                    )
                    unclassifiedFields += result.unclassified
                    if (result.statements.isNotEmpty()) {
                        sb.appendLine("-- ── CREATE ${diff.tableKey} (entity ${entity.className}) ──")
                        for (line in result.statements) sb.appendLine(line)
                        if (result.skippedFields > 0) {
                            sb.appendLine(
                                "-- WARNING: ${result.skippedFields} fields skipped due to missing slot assignment",
                            )
                        }
                        sb.appendLine()
                        createdTables++
                    }
                }
                TableStatus.DB_ONLY -> {
                    // No SQL emitted: DB-only tables are handled entity-side.
                }
            }
        }

        if (addedColumns == 0 && createdTables == 0 && unclassifiedFields == 0) {
            sb.appendLine("-- (everything is in sync, nothing to do)")
        }

        return sb to Stats(addedColumns, createdTables, unclassifiedFields)
    }

    /** ALTER TABLE … ADD [COLUMN] … with dialect-correct syntax. */
    private fun alterAdd(
        dialect: Dialect,
        table: String,
        column: String,
        ddl: String,
        nullable: Boolean,
        defaultLiteral: String?,
        fkClause: String? = null,
    ): String {
        val nullClause = if (nullable) "" else " NOT NULL"
        val defaultClause = defaultLiteral?.let { " DEFAULT $it" } ?: ""
        val fk = fkClause ?: ""
        val tbl = quoteIdent(table, dialect)
        val col = quoteIdent(column, dialect)
        return "ALTER TABLE $tbl ${dialect.alterAddKeyword} $col $ddl$defaultClause$nullClause$fk;"
    }

    /** Reserved words common across mainstream SQL dialects. Quoted on output. */
    private val RESERVED_IDENTIFIERS = setOf(
        "order", "key", "user", "group", "select", "from", "where", "insert",
        "update", "delete", "table", "column", "index", "primary", "unique",
        "value", "default", "null", "not", "type", "role", "level", "status",
        "view", "case", "when", "then", "else", "and", "or", "exists", "between",
        "like", "having", "join", "left", "right", "inner", "outer", "cross",
        "natural", "all", "any", "some", "with", "limit", "offset", "lock",
        "share", "rank", "row", "rows", "schema", "session", "system", "check",
        "constraint", "references", "foreign", "trigger", "procedure", "function",
        "language", "current", "timestamp", "date", "time", "interval",
    )

    /** Quote each segment of a possibly schema-qualified identifier (`schema.table`). */
    private fun quoteIdent(name: String, dialect: Dialect): String {
        if ('.' !in name) return quoteSegment(name, dialect)
        return name.split('.').joinToString(".") { quoteSegment(it, dialect) }
    }

    private fun quoteSegment(name: String, dialect: Dialect): String {
        if (name.lowercase() !in RESERVED_IDENTIFIERS) return name
        return dialect.quoteIdentifier(name)
    }

    /**
     * Resolves the SQL DEFAULT literal for [field], or null when no default applies.
     * Priority: explicit literal initializer in the entity > per-type auto-default
     * (only when the column would otherwise be NOT NULL without one).
     */
    private fun defaultClauseFor(
        field: onl.ycode.stormify.schemasync.entity.EntityField,
        dialect: Dialect,
        defaults: DefaultsProfile,
    ): String? {
        // Auto-increment PKs manage their own value; never emit DEFAULT.
        if (field.primaryKey && field.autoIncrement) return null
        if (field.defaultLiteral != null) return renderLiteral(field.defaultLiteral, field.type, dialect)
        if (field.nullable) return null
        return autoDefaultFor(field.type, dialect, defaults)?.takeIf { it.isNotBlank() }
    }

    private fun renderLiteral(raw: String, fieldType: String, dialect: Dialect): String? {
        val t = raw.trim()
        if (t == "true" || t == "false") return dialect.booleanLiteral(t == "true")
        if (t.startsWith("\"") && t.endsWith("\"")) {
            val inner = t.substring(1, t.length - 1).replace("'", "''")
            return "'$inner'"
        }
        // Numeric: strip Kotlin suffix L/F/f/D/d
        val numeric = Regex("""^([-+]?\d[\d_]*(?:\.\d[\d_]*)?)[LlFfDd]?$""")
        numeric.matchEntire(t)?.let { return it.groupValues[1].replace("_", "") }
        return null
    }

    private fun autoDefaultFor(type: String, dialect: Dialect, d: DefaultsProfile): String? {
        val base = type.removeSuffix("?").trim()
        return when (base) {
            "Boolean", "kotlin.Boolean" -> d.autoDefaultBoolean
            "LocalDate", "java.time.LocalDate", "java.sql.Date" -> d.autoDefaultLocalDate
            "LocalTime", "java.time.LocalTime", "java.sql.Time" -> d.autoDefaultLocalTime
            "LocalDateTime", "java.time.LocalDateTime", "java.sql.Timestamp" -> d.autoDefaultLocalDateTime
            "Instant", "java.time.Instant", "OffsetDateTime", "java.time.OffsetDateTime",
            "ZonedDateTime", "java.time.ZonedDateTime" -> d.autoDefaultInstant
            "UUID", "java.util.UUID" -> d.autoDefaultUuid
            "ByteArray", "kotlin.ByteArray", "CharArray", "kotlin.CharArray" -> d.autoDefaultBytes
            "String", "kotlin.String", "CharSequence" -> d.autoDefaultText
            "Float", "Double", "BigDecimal",
            "kotlin.Float", "kotlin.Double", "java.math.BigDecimal" -> d.autoDefaultDecimal
            "Int", "Integer", "Long", "Short", "Byte",
            "kotlin.Int", "kotlin.Long", "kotlin.Short", "kotlin.Byte",
            "BigInteger", "java.math.BigInteger" -> d.autoDefaultInt
            else -> null
        }
    }

    private fun ddlForField(
        field: EntityField,
        profile: SlotProfile,
        defaults: DefaultsProfile,
        cache: ClassificationCache,
        tableKey: String,
    ): String? {
        if (field.primaryKey) {
            val pk = pkDdl(field, defaults)
            if (pk != null) return pk
        }
        val deterministic = deterministicDdl(field.type, defaults)
        if (deterministic != null) return deterministic
        // FK columns mirror the target entity's non-auto PK DDL, so the user
        // doesn't have to slot-assign every reference.
        if (field.referencedEntity != null) {
            val base = field.type.removeSuffix("?").trim()
            return when (base) {
                "Long", "kotlin.Long" -> defaults.pkLongDdl
                "Int", "Integer", "kotlin.Int" -> defaults.pkIntDdl
                else -> null
            } ?: cachedSlotDdl(profile, cache, "$tableKey.${field.column}")
        }
        return cachedSlotDdl(profile, cache, "$tableKey.${field.column}")
    }

    private fun cachedSlotDdl(profile: SlotProfile, cache: ClassificationCache, key: String): String? {
        val (cat, slot) = cache.get(key) ?: return null
        return ddlForSlot(profile, cat, slot)
    }

    private fun pkDdl(field: EntityField, defaults: DefaultsProfile): String? {
        val base = field.type.removeSuffix("?").trim()
        val isInt = base in setOf("Int", "Integer", "kotlin.Int")
        val isLong = base in setOf("Long", "kotlin.Long")
        return when {
            isInt && field.autoIncrement -> defaults.pkAutoIntDdl
            isLong && field.autoIncrement -> defaults.pkAutoLongDdl
            isInt -> defaults.pkIntDdl
            isLong -> defaults.pkLongDdl
            else -> null
        }
    }

    private fun deterministicDdl(type: String, defaults: DefaultsProfile): String? = when (type.removeSuffix("?").trim()) {
        "Boolean", "kotlin.Boolean" -> defaults.booleanDdl
        "LocalDate", "java.time.LocalDate", "java.sql.Date" -> defaults.localDateDdl
        "LocalTime", "java.time.LocalTime", "java.sql.Time", "Time" -> defaults.localTimeDdl
        "OffsetTime", "java.time.OffsetTime" -> defaults.offsetTimeDdl
        "LocalDateTime", "java.time.LocalDateTime", "java.sql.Timestamp", "Timestamp" -> defaults.localDateTimeDdl
        "Instant", "java.time.Instant", "OffsetDateTime", "java.time.OffsetDateTime",
        "ZonedDateTime", "java.time.ZonedDateTime",
        // Bare `Date` is most commonly java.util.Date (Hibernate / legacy JPA),
        // which carries both date and time → map to the instant DDL.
        "Date", "java.util.Date" -> defaults.instantDdl
        "ByteArray", "kotlin.ByteArray" -> defaults.byteArrayDdl
        "CharArray", "kotlin.CharArray" -> defaults.charArrayDdl
        "UUID", "java.util.UUID", "Uuid", "kotlin.uuid.Uuid" -> defaults.uuidDdl
        else -> null
    }

    private fun ddlForSlot(profile: SlotProfile, category: SlotCategory, slot: String): String? = when (category) {
        SlotCategory.TEXT -> profile.text.firstOrNull { it.name == slot }?.ddl
        SlotCategory.INTEGRAL -> profile.integral.firstOrNull { it.name == slot }?.ddl
        SlotCategory.DECIMAL -> profile.decimal.firstOrNull { it.name == slot }?.ddl
    }

    data class Stats(
        val addedColumns: Int,
        val createdTables: Int,
        val unclassifiedFields: Int,
    )
}
