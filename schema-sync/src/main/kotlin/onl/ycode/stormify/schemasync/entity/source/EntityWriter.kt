package onl.ycode.stormify.schemasync.entity.source

import onl.ycode.stormify.schemasync.entity.ConstructorStyle
import onl.ycode.stormify.schemasync.entity.EntityStyle
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import java.nio.file.Files
import java.nio.file.Path

/**
 * Targeted text splices on Kotlin source files. Two operations are supported:
 *
 *  - [addProperty]: insert `var name: Type = default` into a class body, just
 *    before the closing brace. Indentation is inferred from the previous
 *    declaration. Existing formatting/comments/blank lines are preserved.
 *  - [markTransient]: prepend `@Transient` (with a `kotlin.jvm.Transient` import
 *    if missing) to a property declaration.
 *
 * Operations work on the source string directly using PSI byte offsets, so the
 * output differs from the original only in the spliced region.
 */
object EntityWriter {

    /** Plan for a single edit to a single file; produced by [planEdits]. */
    sealed interface Edit {
        val className: String

        /** Insert a new var declaration into the class body. */
        data class AddProperty(
            override val className: String,
            val propertyName: String,
            val kotlinType: String,
            val nullable: Boolean,
            /** Optional import to inject if absent (e.g. `java.time.Instant`). */
            val import: String? = null,
            /** DB column the property maps to. Defaults to [propertyName] when
             *  the naming policy round-trips (so no `@DbField(name = …)` is needed). */
            val columnName: String? = null,
            val explicitColumnName: Boolean = false,
            val primaryKey: Boolean = false,
            val autoIncrement: Boolean = false,
        ) : Edit {
            /** View this edit as a [NewColumn] so [renderProperty] can format
             *  the inserted line identically to fresh-file generation. */
            internal fun toNewColumn(): NewColumn = NewColumn(
                propName = propertyName,
                columnName = columnName ?: propertyName,
                kotlinType = kotlinType,
                nullable = nullable,
                primaryKey = primaryKey,
                autoIncrement = autoIncrement,
                import = import,
                explicitColumnName = explicitColumnName,
            )
        }

        /** Annotate an existing property with `@Transient` (kotlin.jvm.Transient). */
        data class MarkTransient(
            override val className: String,
            val propertyName: String,
        ) : Edit

        /** Add a supertype to an existing class declaration (e.g. `: ByDb()`). */
        data class AddSupertype(
            override val className: String,
            /** The supertype clause body without the leading colon (e.g. `"ByDb()"`). */
            val superType: String,
            /** Import to inject if absent. */
            val import: String? = null,
        ) : Edit
    }

    /**
     * Plan to create a brand-new entity .kt file for a table that has no Kotlin
     * counterpart yet. Distinct from [Edit] because the target file does not
     * exist; PSI splicing doesn't apply.
     */
    data class NewEntity(
        val className: String,
        val packageName: String,
        val targetDir: Path,
        val tableNameOverride: String? = null,
        val columns: List<NewColumn>,
        /** Optional supertype clause body (e.g. `"ByDb()"`). Null = no inheritance. */
        val superType: String? = null,
        /** Optional import for [superType] (e.g. `onl.ycode.stormify.ByDb`). */
        val superTypeImport: String? = null,
    ) {
        val targetPath: Path get() = targetDir.resolve("$className.kt")
    }

    /** A column definition for [NewEntity]; carries the bits needed to render a property line. */
    data class NewColumn(
        val propName: String,
        val columnName: String,
        val kotlinType: String,
        val nullable: Boolean,
        val primaryKey: Boolean = false,
        val autoIncrement: Boolean = false,
        /** Optional import to add if the file doesn't already have it. */
        val import: String? = null,
        /** True when [columnName] differs from what the naming policy would
         *  produce from [propName], so the generator must emit `name = "…"`. */
        val explicitColumnName: Boolean = false,
    )

