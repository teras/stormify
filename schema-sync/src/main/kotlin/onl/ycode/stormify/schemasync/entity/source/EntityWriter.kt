package onl.ycode.stormify.schemasync.entity.source

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
        ) : Edit

        /** Annotate an existing property with `@Transient` (kotlin.jvm.Transient). */
        data class MarkTransient(
            override val className: String,
            val propertyName: String,
        ) : Edit
    }

    /** Apply [edits] to [path] in-place. Returns true if any change was made. */
    fun applyEdits(env: PsiEnvironment, path: Path, edits: List<Edit>): Boolean {
        if (edits.isEmpty()) return false
        val ktFile = env.parse(path) ?: return false
        var text = ktFile.text
        // Sort by descending offset so earlier offsets stay valid as we splice.
        val planned = edits.mapNotNull { plan(ktFile, it) }
            .sortedByDescending { it.offset }
        if (planned.isEmpty()) return false
        for (splice in planned) {
            text = text.substring(0, splice.offset) + splice.insertion + text.substring(splice.offset)
        }
        val needsTransientImport = edits.any { it is Edit.MarkTransient } &&
            !ktFile.importDirectives.any { it.importedFqName?.asString() == "kotlin.jvm.Transient" }
        if (needsTransientImport) {
            text = injectImport(text, "kotlin.jvm.Transient")
        }
        Files.writeString(path, text)
        return true
    }

    private data class Splice(val offset: Int, val insertion: String)

    private fun plan(ktFile: KtFile, edit: Edit): Splice? {
        val klass = findClass(ktFile, edit.className) ?: return null
        return when (edit) {
            is Edit.AddProperty -> planAddProperty(klass, edit)
            is Edit.MarkTransient -> planMarkTransient(klass, edit)
        }
    }

    private fun planAddProperty(klass: KtClass, edit: Edit.AddProperty): Splice? {
        val body = klass.body ?: return planAddPropertyToEmptyBody(klass, edit)
        val closingBrace = body.rBrace ?: return null
        val indent = inferIndent(body) ?: "    "
        val typeStr = edit.kotlinType + if (edit.nullable) "?" else ""
        val defaultExpr = defaultExprFor(edit.kotlinType, edit.nullable)
        val line = "${indent}var ${edit.propertyName}: $typeStr = $defaultExpr\n"
        // Insert just before the closing brace, preserving its leading whitespace.
        val braceOffset = closingBrace.textRange.startOffset
        // Walk back over whitespace to insert above the brace's indentation.
        val text = klass.containingKtFile.text
        var insertAt = braceOffset
        while (insertAt > 0 && text[insertAt - 1] == ' ') insertAt--
        return Splice(insertAt, line)
    }

    private fun planAddPropertyToEmptyBody(klass: KtClass, edit: Edit.AddProperty): Splice? {
        // Class without a body block (e.g. `class Foo` or `data class Foo(...)`).
        // Add a body: `class Foo { ... }`.
        val end = klass.textRange.endOffset
        val typeStr = edit.kotlinType + if (edit.nullable) "?" else ""
        val defaultExpr = defaultExprFor(edit.kotlinType, edit.nullable)
        val insertion = " {\n    var ${edit.propertyName}: $typeStr = $defaultExpr\n}"
        return Splice(end, insertion)
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
