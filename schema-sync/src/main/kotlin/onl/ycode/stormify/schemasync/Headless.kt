package onl.ycode.stormify.schemasync

import com.github.difflib.DiffUtils
import com.github.difflib.UnifiedDiffUtils
import onl.ycode.stormify.schemasync.classifier.ClassificationCache
import onl.ycode.stormify.schemasync.classifier.SchemaClassifier
import onl.ycode.stormify.schemasync.classifier.autoFillCache
import onl.ycode.stormify.schemasync.config.ConfigState
import onl.ycode.stormify.schemasync.config.ConnectionConfig
import onl.ycode.stormify.schemasync.db.DbIntrospector
import onl.ycode.stormify.schemasync.db.MigrationGenerator
import onl.ycode.stormify.schemasync.entity.DiffEngine
import onl.ycode.stormify.schemasync.entity.EntityStyle
import onl.ycode.stormify.schemasync.entity.EntityStyleDetector
import onl.ycode.stormify.schemasync.entity.KotlinEntity
import onl.ycode.stormify.schemasync.entity.KotlinDefaultsDetector
import onl.ycode.stormify.schemasync.entity.TableDiff
import onl.ycode.stormify.schemasync.entity.source.EntityScanner
import onl.ycode.stormify.schemasync.entity.source.EntityWriter
import onl.ycode.stormify.schemasync.entity.source.PsiEnvironment
import onl.ycode.stormify.schemasync.tui.CategoryFilter
import onl.ycode.stormify.schemasync.tui.PropertyAction
import onl.ycode.stormify.schemasync.tui.PropertyActions
import onl.ycode.stormify.schemasync.tui.RowCategory
import onl.ycode.stormify.schemasync.tui.buildPendingChanges
import onl.ycode.stormify.schemasync.tui.buildTableEntries
import onl.ycode.stormify.schemasync.tui.groupByTable
import java.nio.file.Files
import java.nio.file.Path

/**
 * Headless / CI-friendly entry point. Triggered when the user passes any of
 * `--export-sql` / `--export-kt`. Connects to the DB, scans entities, applies
 * the requested category and name filters, and writes a SQL migration and/or a
 * unified-diff patch — without ever entering the Lanterna TUI.
 *
 * Returns the process exit code (0 success, 1 hard error).
 */