    /**
     * Materialize a [NewEntity] as a fresh `.kt` file. Returns the written path,
     * or null when the file already exists (we never overwrite).
     */
    fun createEntity(entity: NewEntity, style: EntityStyle = EntityStyle.DEFAULT): Path? {
        if (Files.exists(entity.targetPath)) return null
        Files.createDirectories(entity.targetDir)
        Files.writeString(entity.targetPath, renderEntitySource(entity, style))
        return entity.targetPath
    }

    /** Pre-compute what the new file would contain — used by the diff preview. */
    fun renderEntitySource(entity: NewEntity, style: EntityStyle = EntityStyle.DEFAULT): String {
        // Property delegates (`by db(...)`) cannot live on primary-constructor
        // parameters, so AutoTable is incompatible with ConstructorStyle.ALL.
        // For ALL we silently downgrade to ID_ONLY; ID_ONLY and NONE are kept
        // as the user prefers them.
        val autoTable = entity.superType == "AutoTable()"
        val effectiveStyle =
            if (autoTable && style.constructorStyle == ConstructorStyle.ALL)
                style.copy(constructorStyle = ConstructorStyle.ID_ONLY)
            else style
        val sb = StringBuilder()
        sb.appendLine("package ${entity.packageName}")
        sb.appendLine()
        val imports = sortedSetOf("onl.ycode.stormify.DbField", "onl.ycode.stormify.DbTable")
        entity.columns.mapNotNull { it.import }.forEach { imports += it }
        entity.superTypeImport?.let { imports += it }
        if (autoTable) imports += "onl.ycode.stormify.db"
        imports.forEach { sb.appendLine("import $it") }
        sb.appendLine()
        val tableAttr = entity.tableNameOverride?.let { "(name = \"$it\")" } ?: ""
        sb.appendLine("@DbTable$tableAttr")

        val ctorCols = entity.columns.filter { goesInConstructor(it, effectiveStyle) }
        val bodyCols = entity.columns - ctorCols.toSet()
        // `data class` is incompatible with explicit base classes — fall back to plain
        // `class` whenever a supertype is requested, so the generated source compiles.
        val classKw = if (entity.superType != null) "class" else "data class"
        val baseClause = entity.superType?.let { " : $it" } ?: ""

        if (ctorCols.isEmpty() && bodyCols.isEmpty()) {
            sb.appendLine("class ${entity.className}$baseClause")
            return sb.toString()
        }

        if (ctorCols.isEmpty()) {
            sb.appendLine("class ${entity.className}$baseClause {")
        } else {
            sb.appendLine("$classKw ${entity.className}(")
            ctorCols.forEach { sb.appendLine("    ${renderProperty(it, trailingComma = true, byDb = false)}") }
            if (bodyCols.isEmpty()) {
                sb.appendLine(")$baseClause")
                return sb.toString()
            }
            sb.appendLine(")$baseClause {")
        }
        bodyCols.forEachIndexed { i, c ->
            if (effectiveStyle.bodyBlankLine && i > 0) sb.appendLine()
            // PK never uses `by db(...)` — it's the lookup key the delegate would
            // *use* to populate, so it has to be a plain mutable var.
            sb.appendLine("    ${renderProperty(c, trailingComma = false, byDb = autoTable && !c.primaryKey)}")
        }
        sb.appendLine("}")
        return sb.toString()
    }

    /** Public entry point so the diff pane can render exactly the same property
     *  line the new-entity writer would produce, instead of duplicating the
     *  formatting. */
    fun renderPropertyLine(c: NewColumn, byDb: Boolean = false): String =
        renderProperty(c, trailingComma = false, byDb = byDb)

