package onl.ycode.stormify.schemasync.db

import onl.ycode.stormify.schemasync.config.Assignment
import onl.ycode.stormify.schemasync.entity.ColumnDelta
import onl.ycode.stormify.schemasync.entity.EntityField
import onl.ycode.stormify.schemasync.entity.TableDiff
import onl.ycode.stormify.schemasync.model.DefaultsProfile
import onl.ycode.stormify.schemasync.model.SlotCategory
import onl.ycode.stormify.schemasync.model.SlotProfile
import onl.ycode.stormify.schemasync.model.TableStatus
import java.nio.file.Files
import java.nio.file.Path
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

    fun generate(
        diffs: List<TableDiff>,
        profile: SlotProfile,
        defaults: DefaultsProfile,
        assignments: List<Assignment>,
        output: Path,
        dialect: Dialect = Dialect.GENERIC,
    ): Stats {
        val effectiveDefaults = defaults.mergedFor(dialect.tomlKey)
        val assignmentsByColumn = assignments.associateBy { it.column }
        val sb = StringBuilder()
        var addedColumns = 0
        var createdTables = 0
        var orphanColumns = 0
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
                    val entityOnly = diff.columnDeltas.filter { it.kind == ColumnDelta.Kind.ENTITY_ONLY }
                    val dbOnly = diff.columnDeltas.filter { it.kind == ColumnDelta.Kind.DB_ONLY }
                    if (entityOnly.isEmpty() && dbOnly.isEmpty()) continue
                    sb.appendLine("-- ── ${diff.tableKey} ──")
                    for (delta in entityOnly) {
                        val field = delta.entityField!!
                        val ddl = ddlForField(field, profile, effectiveDefaults, assignmentsByColumn, diff.tableKey)
                        if (ddl == null) {
                            sb.appendLine("-- TODO classify ${diff.tableKey}.${field.column} (type=${field.type}); ALTER skipped")
                            unclassifiedFields++
                            continue
                        }
                        if (dialect == Dialect.SQLITE && ddl.contains("PRIMARY KEY", ignoreCase = true)) {
                            sb.appendLine(
                                "-- TODO SQLite cannot ADD a PRIMARY KEY column via ALTER TABLE; " +
                                    "rebuild ${diff.tableKey} to add ${field.column}",
                            )
                            unclassifiedFields++
                            continue
                        }
                        val defaultClause = defaultClauseFor(field, dialect, effectiveDefaults)
                        if (dialect == Dialect.SQLITE && !field.nullable && defaultClause == null) {
                            sb.appendLine(
                                "-- TODO SQLite cannot ADD a NOT NULL column without DEFAULT; " +
                                    "set an auto-default in F6 for ${field.type} or initialize ${field.column} in the entity",
                            )
                            unclassifiedFields++
                            continue
                        }
                        sb.appendLine(alterAdd(dialect, diff.tableKey, field.column, ddl, field.nullable, defaultClause))
                        addedColumns++
                    }
                    for (delta in dbOnly) {
                        sb.appendLine("-- info: column ${diff.tableKey}.${delta.name} exists in DB but not in entity")
                        orphanColumns++
                    }
                    sb.appendLine()
                }
                TableStatus.ENTITY_ONLY -> {
                    val entity = diff.entity!!
                    val lines = entity.fields.mapNotNull { field ->
                        val ddl = ddlForField(field, profile, effectiveDefaults, assignmentsByColumn, diff.tableKey)
                        if (ddl == null) {
                            unclassifiedFields++
                            null
                        } else {
                            val nullClause = if (field.nullable) "" else " NOT NULL"
                            val ddlHasPk = ddl.contains("PRIMARY KEY", ignoreCase = true)
                            val pkClause = if (field.primaryKey && !ddlHasPk) " PRIMARY KEY" else ""
                            val defaultClause = defaultClauseFor(field, dialect, effectiveDefaults)?.let { " DEFAULT $it" } ?: ""
                            "    ${quoteIdent(field.column, dialect)} $ddl$defaultClause$nullClause$pkClause"
                        }
                    }
                    if (lines.isEmpty()) {
                        sb.appendLine("-- ── ${diff.tableKey} (entity ${entity.className}) — SKIPPED ──")
                        sb.appendLine(
                            "-- All ${entity.fields.size} fields are unclassified; classify in F4 first " +
                                "or supply slot assignments.",
                        )
                        sb.appendLine()
                    } else {
                        sb.appendLine("-- ── CREATE ${diff.tableKey} (entity ${entity.className}) ──")
                        sb.appendLine("CREATE TABLE ${quoteIdent(diff.tableKey, dialect)} (")
                        sb.append(lines.joinToString(",\n"))
                        sb.appendLine()
                        sb.appendLine(");")
                        if (lines.size < entity.fields.size) {
                            sb.appendLine(
                                "-- WARNING: ${entity.fields.size - lines.size} fields skipped due to missing slot assignment",
                            )
                        }
                        sb.appendLine()
                        createdTables++
                    }
                }
                TableStatus.DB_ONLY -> {
                    sb.appendLine("-- ── ${diff.tableKey} (DB only) ──")
                    sb.appendLine("-- table exists in DB but no matching entity")
                    diff.dbColumns.forEach { col ->
                        sb.appendLine("--   ${col.name}  ${col.dbType}")
                    }
                    sb.appendLine()
                    orphanColumns += diff.dbColumns.size
                }
                TableStatus.PROBLEMATIC -> {
                    sb.appendLine("-- ── ${diff.tableKey} (PROBLEMATIC) ──")
                    sb.appendLine("-- requires manual review")
                    sb.appendLine()
                }
            }
        }

        if (addedColumns == 0 && createdTables == 0 && unclassifiedFields == 0 && orphanColumns == 0) {
            sb.appendLine("-- (everything is in sync, nothing to do)")
        }

        Files.writeString(output, sb.toString())
        return Stats(addedColumns, createdTables, orphanColumns, unclassifiedFields)
    }

    /** ALTER TABLE … ADD [COLUMN] … with dialect-correct syntax. */
    private fun alterAdd(
        dialect: Dialect,
        table: String,
        column: String,
        ddl: String,
        nullable: Boolean,
        defaultLiteral: String?,
    ): String {
        val nullClause = if (nullable) "" else " NOT NULL"
        val defaultClause = defaultLiteral?.let { " DEFAULT $it" } ?: ""
        val tbl = quoteIdent(table, dialect)
        val col = quoteIdent(column, dialect)
        return when (dialect) {
            Dialect.MSSQL, Dialect.ORACLE -> "ALTER TABLE $tbl ADD $col $ddl$defaultClause$nullClause;"
            else -> "ALTER TABLE $tbl ADD COLUMN $col $ddl$defaultClause$nullClause;"
        }
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
        return when (dialect) {
            Dialect.MYSQL, Dialect.MARIADB -> "`$name`"
            Dialect.MSSQL -> "[$name]"
            else -> "\"$name\""
        }
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
        if (t == "true" || t == "false") {
            val v = t == "true"
            return when (dialect) {
                Dialect.MYSQL, Dialect.MARIADB, Dialect.MSSQL, Dialect.SQLITE, Dialect.ORACLE -> if (v) "1" else "0"
                else -> if (v) "TRUE" else "FALSE"
            }
        }
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
        assignments: Map<String, Assignment>,
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
            } ?: ddlForSlot(profile, key = "$tableKey.${field.column}", assignments)
        }
        val key = "$tableKey.${field.column}"
        val assignment = assignments[key] ?: return null
        return ddlForSlot(profile, assignment.category, assignment.slot)
    }

    private fun ddlForSlot(profile: SlotProfile, key: String, assignments: Map<String, Assignment>): String? {
        val a = assignments[key] ?: return null
        return ddlForSlot(profile, a.category, a.slot)
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
        "LocalTime", "java.time.LocalTime", "java.sql.Time" -> defaults.localTimeDdl
        "LocalDateTime", "java.time.LocalDateTime", "java.sql.Timestamp" -> defaults.localDateTimeDdl
        "Instant", "java.time.Instant", "OffsetDateTime", "java.time.OffsetDateTime",
        "ZonedDateTime", "java.time.ZonedDateTime" -> defaults.instantDdl
        "ByteArray", "kotlin.ByteArray" -> defaults.byteArrayDdl
        "CharArray", "kotlin.CharArray" -> defaults.charArrayDdl
        "UUID", "java.util.UUID" -> defaults.uuidDdl
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
        val orphanColumns: Int,
        val unclassifiedFields: Int,
    )
}