internal fun runHeadless(args: Array<String>, state: ConfigState): Int {
    val exportSql = argValue(args, "--export-sql")?.let(Path::of)
    val exportKt = argValue(args, "--export-kt")?.let(Path::of)

    val connection = state.current.connection ?: run {
        System.err.println("No DB connection configured. Pass --url <jdbc-url>.")
        return 1
    }

    val sourceRoots = argValuesAll(args, "--sources").map(Path::of)
    if (exportKt != null && sourceRoots.isEmpty()) {
        System.err.println("--export-kt requires at least one --sources <dir>.")
        return 1
    }

    // Glob → regex (case-insensitive). `*` matches any chars, `?` matches one.
    // Empty list = match all.
    val entityFilters = argValuesAll(args, "--filter-entity").map(::globToRegex)
    val propertyFilters = argValuesAll(args, "--filter-property").map(::globToRegex)
    fun entityMatches(tableKey: String, classNames: Collection<String>): Boolean {
        if (entityFilters.isEmpty()) return true
        return entityFilters.any { rx ->
            rx.matches(tableKey) || classNames.any { cn ->
                rx.matches(cn) || rx.matches(cn.substringAfterLast('.'))
            }
        }
    }
    fun propertyMatches(column: String): Boolean {
        if (propertyFilters.isEmpty()) return true
        return propertyFilters.any { it.matches(column) }
    }

    // Categories token list. Default = same as TUI (everything except SYNCED).
    val rawCategories = argValue(args, "--categories")
    val selectedCategories: Set<RowCategory> = when {
        rawCategories == null -> RowCategory.entries.toSet() - RowCategory.SYNCED
        rawCategories.trim().equals("all", ignoreCase = true) -> RowCategory.entries.toSet()
        else -> parseCategoryTokens(rawCategories) ?: return 1
    }
    val categoryFilter = CategoryFilter(selectedCategories)

    System.err.println("Connecting to ${connection.url}…")
    val intro = DbIntrospector.connect(connection.url, connection.user, connection.password)
    val classifier = SchemaClassifier()
    try {
        val tables = intro.listTables()
        val tableKeys = tables.map { it.key }
        val viewKeys = tables.asSequence()
            .filter { it.kind == DbIntrospector.TableId.Kind.VIEW }
            .map { it.name.lowercase() }
            .toSet()
        val columns = intro.listColumns { done, total ->
            if (total > 0 && (done == total || done % 50 == 0)) {
                System.err.println("  introspecting tables $done/$total")
            }
        }
        val dialect = intro.dialect

        val entities = if (sourceRoots.isNotEmpty()) {
            System.err.println("Scanning Kotlin sources…")
            EntityScanner.scan(sourceRoots, state.current.namingPolicy)
        } else emptyList()

        val columnsByTable = groupByTable(columns)
        val effectiveTableKeys = tableKeys.ifEmpty { columnsByTable.keys.toList() }
        val diffs = DiffEngine.diff(entities, columnsByTable, effectiveTableKeys, state.current.namingPolicy)
        val diffsByTable = diffs.associateBy { it.tableKey }
        val tableEntries = buildTableEntries(diffs, viewKeys)

        classifier.trainFromSync(diffs, state.current.slots)
        val cache = ClassificationCache().also {
            autoFillCache(it, classifier, diffs, state.current.slots)
        }

        // Tables that survive the category + entity-name filter.
        val acceptedTableKeys: Set<String> = tableEntries.asSequence()
            .filter { categoryFilter.accept(it) }
            .map { it.table }
            .filter { tk ->
                val classNames = diffsByTable[tk]?.entities?.map { it.className }.orEmpty()
                entityMatches(tk, classNames)
            }
            .toSet()

        val acceptColumn: (String, String) -> Boolean = { tk, col ->
            tk in acceptedTableKeys && propertyMatches(col)
        }

        if (exportSql != null) {
            val render = MigrationGenerator.render(
                diffs.toList(), state.current.slots, state.current.defaults,
                cache, dialect, entities,
                acceptColumn = acceptColumn,
            )
            exportSql.parent?.let(Files::createDirectories)
            Files.writeString(exportSql, render.sql)
            System.err.println(
                "Wrote $exportSql " +
                    "(${render.stats.addedColumns} ALTER, " +
                    "${render.stats.createdTables} CREATE, " +
                    "${render.stats.unclassifiedFields} skipped)",
            )
        }

        if (exportKt != null) {
            val actions = PropertyActions().also {
                for (tk in acceptedTableKeys) {
                    val diff = diffsByTable[tk] ?: continue
                    for (delta in diff.columnDeltas) {
                        if (PropertyAction.isTogglable(delta.kind) && propertyMatches(delta.name)) {
                            it.set(tk, delta.name, PropertyAction.INSERT)
                        }
                    }
                }
            }
            val style = EntityStyleDetector.detect(entities)
            val kotlinDefaults = KotlinDefaultsDetector.detect(entities, state.current.kotlin)
            val pending = buildPendingChanges(
                entities, diffs, actions, state.current.namingPolicy, kotlinDefaults,
            )
            val patchText = renderUnifiedPatch(pending, style)
            exportKt.parent?.let(Files::createDirectories)
            Files.writeString(exportKt, patchText)
            System.err.println(
                "Wrote $exportKt " +
                    "(${pending.splices.size} splices, " +
                    "${pending.newEntities.size} new entity files)",
            )
        }
    } finally {
        runCatching { classifier.close() }
        runCatching { intro.cancelInflight() }
    }
    return 0
}

/** Translate `*`, `?`, `[set]` glob into an anchored case-insensitive regex.
 *  Falls back to plain literal matching for any other character. */
internal fun globToRegex(glob: String): Regex {
    val sb = StringBuilder("^")
    var i = 0
    while (i < glob.length) {
        when (val c = glob[i]) {
            '*' -> sb.append(".*")
            '?' -> sb.append('.')
            '[' -> {
                val close = glob.indexOf(']', i + 1)
                if (close < 0) {
                    sb.append("\\[")
                } else {
                    sb.append(glob.substring(i, close + 1))
                    i = close
                }
            }
            in setOf('.', '\\', '+', '(', ')', '{', '}', '|', '^', '$') -> {
                sb.append('\\').append(c)
            }
            else -> sb.append(c)
        }
        i++
    }
    sb.append('$')
    return Regex(sb.toString(), RegexOption.IGNORE_CASE)
}