    private fun renderProperty(c: NewColumn, trailingComma: Boolean, byDb: Boolean = false): String {
        val annot = buildString {
            val parts = mutableListOf<String>()
            if (c.primaryKey) parts += "primaryKey = true"
            if (c.autoIncrement) parts += "autoIncrement = true"
            if (c.explicitColumnName) parts += "name = \"${c.columnName}\""
            if (parts.isNotEmpty()) append("@DbField(${parts.joinToString(", ")})\n    ")
        }
        val typeStr = c.kotlinType + if (c.nullable) "?" else ""
        val default = defaultExprFor(c.kotlinType, c.nullable)
        val tail = if (trailingComma) "," else ""
        val initializer = if (byDb) "by db($default)" else "= $default"
        return "${annot}var ${safeKotlinIdentifier(c.propName)}: $typeStr $initializer$tail"
    }

    private fun goesInConstructor(c: NewColumn, style: EntityStyle): Boolean = when (style.constructorStyle) {
        ConstructorStyle.ALL -> true
        ConstructorStyle.NONE -> false
        ConstructorStyle.ID_ONLY -> c.primaryKey
    }

    /** Apply [edits] to [path] in-place. Returns true if any change was made. */
    fun applyEdits(env: PsiEnvironment, path: Path, edits: List<Edit>, style: EntityStyle = EntityStyle.DEFAULT): Boolean {
        val patched = previewEdits(env, path, edits, style) ?: return false
        Files.writeString(path, patched)
        return true
    }

    /** Compute the post-edit file text without writing to disk. */
    fun previewEdits(env: PsiEnvironment, path: Path, edits: List<Edit>, style: EntityStyle = EntityStyle.DEFAULT): String? {
        if (edits.isEmpty()) return null
        val ktFile = env.parse(path) ?: return null
        var text = ktFile.text
        val planned = planAll(ktFile, edits, style).sortedByDescending { it.offset }
        if (planned.isEmpty()) return null
        for (splice in planned) {
            text = text.substring(0, splice.offset) + splice.insertion + text.substring(splice.offset)
        }
        val existingImports = ktFile.importDirectives.mapNotNull { it.importedFqName?.asString() }.toMutableSet()
        val needed = mutableListOf<String>()
        if (edits.any { it is Edit.MarkTransient } && "kotlin.jvm.Transient" !in existingImports) {
            needed += "kotlin.jvm.Transient"; existingImports += "kotlin.jvm.Transient"
        }
        for (edit in edits) {
            val imp = when (edit) {
                is Edit.AddProperty -> edit.import
                is Edit.AddSupertype -> edit.import
                else -> null
            } ?: continue
            if (imp !in existingImports) { needed += imp; existingImports += imp }
        }
        for (imp in needed) text = injectImport(text, imp)
        return text
    }

    /** Plan a batch of edits. AddProperty edits are grouped per class and
     *  routed to either the constructor or the body according to [style]. */
    private fun planAll(ktFile: KtFile, edits: List<Edit>, style: EntityStyle): List<Splice> {
        val out = mutableListOf<Splice>()
        val addByClass: Map<String, List<Edit.AddProperty>> = edits
            .filterIsInstance<Edit.AddProperty>()
            .groupBy { it.className }
        for ((className, adds) in addByClass) {
            val klass = findClass(ktFile, className) ?: continue
            val ctorAdds = adds.filter { goesInConstructor(it, style) }
            val bodyAdds = adds - ctorAdds.toSet()
            ctorAdds.forEach { planAddPropertyToConstructor(klass, it)?.let(out::add) }
            if (bodyAdds.isNotEmpty()) {
                if (klass.body == null) {
                    out += planAddPropertiesToEmptyBody(klass, bodyAdds, style)
                } else {
                    bodyAdds.forEach { planAddProperty(klass, it, style)?.let(out::add) }
                }
            }
        }
        for (edit in edits) {
            when (edit) {
                is Edit.MarkTransient -> plan(ktFile, edit)?.let(out::add)
                is Edit.AddSupertype -> {
                    val klass = findClass(ktFile, edit.className) ?: continue
                    planAddSupertype(klass, edit)?.let(out::add)
                }
                else -> {}
            }
        }
        return out
    }

