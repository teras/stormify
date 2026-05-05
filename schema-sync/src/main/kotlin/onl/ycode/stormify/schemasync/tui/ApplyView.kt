package onl.ycode.stormify.schemasync.tui

import com.googlecode.lanterna.TerminalSize
import com.googlecode.lanterna.TextColor
import com.googlecode.lanterna.gui2.BasicWindow
import com.googlecode.lanterna.gui2.Button
import com.googlecode.lanterna.gui2.Direction
import com.googlecode.lanterna.gui2.EmptySpace
import com.googlecode.lanterna.gui2.Interactable
import com.googlecode.lanterna.gui2.Label
import com.googlecode.lanterna.gui2.LinearLayout
import com.googlecode.lanterna.gui2.Panel
import com.googlecode.lanterna.gui2.TextBox
import com.googlecode.lanterna.gui2.Window
import com.googlecode.lanterna.gui2.WindowBasedTextGUI
import com.googlecode.lanterna.gui2.WindowListenerAdapter
import com.googlecode.lanterna.gui2.dialogs.MessageDialog
import com.googlecode.lanterna.gui2.dialogs.MessageDialogButton
import com.googlecode.lanterna.input.KeyStroke
import com.googlecode.lanterna.input.KeyType
import onl.ycode.stormify.schemasync.classifier.ClassificationCache
import onl.ycode.stormify.schemasync.config.ConfigState
import onl.ycode.stormify.schemasync.db.Dialect
import onl.ycode.stormify.schemasync.db.JdbcCategoryMapper
import onl.ycode.stormify.schemasync.db.MigrationGenerator
import onl.ycode.stormify.schemasync.db.dbDefaultToKotlinLiteral
import onl.ycode.stormify.schemasync.entity.ColumnDelta
import onl.ycode.stormify.schemasync.entity.EntityStyle
import onl.ycode.stormify.schemasync.entity.EntityStyleDetector
import onl.ycode.stormify.schemasync.entity.KotlinDefaultsDetector
import onl.ycode.stormify.schemasync.entity.KotlinEntity
import onl.ycode.stormify.schemasync.entity.TableDiff
import onl.ycode.stormify.schemasync.entity.source.EntityWriter
import onl.ycode.stormify.schemasync.entity.source.PsiEnvironment
import onl.ycode.stormify.schemasync.model.EntityBase
import onl.ycode.stormify.schemasync.model.KotlinDefaults
import onl.ycode.stormify.schemasync.model.NamingPolicy
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/** A single PSI splice queued for F2 apply. */
data class PsiSplice(
    val label: String,
    val sourcePath: Path,
    val edit: EntityWriter.Edit,
)

/** All code-side changes F2 will perform: in-place splices + brand-new entity files. */
data class PendingChanges(
    val splices: List<PsiSplice>,
    val newEntities: List<EntityWriter.NewEntity>,
) {
    val isEmpty: Boolean get() = splices.isEmpty() && newEntities.isEmpty()
    val total: Int get() = splices.size + newEntities.size
}

/** Resolves the (package, directory) where a brand-new entity file should land
 *  based on the most common location among already-scanned entities. */
private fun newEntityHomeFromScanned(entities: List<KotlinEntity>): Pair<String, Path>? {
    val candidate = entities.firstOrNull { !it.sourcePath.isNullOrBlank() } ?: return null
    val sourcePath = candidate.sourcePath ?: return null
    val dir = Path.of(sourcePath).parent ?: return null
    val pkg = candidate.className.substringBeforeLast('.', missingDelimiterValue = "")
    return pkg to dir
}