/** Returns null and prints an error if any token doesn't match a [RowCategory.token]. */
internal fun parseCategoryTokens(raw: String): Set<RowCategory>? {
    val byToken = RowCategory.entries.associateBy { it.token }
    val out = mutableSetOf<RowCategory>()
    for (token in raw.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }) {
        val cat = byToken[token]
        if (cat == null) {
            System.err.println(
                "Unknown category token: $token. Allowed: ${byToken.keys.joinToString(", ")}, all.",
            )
            return null
        }
        out += cat
    }
    return out
}

/** Builds a single git-applicable unified diff covering every splice +
 *  every brand-new entity file. Splices are grouped by file so the output
 *  has one hunk header per file instead of one per edit. */
private fun renderUnifiedPatch(
    pending: onl.ycode.stormify.schemasync.tui.PendingChanges,
    style: EntityStyle,
): String {
    val sb = StringBuilder()
    val env = if (pending.splices.isNotEmpty()) PsiEnvironment() else null
    val cwd = Path.of("").toAbsolutePath()
    try {
        val splicesByPath = pending.splices.groupBy { it.sourcePath }
        for ((path, splices) in splicesByPath) {
            val original = runCatching { Files.readString(path) }.getOrNull()
            if (original == null) {
                System.err.println("  warning: could not read $path; skipping ${splices.size} splice(s)")
                continue
            }
            val edits = splices.map { it.edit }
            val patched = env?.let { EntityWriter.previewEdits(it, path, edits, style) }
            if (patched == null) {
                System.err.println("  warning: could not preview edits for $path; skipping ${splices.size} splice(s)")
                continue
            }
            if (patched == original) continue
            sb.appendLine(unifiedDiff(relativeOrAbsolute(cwd, path), original, patched))
        }
        for (newEntity in pending.newEntities) {
            val rendered = EntityWriter.renderEntitySource(newEntity, style)
            sb.appendLine(unifiedDiff(relativeOrAbsolute(cwd, newEntity.targetPath), "", rendered))
        }
    } finally {
        runCatching { env?.close() }
    }
    return sb.toString()
}

/** Path string the way `git apply` likes it: relative to [cwd] when [path]
 *  lives under it, otherwise the absolute path stripped of the leading `/`
 *  so the produced `a/<path>` header doesn't yield a doubled slash. */
private fun relativeOrAbsolute(cwd: Path, path: Path): String {
    val abs = path.toAbsolutePath().normalize()
    return runCatching { cwd.relativize(abs).toString() }
        .getOrNull()
        ?.takeIf { !it.startsWith("..") }
        ?: abs.toString().trimStart('/')
}

/** java-diff-utils wrapper that emits a single block with `a/`+`b/` prefixes
 *  so `git apply` accepts it without `-p0`. Empty [oldText] is treated as
 *  "new file" via `/dev/null`. */
private fun unifiedDiff(path: String, oldText: String, newText: String): String {
    val oldLines = splitLinesNoTrailing(oldText)
    val newLines = splitLinesNoTrailing(newText)
    val patch = DiffUtils.diff(oldLines, newLines)
    val origPath = if (oldText.isEmpty()) "/dev/null" else "a/$path"
    val newPath = "b/$path"
    val unified = UnifiedDiffUtils.generateUnifiedDiff(origPath, newPath, oldLines, patch, 3)
    return unified.joinToString("\n")
}

/** Split on `\n` but discard the phantom empty trailing element that
 *  [String.lines] inserts when the text ends with a newline. Without this,
 *  the diff carries an extra trailing context line that doesn't exist in
 *  the file, and `git apply` rejects the hunk. */
private fun splitLinesNoTrailing(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val lines = text.split('\n')
    return if (text.endsWith('\n')) lines.dropLast(1) else lines
}

internal fun hasHeadlessFlag(args: Array<String>): Boolean =
    args.any { it == "--export-sql" || it.startsWith("--export-sql=") ||
        it == "--export-kt" || it.startsWith("--export-kt=") }
