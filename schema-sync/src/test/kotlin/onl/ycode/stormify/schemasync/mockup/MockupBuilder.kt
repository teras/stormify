package onl.ycode.stormify.schemasync.mockup

import kotlin.random.Random

/**
 * Deterministically generates a [MockupSpec] from a fixed seed. Same
 * seed + shape ⇒ identical output, byte-for-byte. The builder is **not**
 * dialect-aware: it operates on logical column types and lets [MockupSql]
 * / [MockupEntitySource] produce per-dialect DDL and Kotlin source.
 *
 * Distribution highlights:
 *  - 100 entities target non-existent tables (phantom → ENTITY_ONLY).
 *  - 50 target views, 50 target synonyms, 200 share base tables in
 *    pairs (1-2 cardinality), 600 solo on base tables.
 *  - Of the 900 non-phantom entities: 150 fully SYNCED, 750 carry at
 *    least one [DivergenceFlags] axis. The 750 are split into 200
 *    single-axis, 250 double, 200 triple, 100 quad — every TUI filter
 *    combination has matches.
 *  - Some DB views and synonyms remain unmapped (DB_ONLY).
 *  - Each table picks 4–11 columns from a varied palette so no two
 *    entities are cookie-cutter identical.
 */
object MockupBuilder {

    private val PALETTE = listOf(
        ColumnType.VARCHAR_50, ColumnType.VARCHAR_255, ColumnType.TEXT,
        ColumnType.INT, ColumnType.BIGINT, ColumnType.SMALLINT,
        ColumnType.DECIMAL_10_2, ColumnType.DECIMAL_18_4, ColumnType.DOUBLE_,
        ColumnType.BOOLEAN_,
        ColumnType.DATE_, ColumnType.TIMESTAMP_,
        ColumnType.BLOB_,
    )

    fun build(shape: MockupShape = MockupShape.FULL, rowsPerTable: Int = 1000, seed: Long = 42L): MockupSpec {
        val rng = Random(seed)
        // Solo base-table naming: half use canonical-friendly names that
        // round-trip through the LOWER_CASE_WITH_UNDERSCORES policy
        // (`entity_solo<n>` ↔ class `EntitySolo<n>`), the other half use
        // the compact `t<n>` form that requires an explicit
        // `@DbTable(name=...)` annotation. Mixing both styles in one
        // fixture mirrors what real-world projects look like.
        val tables = (0 until shape.baseTables).map { i ->
            val name = when {
                // Shared range and orphan range keep the compact form.
                i < shape.sharedTables -> "t${i.pad()}"
                i >= shape.sharedTables + shape.soloBaseEntities -> "t${i.pad()}"
                // First half of the solo range: canonical-aligned to entity class.
                (i - shape.sharedTables) < shape.soloBaseEntities / 2 ->
                    "entity_solo${(i - shape.sharedTables + 1).pad4()}"
                // Second half: compact form, will need explicit @DbTable.
                else -> "t${i.pad()}"
            }
            generateTable(name, rng)
        }
        val views = (0 until shape.views).map { i -> ViewSpec("v${i.pad()}", tables[i % tables.size].name) }
        val synonyms = (0 until shape.synonyms).map { i ->
            SynonymSpec("syn${i.pad()}", tables[(i + 17) % tables.size].name)
        }
        val entities = generateEntities(shape, tables, views, synonyms, rng)
        return MockupSpec(tables, views, synonyms, entities, rowsPerTable, shape)
    }

    private fun Int.pad(): String = toString().padStart(4, '0')
    private fun Int.pad4(): String = toString().padStart(4, '0')

    private fun generateTable(name: String, rng: Random): TableSpec {
        val pk = ColumnSpec("id", ColumnType.PK_BIGINT, nullable = false, default = null)
        val width = 4 + rng.nextInt(8)  // 4..11 columns plus PK
        val cols = mutableListOf(pk)
        for (i in 0 until width) {
            val type = PALETTE[rng.nextInt(PALETTE.size)]
            val nullable = rng.nextDouble() < 0.55
            // Non-null columns whose Kotlin counterpart is a literal-typed
            // primitive (Int/Long/String/…) MUST carry a DB default so
            // the entity-side init can match it. Without this, the
            // emitter would write `var x: Int = 0` (an extractable
            // literal) while the DB reports null, triggering a
            // false-positive default mismatch on every SYNCED entity.
            val mustHaveDefault = !nullable && requiresDbDefaultForSynced(type)
            val default = when {
                mustHaveDefault -> defaultFor(type, rng)
                rng.nextDouble() < 0.30 -> defaultFor(type, rng)
                else -> null
            }
            cols += ColumnSpec("col${i.pad()}_${typeTag(type)}", type, nullable, default)
        }
        return TableSpec(name, cols)
    }