fun buildPendingChanges(
    entities: List<KotlinEntity>,
    diffs: Collection<TableDiff>,
    actions: PropertyActions,
    policy: NamingPolicy = NamingPolicy.LOWER_CASE_WITH_UNDERSCORES,
    kotlinDefaults: KotlinDefaults = KotlinDefaults(),
): PendingChanges {
    val effectiveDefaults = KotlinDefaultsDetector.detect(entities, kotlinDefaults)
    val splices = mutableListOf<PsiSplice>()
    val newEntities = mutableListOf<EntityWriter.NewEntity>()
    // Multiple entities may claim the same tableKey (multi-view, refactor
    // leftovers); for FK resolution we just need *any* match, so first-wins.
    val byTableKey: Map<String, KotlinEntity> =
        entities.groupBy { it.tableKey }.mapValues { it.value.first() }

    val (newSuperType, newSuperImport) = when (kotlinDefaults.entityBase) {
        EntityBase.NONE -> null to null
        EntityBase.BYDB_ALWAYS, EntityBase.BYDB_FOR_NEW -> "AutoTable()" to "onl.ycode.stormify.AutoTable"
    }

    if (kotlinDefaults.entityBase == EntityBase.BYDB_ALWAYS) {
        for (entity in entities) {
            if (entity.hasSuperType) continue
            val source = entity.sourcePath ?: continue
            splices += PsiSplice(
                label = "+ ${entity.className} : AutoTable()",
                sourcePath = Path.of(source),
                edit = EntityWriter.Edit.AddSupertype(
                    className = entity.className,
                    superType = "AutoTable()",
                    import = "onl.ycode.stormify.AutoTable",
                ),
            )
        }
    }

    /** Resolves an FK target table to (Kotlin propName, simple class name, import). */
    fun fkTarget(refTable: String?): Triple<String, String, String?>? {
        val target = refTable?.let { byTableKey[it] } ?: return null
        val simple = target.className.substringAfterLast('.')
        val propBase = simple.replaceFirstChar { it.lowercaseChar() }
        val import = target.className.takeIf { it.contains('.') }
        return Triple(propBase, simple, import)
    }
    /** Strip trailing `_id` (or `Id` after camelCase) so `owner_id` becomes `owner`. */
    fun stripIdSuffix(name: String): String = when {
        name.endsWith("_id", ignoreCase = true) -> name.dropLast(3)
        name.endsWith("Id") && name.length > 2 && name[name.length - 3].isLowerCase() -> name.dropLast(2)
        else -> name
    }
    val home: Pair<String, Path>? = newEntityHomeFromScanned(entities)

    for (diff in diffs) {
        when (diff.status) {
            onl.ycode.stormify.schemasync.model.TableStatus.DB_ONLY -> {
                val activeColumns = diff.dbColumns.filter { col ->
                    actions.get(diff.tableKey, col.name) == PropertyAction.INSERT
                }
                if (activeColumns.isEmpty() || home == null) continue
                val (pkg, dir) = home
                val classSimple = pascalCase(diff.tableKey.substringAfterLast('.'))
                val conventionTable = policy.fromKotlin(classSimple.replaceFirstChar { it.lowercaseChar() })
                val tableNameOverride = diff.tableKey
                    .takeIf { it.substringAfterLast('.') != conventionTable }
                newEntities += EntityWriter.NewEntity(
                    className = classSimple,
                    packageName = pkg,
                    targetDir = dir,
                    tableNameOverride = tableNameOverride,
                    superType = newSuperType,
                    superTypeImport = newSuperImport,
                    columns = activeColumns.map { col ->
                        val isPk = col.name.equals("id", ignoreCase = true)
                        val fk = fkTarget(col.referencedTable)
                        if (fk != null) {
                            val (basePropName, simpleType, fqn) = fk
                            val propName = policy.toKotlin(stripIdSuffix(col.name)).ifEmpty { basePropName }
                            EntityWriter.NewColumn(
                                propName = propName,
                                columnName = col.name,
                                kotlinType = simpleType,
                                nullable = col.nullable,
                                primaryKey = isPk,
                                autoIncrement = false,
                                import = fqn,
                                explicitColumnName = policy.fromKotlin(propName) != col.name,
                                defaultLiteral = dbDefaultToKotlinLiteral(col.defaultValue, simpleType),
                            )
                        } else {
                            val choice = JdbcCategoryMapper.kotlinTypeChoice(col.jdbcType, isPk, effectiveDefaults)
                            val propName = policy.toKotlin(col.name)
                            EntityWriter.NewColumn(
                                propName = propName,
                                columnName = col.name,
                                kotlinType = choice.kotlin,
                                nullable = col.nullable,
                                primaryKey = isPk,
                                autoIncrement = false,
                                import = choice.import,
                                explicitColumnName = policy.fromKotlin(propName) != col.name,
                                defaultLiteral = dbDefaultToKotlinLiteral(col.defaultValue, choice.kotlin),
                            )
                        }
                    },
                )
            }
            onl.ycode.stormify.schemasync.model.TableStatus.DIFF -> {
                val primary = diff.primary ?: byTableKey[diff.tableKey] ?: continue
                val targetClassNames = actions.targetsFor(diff.tableKey, primary.className)
                val insertTargets = diff.entities.filter { it.className in targetClassNames }
                for (delta in diff.columnDeltas) {
                    val act = actions.get(diff.tableKey, delta.name)
                    when (delta.kind) {
                        ColumnDelta.Kind.DB_ONLY -> if (act == PropertyAction.INSERT) {
                            val col = delta.dbColumn ?: continue
                            val fk = fkTarget(col.referencedTable)
                            val propName: String
                            val typeName: String
                            val typeImport: String?
                            if (fk != null) {
                                val (basePropName, simpleType, fqn) = fk
                                propName = policy.toKotlin(stripIdSuffix(col.name)).ifEmpty { basePropName }
                                typeName = simpleType
                                typeImport = fqn
                            } else {
                                val choice = JdbcCategoryMapper.kotlinTypeChoice(col.jdbcType, primaryKey = false, effectiveDefaults)
                                propName = policy.toKotlin(col.name)
                                typeName = choice.kotlin
                                typeImport = choice.import
                            }
                            val nul = if (col.nullable) "?" else ""
                            val translatedDefault = dbDefaultToKotlinLiteral(col.defaultValue, typeName)
                            for (target in insertTargets) {
                                val source = target.sourcePath ?: continue
                                splices += PsiSplice(
                                    label = "+ ${target.className}.$propName: $typeName$nul  (column ${diff.tableKey}.${col.name})",
                                    sourcePath = Path.of(source),
                                    edit = EntityWriter.Edit.AddProperty(
                                        className = target.className,
                                        propertyName = propName,
                                        kotlinType = typeName,
                                        nullable = col.nullable,
                                        import = typeImport,
                                        columnName = col.name,
                                        explicitColumnName = policy.fromKotlin(propName) != col.name,
                                        defaultLiteral = translatedDefault,
                                    ),
                                )
                            }
                        }
                        else -> {}
                    }
                }
            }
            else -> {} // ENTITY_ONLY → SQL only; SYNCED → nothing
        }
    }
    return PendingChanges(splices, newEntities)
}