    /** Insert `: SuperType` into an existing class header. Skipped when the class
     *  already declares any supertype (we don't disturb user choices). */
    private fun planAddSupertype(klass: KtClass, edit: Edit.AddSupertype): Splice? {
        if (klass.superTypeListEntries.isNotEmpty()) return null
        val text = klass.containingKtFile.text
        val anchor = klass.body?.lBrace?.textRange?.startOffset
            ?: (klass.textRange.endOffset)
        // Insert just before the `{` (or at end of header for body-less classes),
        // trimming any trailing whitespace so the `: …` sits flush against `)`.
        var insertAt = anchor
        while (insertAt > 0 && text[insertAt - 1] == ' ') insertAt--
        return Splice(insertAt, " : ${edit.superType}")
    }

    private fun goesInConstructor(edit: Edit.AddProperty, style: EntityStyle): Boolean = when (style.constructorStyle) {
        ConstructorStyle.ALL -> true
        ConstructorStyle.NONE -> false
        // We never know whether an injected property is the PK or not (the
        // edit doesn't carry that flag), but in practice schema-sync only adds
        // non-PK columns via AddProperty (DB columns missing from the entity
        // are never marked PK). So ID_ONLY → body for AddProperty.
        ConstructorStyle.ID_ONLY -> false
    }

    /** Splice a new `var name: Type = default` parameter into the primary
     *  constructor's value-parameter list, just before the closing `)`. */
    private fun planAddPropertyToConstructor(klass: KtClass, edit: Edit.AddProperty): Splice? {
        val ctor = klass.primaryConstructor ?: return null
        val list = ctor.valueParameterList ?: return null
        val text = klass.containingKtFile.text
        val rParen = list.text.lastIndexOf(')')
        if (rParen < 0) return null
        val rParenAbs = list.textRange.startOffset + rParen
        val params = list.parameters
        val indent = if (params.isNotEmpty()) {
            val first = params.first()
            var i = first.textRange.startOffset
            while (i > 0 && text[i - 1] != '\n') i--
            text.substring(i, first.textRange.startOffset)
        } else "    "
        // Insert just before the line containing the rParen so we keep its alignment.
        var insertAt = rParenAbs
        while (insertAt > 0 && text[insertAt - 1] == ' ') insertAt--
        if (insertAt > 0 && text[insertAt - 1] == '\n') insertAt--
        val rendered = renderProperty(edit.toNewColumn(), trailingComma = true)
        return Splice(insertAt + 1, "$indent$rendered\n")
    }

    private data class Splice(val offset: Int, val insertion: String)

    private fun plan(ktFile: KtFile, edit: Edit): Splice? {
        val klass = findClass(ktFile, edit.className) ?: return null
        return when (edit) {
            is Edit.AddProperty -> planAddProperty(klass, edit, EntityStyle.DEFAULT)
            is Edit.MarkTransient -> planMarkTransient(klass, edit)
            is Edit.AddSupertype -> planAddSupertype(klass, edit)
        }
    }

    private fun planAddProperty(klass: KtClass, edit: Edit.AddProperty, style: EntityStyle): Splice? {
        val body = klass.body ?: return planAddPropertiesToEmptyBody(klass, listOf(edit), style)
        val closingBrace = body.rBrace ?: return null
        val indent = inferIndent(body) ?: "    "
        val prefix = if (style.bodyBlankLine) "\n" else ""
        val line = "${prefix}${indent}${renderProperty(edit.toNewColumn(), trailingComma = false)}\n"
        val braceOffset = closingBrace.textRange.startOffset
        val text = klass.containingKtFile.text
        var insertAt = braceOffset
        while (insertAt > 0 && text[insertAt - 1] == ' ') insertAt--
        return Splice(insertAt, line)
    }

    /** A single splice that wraps every queued [edits] in one `{ … }` body. */
    private fun planAddPropertiesToEmptyBody(klass: KtClass, edits: List<Edit.AddProperty>, style: EntityStyle): Splice {
        val end = klass.textRange.endOffset
        val sep = if (style.bodyBlankLine) "\n\n" else "\n"
        val lines = edits.joinToString(sep) {
            "    ${renderProperty(it.toNewColumn(), trailingComma = false)}"
        }
        return Splice(end, " {\n$lines\n}")
    }