    /** Column types whose Kotlin counterpart is a literal-typed primitive
     *  the scanner extracts as `defaultLiteral`. Excludes Boolean (DB
     *  representations differ across dialects: `0/1` vs `false/true`,
     *  with no portable normalisation), TEXT (DB defaults forbidden by
     *  MySQL/MSSQL), and complex types whose Kotlin init is necessarily
     *  a function call (BigDecimal, dates, ByteArray) which the scanner
     *  ignores anyway. */
    private fun requiresDbDefaultForSynced(type: ColumnType): Boolean = when (type) {
        ColumnType.VARCHAR_50, ColumnType.VARCHAR_255,
        ColumnType.INT, ColumnType.BIGINT, ColumnType.SMALLINT,
        ColumnType.DOUBLE_,
        -> true
        else -> false
    }

    private fun defaultFor(type: ColumnType, rng: Random): ColumnDefault? = when (type) {
        ColumnType.PK_BIGINT -> null
        ColumnType.VARCHAR_50, ColumnType.VARCHAR_255 ->
            if (rng.nextBoolean()) ColumnDefault.EmptyString else ColumnDefault.StrLit("seed-${rng.nextInt(1000)}")
        // MySQL/MSSQL refuse defaults on TEXT/BLOB columns; keep TEXT
        // default-less for portability across every supported dialect.
        ColumnType.TEXT -> null
        ColumnType.INT, ColumnType.BIGINT, ColumnType.SMALLINT ->
            if (rng.nextBoolean()) ColumnDefault.Zero else ColumnDefault.IntLit(rng.nextInt(100))
        ColumnType.DOUBLE_ -> ColumnDefault.Zero
        // BigDecimal cannot be expressed as a sanitisable Kotlin literal
        // (`java.math.BigDecimal("0")` is a function call, not a literal),
        // so the entity scanner sees defaultLiteral=null while the DB
        // reports `0` — a permanent false-positive default mismatch.
        // Skip DB defaults on DECIMAL columns to keep SYNCED reachable.
        ColumnType.DECIMAL_10_2, ColumnType.DECIMAL_18_4 -> null
        // Boolean DB defaults aren't portable: PostgreSQL reports
        // `false` while MySQL/MSSQL/Oracle report `0`/`1`, and
        // schema-sync's diff engine has no boolean-aware normaliser. To
        // keep SYNCED reachable across every dialect, leave Boolean
        // columns default-less and let the entity-side init use a
        // non-extractable form (`(false)`).
        ColumnType.BOOLEAN_ -> null
        // CURRENT_TIMESTAMP cannot be expressed as a Kotlin literal that
        // the scanner accepts; an entity init like `LocalDateTime.now()`
        // is a function call, not a literal. Skip TIMESTAMP defaults so
        // SYNCED entities don't carry an unavoidable default mismatch.
        ColumnType.TIMESTAMP_ -> null
        // MySQL/MariaDB reject CURRENT_TIMESTAMP as a DATE default;
        // schema-sync would also need a non-trivial Kotlin literal.
        ColumnType.DATE_, ColumnType.BLOB_ -> null
    }

    private fun typeTag(type: ColumnType): String = when (type) {
        ColumnType.PK_BIGINT -> "id"
        ColumnType.VARCHAR_50 -> "v50"
        ColumnType.VARCHAR_255 -> "v255"
        ColumnType.TEXT -> "txt"
        ColumnType.INT -> "i"
        ColumnType.BIGINT -> "lng"
        ColumnType.SMALLINT -> "si"
        ColumnType.DECIMAL_10_2 -> "d102"
        ColumnType.DECIMAL_18_4 -> "d184"
        ColumnType.DOUBLE_ -> "dbl"
        ColumnType.BOOLEAN_ -> "b"
        ColumnType.DATE_ -> "d"
        ColumnType.TIMESTAMP_ -> "ts"
        ColumnType.BLOB_ -> "blob"
    }