/** Renders [pending] as the colour-coded entity diff. The single source of
 *  truth for both F2 ApplyView's "Entities" tab and DiffPane's right pane. */
internal fun renderEntitiesDiffLines(
    pending: PendingChanges,
    style: EntityStyle,
    env: PsiEnvironment,
): List<DiffLine> {
    if (pending.isEmpty) return listOf(DiffLine("(no entity changes)"))
    val out = mutableListOf<DiffLine>()
    if (pending.splices.isNotEmpty()) {
        val byPath = pending.splices.groupBy { it.sourcePath }
        for ((path, splices) in byPath) {
            val original = runCatching { Files.readString(path) }.getOrNull() ?: continue
            val patched = EntityWriter.previewEdits(env, path, splices.map { it.edit }, style) ?: continue
            if (patched == original) continue
            out += DiffLine("${Symbols.rule} ${path.fileName} ${Symbols.rule}")
            out += DiffLine("(${path})")
            out += DiffLine("")
            out += unifiedDiffLines(original.lines(), patched.lines())
            out += DiffLine("")
        }
    }
    if (pending.newEntities.isNotEmpty()) {
        for (ent in pending.newEntities) {
            out += DiffLine("${Symbols.rule} + ${ent.targetPath.fileName} ${Symbols.rule}")
            out += DiffLine("(${ent.targetPath})")
            out += DiffLine("")
            EntityWriter.renderEntitySource(ent, style).lines().forEach {
                out += DiffLine("+$it", TextColor.ANSI.GREEN)
            }
            out += DiffLine("")
        }
    }
    return out
}