    private fun planMarkTransient(klass: KtClass, edit: Edit.MarkTransient): Splice? {
        // Property either in primary constructor or class body.
        val ctorParam = klass.primaryConstructor?.valueParameters
            ?.firstOrNull { it.name == edit.propertyName && it.hasValOrVar() }
        if (ctorParam != null) return planAnnotateParameter(ctorParam)
        val prop = klass.declarations.filterIsInstance<KtProperty>()
            .firstOrNull { it.name == edit.propertyName }
        if (prop != null) return planAnnotateProperty(prop)
        return null
    }

    private fun planAnnotateParameter(param: KtParameter): Splice? {
        if (param.annotationEntries.any { it.shortName?.asString() == "Transient" }) return null
        // Insert `@Transient ` before the parameter (or before val/var keyword).
        val offset = param.textRange.startOffset
        return Splice(offset, "@Transient ")
    }

    private fun planAnnotateProperty(prop: KtProperty): Splice? {
        if (prop.annotationEntries.any { it.shortName?.asString() == "Transient" }) return null
        val text = prop.containingKtFile.text
        val offset = prop.textRange.startOffset
        // Find the leading indentation on this line.
        var lineStart = offset
        while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--
        val indent = text.substring(lineStart, offset)
        return Splice(offset, "@Transient\n$indent")
    }

    private fun inferIndent(body: KtClassBody): String? {
        val text = body.containingKtFile.text
        val firstDecl = body.declarations.firstOrNull() ?: return null
        val start = firstDecl.textRange.startOffset
        var i = start
        while (i > 0 && text[i - 1] != '\n') i--
        val ws = text.substring(i, start)
        return ws.takeIf { it.isNotEmpty() && it.all { c -> c == ' ' || c == '\t' } }
    }

    private fun defaultExprFor(kotlinType: String, nullable: Boolean): String {
        if (nullable) return "null"
        val base = kotlinType.removeSuffix("?").trim()
        return when (base) {
            "String", "kotlin.String" -> "\"\""
            "Int", "Integer", "kotlin.Int" -> "0"
            "Long", "kotlin.Long" -> "0L"
            "Short", "kotlin.Short" -> "0"
            "Byte", "kotlin.Byte" -> "0"
            "Float", "kotlin.Float" -> "0f"
            "Double", "kotlin.Double" -> "0.0"
            "Boolean", "kotlin.Boolean" -> "false"
            "BigDecimal", "java.math.BigDecimal" -> "java.math.BigDecimal.ZERO"
            "BigInteger", "java.math.BigInteger" -> "java.math.BigInteger.ZERO"
            else -> "TODO()"
        }
    }

    private fun findClass(ktFile: KtFile, name: String): KtClass? {
        val target = name.substringAfterLast('.')
        for (decl in ktFile.declarations) {
            if (decl is KtClass && (decl.name == target || decl.fqName?.asString() == name)) return decl
            if (decl is KtClass) decl.declarations.filterIsInstance<KtClass>()
                .firstOrNull { it.name == target }
                ?.let { return it }
        }
        return null
    }

    private fun injectImport(source: String, fqn: String): String {
        val packageRegex = Regex("""^package\s+[^\n]+""", RegexOption.MULTILINE)
        val importLine = "import $fqn"
        if (source.contains(importLine)) return source

        val firstImport = Regex("""^import\s+[^\n]+""", RegexOption.MULTILINE).find(source)
        if (firstImport != null) {
            return source.substring(0, firstImport.range.first) +
                "$importLine\n" +
                source.substring(firstImport.range.first)
        }
        val pkg = packageRegex.find(source)
        if (pkg != null) {
            val end = pkg.range.last + 1
            return source.substring(0, end) + "\n\n$importLine\n" + source.substring(end)
        }
        return "$importLine\n" + source
    }
}