    /**
     * Lays down the entity universe. The flag distribution is computed
     * up-front so every TUI bucket has a known population. RNG is used
     * only to mix flag tuples within each issue-count budget so the
     * output stays deterministic per seed.
     */
    private fun generateEntities(
        shape: MockupShape,
        tables: List<TableSpec>,
        views: List<ViewSpec>,
        synonyms: List<SynonymSpec>,
        rng: Random,
    ): List<EntitySpec> {
        // 1) Build the divergence ledger. Each entry is the flag set the
        //    next non-phantom entity should carry. Order is shuffled by
        //    the seeded RNG so the buckets sprinkle through the entity
        //    list naturally.
        val ledger = ArrayDeque(buildDivergenceLedger(shape, rng))

        // 2) Decide which DB target each entity claims. We allocate
        //    pairs first (fixed positions in the spec), then views,
        //    then synonyms, then solo base. Phantoms come last so they
        //    don't consume any non-phantom budget entries.
        val out = mutableListOf<EntitySpec>()

        // Paired entities — 100 pairs sharing tables[0..sharedTables).
        repeat(shape.sharedTables) { p ->
            val table = tables[p]
            for (variant in 0 until 2) {
                val name = "EntityShared${(p + 1).pad4()}_$variant"
                out += buildEntityFor(table, table.name, TargetKind.TABLE, name, ledger.removeFirst(), rng)
            }
        }

        // View-mapped entities — first [viewMappedEntities] views.
        for (i in 0 until shape.viewMappedEntities) {
            val view = views[i]
            val table = tables.first { it.name == view.baseTable }
            val name = "EntityView${(i + 1).pad4()}"
            out += buildEntityFor(table, view.name, TargetKind.VIEW, name, ledger.removeFirst(), rng)
        }

        // Synonym-mapped entities — first [synonymMappedEntities] synonyms.
        for (i in 0 until shape.synonymMappedEntities) {
            val syn = synonyms[i]
            val table = tables.first { it.name == syn.baseTable }
            val name = "EntitySynonym${(i + 1).pad4()}"
            out += buildEntityFor(table, syn.name, TargetKind.SYNONYM, name, ledger.removeFirst(), rng)
        }

        // Solo base-table entities — remaining base tables get one each.
        var soloIdx = 0
        while (soloIdx < shape.soloBaseEntities) {
            val table = tables[shape.sharedTables + soloIdx]
            val name = "EntitySolo${(soloIdx + 1).pad4()}"
            out += buildEntityFor(table, table.name, TargetKind.TABLE, name, ledger.removeFirst(), rng)
            soloIdx++
        }

        // Phantoms — point at made-up names; flags ignored (a missing
        // table dwarfs any column-level diff).
        for (i in 0 until shape.phantomEntities) {
            val name = "EntityPhantom${(i + 1).pad4()}"
            out += phantomEntity(name, "ghostT${i.pad()}")
        }

        check(ledger.isEmpty()) { "ledger left over: ${ledger.size}" }
        return out
    }

    /** Produces exactly [shape.nonPhantomTotal] flag sets matching every
     *  per-bucket budget in the shape. The flag assignments inside each
     *  issue-count tier rotate through every possible flag combination
     *  for that tier so coverage is balanced. */
    private fun buildDivergenceLedger(shape: MockupShape, rng: Random): List<DivergenceFlags> {
        val ledger = mutableListOf<DivergenceFlags>()
        repeat(shape.syncedEntities) { ledger += DivergenceFlags.SYNCED }
        ledger += distributeFlagsAcrossCombos(combosOfSize(1), shape.singleIssueEntities)
        ledger += distributeFlagsAcrossCombos(combosOfSize(2), shape.doubleIssueEntities)
        ledger += distributeFlagsAcrossCombos(combosOfSize(3), shape.tripleIssueEntities)
        ledger += distributeFlagsAcrossCombos(combosOfSize(4), shape.quadIssueEntities)
        ledger.shuffle(rng)
        return ledger
    }

    private val ALL_AXES: Array<(DivergenceFlags) -> DivergenceFlags> = arrayOf(
        { it.copy(missingDbField = true) },
        { it.copy(missingKotlinField = true) },
        { it.copy(typeConflict = true) },
        { it.copy(defaultConflict = true) },
    )