/** Minimal unified-diff over line lists, returning coloured [DiffLine]s. */
private fun unifiedDiffLines(a: List<String>, b: List<String>, context: Int = 3): List<DiffLine> {
    val n = a.size
    val m = b.size
    val dp = Array(n + 1) { IntArray(m + 1) }
    for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
        dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1
                   else maxOf(dp[i + 1][j], dp[i][j + 1])
    }
    data class Op(val tag: Char, val aIdx: Int, val bIdx: Int, val text: String)
    val ops = mutableListOf<Op>()
    var i = 0; var j = 0
    while (i < n && j < m) {
        when {
            a[i] == b[j] -> { ops += Op(' ', i, j, a[i]); i++; j++ }
            dp[i + 1][j] >= dp[i][j + 1] -> { ops += Op('-', i, -1, a[i]); i++ }
            else -> { ops += Op('+', -1, j, b[j]); j++ }
        }
    }
    while (i < n) { ops += Op('-', i, -1, a[i]); i++ }
    while (j < m) { ops += Op('+', -1, j, b[j]); j++ }

    val changeIdx = ops.withIndex().filter { it.value.tag != ' ' }.map { it.index }
    if (changeIdx.isEmpty()) return listOf(DiffLine("(no textual changes)"))

    val hunks = mutableListOf<Pair<Int, Int>>()
    var hunkStart = (changeIdx.first() - context).coerceAtLeast(0)
    var hunkEnd = (changeIdx.first() + context).coerceAtMost(ops.lastIndex)
    for (idx in changeIdx.drop(1)) {
        if (idx - hunkEnd <= context) {
            hunkEnd = (idx + context).coerceAtMost(ops.lastIndex)
        } else {
            hunks += hunkStart to hunkEnd
            hunkStart = (idx - context).coerceAtLeast(0)
            hunkEnd = (idx + context).coerceAtMost(ops.lastIndex)
        }
    }
    hunks += hunkStart to hunkEnd

    val out = mutableListOf<DiffLine>()
    for ((start, end) in hunks) {
        val slice = ops.subList(start, end + 1)
        val firstA = slice.firstOrNull { it.aIdx >= 0 }?.aIdx ?: 0
        val firstB = slice.firstOrNull { it.bIdx >= 0 }?.bIdx ?: 0
        val countA = slice.count { it.tag != '+' }
        val countB = slice.count { it.tag != '-' }
        out += DiffLine("@@ -${firstA + 1},$countA +${firstB + 1},$countB @@", TextColor.ANSI.CYAN)
        for (op in slice) {
            val color = when (op.tag) {
                '+' -> TextColor.ANSI.GREEN
                '-' -> TextColor.ANSI.RED
                else -> null
            }
            out += DiffLine("${op.tag}${op.text}", color)
        }
    }
    return out
}

private fun toRelative(projectDir: Path, p: Path): String {
    val abs = if (p.isAbsolute) p.normalize() else projectDir.resolve(p).normalize()
    return runCatching { projectDir.relativize(abs).toString() }.getOrDefault(abs.toString())
}

private fun resolvePath(projectDir: Path, raw: String, fallback: String): Path {
    val text = raw.trim().ifEmpty { fallback }
    val p = Path.of(text)
    return (if (p.isAbsolute) p else projectDir.resolve(p)).normalize()
}

/**
 * F2 apply — full-screen tabbed view: Entities tab shows the patched source for
 * every affected file plus brand-new entity files; SQL tab shows the migration
 * SQL. Both tabs expose an editable path TextBox at the top whose value is
 * persisted (relative to the project) into `.schema-sync.toml` on apply.
 *
 * Returns the count of code-side changes actually applied. Caller should
 * re-introspect when the result is > 0.
 */