    /** All `(4 choose k)` flag combinations as bit-masks over [ALL_AXES]. */
    private fun combosOfSize(k: Int): List<DivergenceFlags> {
        val combos = mutableListOf<DivergenceFlags>()
        for (mask in 0 until (1 shl ALL_AXES.size)) {
            if (Integer.bitCount(mask) != k) continue
            var f = DivergenceFlags.SYNCED
            for (i in ALL_AXES.indices) {
                if ((mask shr i) and 1 == 1) f = ALL_AXES[i](f)
            }
            combos += f
        }
        return combos
    }

    /** Round-robin [combos] until we've produced [count] entries.
     *  Guarantees every combo gets at least one slot when count ≥
     *  combos.size — important for SMOKE where the budgets are tight. */
    private fun distributeFlagsAcrossCombos(combos: List<DivergenceFlags>, count: Int): List<DivergenceFlags> {
        if (combos.isEmpty() || count == 0) return emptyList()
        return List(count) { combos[it % combos.size] }
    }

    private fun phantomEntity(className: String, tableName: String): EntitySpec {
        val fields = listOf(
            EntityField("id", "id", "Long", nullable = false, primaryKey = true, initLiteral = "0L"),
            EntityField("name", "name", "String", nullable = false, initLiteral = "\"\""),
            EntityField("createdAt", "created_at", "java.time.LocalDateTime", nullable = true),
        )
        return EntitySpec(className, tableName, TargetKind.PHANTOM, fields, DivergenceFlags.SYNCED)
    }

    /**
     * Materialises an entity for [sourceTable] reachable via [tableName]
     * (which may be a view or synonym alias). Applies each
     * [DivergenceFlags] axis as an independent transform on the field
     * list — flags compose cleanly so the same code path emits
     * single-, double-, triple-, and quad-issue entities.
     */
    private fun buildEntityFor(
        sourceTable: TableSpec,
        tableName: String,
        targetKind: TargetKind,
        className: String,
        flags: DivergenceFlags,
        rng: Random,
    ): EntitySpec {
        var fields = sourceTable.columns.map { fieldFor(it) }

        // Apply each axis. Order is stable (fixed) so flag composition
        // is deterministic per RNG draw.
        if (flags.missingKotlinField) {
            // Drop a non-PK field so the DB column becomes DB_ONLY.
            val dropIdx = fields.indexOfFirst { !it.primaryKey }.coerceAtLeast(1)
            fields = fields.toMutableList().also { it.removeAt(dropIdx) }
        }
        if (flags.missingDbField) {
            // Append an extra entity-side field; DB has no such column.
            fields = fields + EntityField(
                propName = "extraSlot",
                columnName = "extra_slot",
                kotlinType = "String",
                nullable = true,
                initLiteral = "null",
            )
        }
        if (flags.typeConflict) {
            // Pick a non-PK field to swap; prefer one whose source column
            // has no DB default so the type swap doesn't accidentally
            // trip defaultConflict as well. Falls back to any candidate
            // when every field's column carries a default.
            val candidates = fields.indices
                .filter { i -> !fields[i].primaryKey && fields[i].columnName != "extra_slot" }
            val targetIdx = candidates.firstOrNull { i ->
                sourceTable.columns.firstOrNull { it.name == fields[i].columnName }?.default == null
            } ?: candidates.firstOrNull() ?: 1
            val f = fields[targetIdx]
            val newType = if (f.kotlinType == "String" || f.kotlinType.endsWith("LocalDate") ||
                f.kotlinType.endsWith("LocalDateTime") || f.kotlinType == "Boolean" ||
                f.kotlinType == "ByteArray"
            ) "Long" else "String"
            fields = fields.toMutableList().also {
                // initLiteral=null lets the emitter pick a non-extractable
                // fallback (`String()`, `Long.MIN_VALUE`); the scanner
                // then sees defaultLiteral=null and the diff registers a
                // pure type conflict without a default-side mismatch.
                it[targetIdx] = f.copy(
                    kotlinType = newType,
                    nullable = false,
                    initLiteral = null,
                )
            }
        }
        if (flags.defaultConflict) {
            // Find a column with a DB DEFAULT and emit a different
            // Kotlin literal so DiffEngine.defaultMismatch fires.
            val targetIdx = fields.indices.firstOrNull { i ->
                !fields[i].primaryKey &&
                    sourceTable.columns.firstOrNull { it.name == fields[i].columnName }?.default != null
            }
            if (targetIdx != null) {
                val f = fields[targetIdx]
                // Bump the literal: numbers + 1, strings → fixed "drift",
                // booleans flip — anything non-equal to the DB default
                // does the trick.
                fields = fields.toMutableList().also {
                    it[targetIdx] = f.copy(initLiteral = driftedLiteral(f, rng))
                }
            }
        }
        return EntitySpec(className, tableName, targetKind, fields, flags)
    }

    /** Kotlin-source primitives that the diff engine treats as opaque
     *  (no TypeFamily) — swapping these on the entity side wouldn't
     *  trigger a TYPE_MISMATCH, so we skip them when injecting one. */
    private val TYPE_AGNOSTIC = setOf<String>()

    private fun driftedLiteral(field: EntityField, rng: Random): String {
        val ignored = rng
        return when (field.kotlinType.substringAfterLast('.')) {
            "String" -> "\"drift-${field.propName}\""
            "Int" -> "999"
            "Long" -> "999L"
            "Short" -> "9"
            "Double" -> "9.9"
            "Float" -> "9.9f"
            "Boolean" -> "true"
            "BigDecimal" -> "java.math.BigDecimal(\"9.99\")"
            "LocalDate" -> "java.time.LocalDate.of(1999, 1, 1)"
            "LocalDateTime" -> "java.time.LocalDateTime.of(1999, 1, 1, 0, 0)"
            "ByteArray" -> "byteArrayOf(9)"
            else -> "\"drift\""
        }
    }

    /** Build an entity field that, by default (no divergence flags),
     *  matches [c] both structurally and on its default value. */
    private fun fieldFor(c: ColumnSpec): EntityField {
        val (kotlinType, nullable) = kotlinTypeFor(c)
        val propName = toCamel(c.name)
        return EntityField(
            propName = propName,
            columnName = c.name,
            kotlinType = kotlinType,
            nullable = nullable,
            primaryKey = c.type == ColumnType.PK_BIGINT,
            initLiteral = matchingInitLiteral(c, kotlinType, nullable),
        )
    }

    /** Returns the Kotlin literal whose normalized form equals the DB
     *  default's normalized form. Returns null when the entity field
     *  may legitimately have no init (PK; unset nullable; column with
     *  no DB default). */
    private fun matchingInitLiteral(c: ColumnSpec, kotlinType: String, nullable: Boolean): String? {
        val def = c.default
        if (def == null) {
            // No DB default; we still need to emit Kotlin syntax. Pick a
            // literal whose normalized form is `null` for nullable
            // fields and a type-default zero for non-null fields. Both
            // compare cleanly against `col.defaultValue == null`.
            return null
        }
        val simple = kotlinType.substringAfterLast('.')
        return when (def) {
            ColumnDefault.Zero -> when (simple) {
                "Long" -> "0L"
                "Float" -> "0f"
                "Double" -> "0.0"
                else -> "0"
            }
            ColumnDefault.EmptyString -> "\"\""
            ColumnDefault.FalseBool -> "false"
            ColumnDefault.NowTs -> null  // unreachable; defaultFor never emits NowTs
            is ColumnDefault.IntLit -> when (simple) {
                "Long" -> "${def.value}L"
                "Double" -> "${def.value}.0"
                "Float" -> "${def.value}f"
                else -> def.value.toString()
            }
            is ColumnDefault.StrLit -> "\"${def.value.replace("\"", "\\\"")}\""
        }
    }

    private fun kotlinTypeFor(c: ColumnSpec): Pair<String, Boolean> {
        val base = when (c.type) {
            ColumnType.PK_BIGINT -> "Long"
            ColumnType.VARCHAR_50, ColumnType.VARCHAR_255, ColumnType.TEXT -> "String"
            ColumnType.SMALLINT -> "Short"
            ColumnType.INT -> "Int"
            ColumnType.BIGINT -> "Long"
            ColumnType.DECIMAL_10_2, ColumnType.DECIMAL_18_4 -> "java.math.BigDecimal"
            ColumnType.DOUBLE_ -> "Double"
            ColumnType.BOOLEAN_ -> "Boolean"
            ColumnType.DATE_ -> "java.time.LocalDate"
            ColumnType.TIMESTAMP_ -> "java.time.LocalDateTime"
            ColumnType.BLOB_ -> "ByteArray"
        }
        return base to (c.nullable && c.type != ColumnType.PK_BIGINT)
    }

    private fun toCamel(snake: String): String {
        val parts = snake.split('_').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return snake
        return parts.first() + parts.drop(1).joinToString("") { p ->
            p.replaceFirstChar { it.uppercaseChar() }
        }
    }
}