fun runApplyConfirmation(
    gui: WindowBasedTextGUI,
    state: ConfigState,
    cache: ClassificationCache,
    diffs: Collection<TableDiff>,
    pending: PendingChanges,
    dialect: Dialect,
    actions: PropertyActions,
    entities: List<KotlinEntity> = emptyList(),
): Boolean {
    val style = EntityStyleDetector.detect(entities)
    if (pending.isEmpty && diffs.none {
            it.status == onl.ycode.stormify.schemasync.model.TableStatus.ENTITY_ONLY ||
            it.status == onl.ycode.stormify.schemasync.model.TableStatus.DIFF ||
            it.status == onl.ycode.stormify.schemasync.model.TableStatus.DB_ONLY
        }) {
        MessageDialog.showMessageDialog(
            gui, "Apply",
            "Nothing to do ${Symbols.dash} schema and entities are in sync.",
            MessageDialogButton.OK,
        )
        return false
    }

    val projectDir = state.projectDir
    val entitiesDirInitial: Path = state.current.paths.entitiesDir
        ?.let { resolvePath(projectDir, it, "") }
        ?: pending.newEntities.firstOrNull()?.targetDir
        ?: projectDir
    val migrationInitial: Path = state.current.paths.migrationSql
        ?.let { resolvePath(projectDir, it, "migration.sql") }
        ?: projectDir.resolve("migration.sql")

    val window = BasicWindow("Apply pending edits")
    window.setHints(listOf(Window.Hint.FULL_SCREEN, Window.Hint.NO_DECORATIONS))

    var confirmed = false
    var activeIdx = 0
    val tabButtons = mutableListOf<Button>()
    val contentSlot = Panel(LinearLayout(Direction.VERTICAL).setSpacing(0))

    val fillGrow = LinearLayout.createLayoutData(LinearLayout.Alignment.Fill, LinearLayout.GrowPolicy.CanGrow)

    // Entities tab content
    val entitiesPathBox = TextBox(TerminalSize(60, 1), entitiesDirInitial.toString())
    val entitiesDiffLines = PsiEnvironment().use { env -> renderEntitiesDiffLines(pending, style, env) }
    val entitiesContent = DiffViewer(entitiesDiffLines)
    val entitiesPane = Panel(LinearLayout(Direction.VERTICAL).setSpacing(0)).apply {
        layoutData = fillGrow
        val row = Panel(LinearLayout(Direction.HORIZONTAL).setSpacing(1))
        row.addComponent(Label("Entities dir:"))
        row.addComponent(entitiesPathBox)
        addComponent(row)
        addComponent(entitiesContent.apply { layoutData = fillGrow })
    }
    val entitiesInitialFocus: Interactable = entitiesPathBox

    // SQL tab content
    val sqlPathBox = TextBox(TerminalSize(60, 1), migrationInitial.toString())
    val migrationRender = MigrationGenerator.render(
        diffs.toList(),
        state.current.slots,
        state.current.defaults,
        cache,
        dialect,
        entities,
        acceptColumn = { tableKey, col -> actions.get(tableKey, col) == PropertyAction.INSERT },
    )
    val sqlContent = DiffViewer(migrationRender.sql.lines().map { DiffLine(it) })
    val sqlPane = Panel(LinearLayout(Direction.VERTICAL).setSpacing(0)).apply {
        layoutData = fillGrow
        val row = Panel(LinearLayout(Direction.HORIZONTAL).setSpacing(1))
        row.addComponent(Label("Migration SQL:"))
        row.addComponent(sqlPathBox)
        addComponent(row)
        addComponent(sqlContent.apply { layoutData = fillGrow })
    }
    val sqlInitialFocus: Interactable = sqlPathBox

    data class TabSpec(val label: String, val content: Panel, val initialFocus: Interactable)
    val tabs = listOf(
        TabSpec("Entities", entitiesPane, entitiesInitialFocus),
        TabSpec("SQL", sqlPane, sqlInitialFocus),
    )

    fun renderTabLabels() {
        tabs.forEachIndexed { i, spec ->
            tabButtons[i].label = if (i == activeIdx) "[ ${spec.label} ]" else "  ${spec.label}  "
        }
    }

    fun showTab(i: Int, focusContent: Boolean) {
        activeIdx = i
        contentSlot.removeAllComponents()
        contentSlot.addComponent(tabs[i].content)
        renderTabLabels()
        if (focusContent) tabs[i].initialFocus.takeFocus()
    }

    val tabBar = Panel(LinearLayout(Direction.HORIZONTAL).setSpacing(1))
    tabs.forEachIndexed { i, spec ->
        val b = plainButton(spec.label) { showTab(i, focusContent = true) }
        tabButtons += b
        tabBar.addComponent(b)
    }

    fun persistPaths() {
        val newEntitiesRel = toRelative(projectDir, Path.of(entitiesPathBox.text.trim().ifEmpty { entitiesDirInitial.toString() }))
        val newMigrationRel = toRelative(projectDir, Path.of(sqlPathBox.text.trim().ifEmpty { migrationInitial.toString() }))
        state.update { cfg ->
            cfg.copy(paths = cfg.paths.copy(
                entitiesDir = newEntitiesRel.takeIf { it.isNotEmpty() && it != "." },
                migrationSql = newMigrationRel.takeIf { it.isNotEmpty() && it != "." },
            ))
        }
    }

    val applyButton = plainButton("Apply") {
        persistPaths()
        val typedEntitiesDir = resolvePath(projectDir, entitiesPathBox.text, entitiesDirInitial.toString())
        val finalPending = if (entitiesPathBox.text.trim() != entitiesDirInitial.toString()) {
            // Re-target new entity files at the user-edited path.
            pending.copy(newEntities = pending.newEntities.map { it.copy(targetDir = typedEntitiesDir) })
        } else pending
        val splicesApplied = applySplices(finalPending.splices, style)
        val createdFiles = finalPending.newEntities.mapNotNull { EntityWriter.createEntity(it, style) }
        val hasSql = migrationRender.stats.addedColumns > 0 || migrationRender.stats.createdTables > 0
        val sqlPath = resolvePath(projectDir, sqlPathBox.text, "migration.sql")
        if (hasSql) {
            Files.createDirectories(sqlPath.parent ?: projectDir)
            Files.writeString(sqlPath, migrationRender.sql)
        }
        confirmed = true
        val msg = buildString {
            appendLine("Applied $splicesApplied of ${finalPending.splices.size} code edits.")
            if (finalPending.newEntities.isNotEmpty()) {
                appendLine("Created ${createdFiles.size} of ${finalPending.newEntities.size} new entity files.")
            }
            appendLine()
            if (hasSql) {
                appendLine("Migration written to $sqlPath")
                appendLine("  ${migrationRender.stats.addedColumns} ALTER TABLE ADD COLUMN")
                appendLine("  ${migrationRender.stats.createdTables} CREATE TABLE")
                if (migrationRender.stats.unclassifiedFields > 0) {
                    appendLine("  ${migrationRender.stats.unclassifiedFields} fields skipped (unclassified)")
                }
            } else {
                appendLine("No SQL migration to write.")
            }
        }
        MessageDialog.showMessageDialog(gui, "Apply", msg, MessageDialogButton.OK)
        window.close()
    }
    val cancelButton = plainButton("Cancel") { window.close() }

    val buttonRow = Panel(LinearLayout(Direction.HORIZONTAL).setSpacing(2))
    buttonRow.addComponent(applyButton)
    buttonRow.addComponent(cancelButton)

    contentSlot.layoutData = fillGrow
    val root = Panel(LinearLayout(Direction.VERTICAL).setSpacing(0))
    root.addComponent(tabBar)
    root.addComponent(contentSlot)
    root.addComponent(buttonRow)
    root.addComponent(Label("Tab/Shift-Tab navigate · Enter activate · Esc cancel"))

    window.component = root
    showTab(0, focusContent = false)
    window.focusedInteractable = tabButtons[0]

    window.addWindowListener(object : WindowListenerAdapter() {
        override fun onUnhandledInput(basePane: Window, keyStroke: KeyStroke, hasBeenHandled: AtomicBoolean) {
            when {
                keyStroke.keyType == KeyType.Escape -> {
                    window.close(); hasBeenHandled.set(true)
                }
                ThemeManager.handleKey(keyStroke, gui) -> {
                    gui.screen.refresh(); hasBeenHandled.set(true)
                }
            }
        }
    })

    gui.addWindowAndWait(window)
    return confirmed
}

private fun applySplices(splices: List<PsiSplice>, style: EntityStyle): Int {
    val grouped = splices.groupBy({ it.sourcePath }, { it.edit })
    var applied = 0
    PsiEnvironment().use { env ->
        for ((path, edits) in grouped) {
            if (EntityWriter.applyEdits(env, path, edits, style)) applied += edits.size
        }
    }
    return applied
}
