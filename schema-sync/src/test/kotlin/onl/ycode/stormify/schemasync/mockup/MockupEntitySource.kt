package onl.ycode.stormify.schemasync.mockup

import onl.ycode.stormify.schemasync.model.NamingPolicy
import java.io.File

/**
 * Emits one Kotlin file per [EntitySpec] under the chosen package
 * directory. Output style mirrors `demo/sources-ctor-pk`: PK in the
 * constructor, body declarations on their own lines so schema-sync's
 * style detector picks the same defaults as our existing demos.
 *
 * Annotation strategy: `@DbTable(name=…)` and `@DbField(name=…)` are
 * **omitted** when the class/property name round-trips through
 * [NamingPolicy.LOWER_CASE_WITH_UNDERSCORES] back to the DB-side name
 * — schema-sync derives the same name automatically. Entities whose
 * names don't align (phantom targets, paired tables, views, synonyms,
 * compact `t<n>` solos) carry the explicit annotation. Mixing both
 * styles in one fixture matches what real-world projects look like.
 */
object MockupEntitySource {

    /** Default Kotlin package the emitter writes into. */
    const val PACKAGE = "com.example.mockup"

    private val POLICY = NamingPolicy.LOWER_CASE_WITH_UNDERSCORES

    fun writeAll(spec: MockupSpec, root: File): Int {
        val dir = File(root, PACKAGE.replace('.', '/'))
        dir.mkdirs()
        for (entity in spec.entities) {
            File(dir, "${entity.className}.kt").writeText(render(entity))
        }
        return spec.entities.size
    }

    private fun render(e: EntitySpec): String {
        val imports = collectImports(e.fields).filter { '.' in it }.toSortedSet()
        val sb = StringBuilder()
        sb.appendLine("package $PACKAGE")
        sb.appendLine()
        sb.appendLine("import onl.ycode.stormify.DbField")
        sb.appendLine("import onl.ycode.stormify.DbTable")
        for (imp in imports) sb.appendLine("import $imp")
        sb.appendLine()
        sb.appendLine(dbTableLine(e))
        val pk = e.fields.firstOrNull { it.primaryKey }
        if (pk != null) {
            sb.appendLine("data class ${e.className}(")
            sb.appendLine("    ${dbFieldLine(pk, isPk = true)}")
            sb.appendLine("    var ${pk.propName}: ${simpleType(pk.kotlinType)} = ${pk.initLiteral ?: "0L"},")
            sb.appendLine(") {")
            for (f in e.fields.filter { !it.primaryKey }) {
                sb.appendLine()
                dbFieldLine(f, isPk = false)?.let { sb.appendLine("    $it") }
                sb.appendLine("    var ${f.propName}: ${typeWithNullability(f)} = ${initFor(f)}")
            }
            sb.appendLine("}")
        } else {
            sb.appendLine("class ${e.className} {")
            for (f in e.fields) {
                dbFieldLine(f, isPk = false)?.let { sb.appendLine("    $it") }
                sb.appendLine("    var ${f.propName}: ${typeWithNullability(f)} = ${initFor(f)}")
            }
            sb.appendLine("}")
        }
        return sb.toString()
    }

    /** `@DbTable` annotation line: omits `name=` when the class name
     *  round-trips through the policy back to [EntitySpec.tableName]. */
    private fun dbTableLine(e: EntitySpec): String {
        val canonical = POLICY.fromKotlin(e.className) == e.tableName
        return if (canonical) "@DbTable" else "@DbTable(name = \"${e.tableName}\")"
    }

    /** `@DbField` annotation line, or null when the field is a non-PK
     *  whose property name round-trips canonically — schema-sync will
     *  pick up the column name from the property automatically. */
    private fun dbFieldLine(f: EntityField, isPk: Boolean): String? {
        val canonicalColumn = POLICY.fromKotlin(f.propName) == f.columnName
        if (!isPk && canonicalColumn) return null
        val parts = mutableListOf<String>()
        if (!canonicalColumn) parts += "name = \"${f.columnName}\""
        if (isPk) parts += "primaryKey = true"
        if (isPk) parts += "autoIncrement = true"
        return "@DbField(${parts.joinToString(", ")})"
    }

    private fun collectImports(fields: List<EntityField>): Set<String> =
        fields.map { it.kotlinType }.filter { '.' in it }.toSet()

    private fun simpleType(fqcn: String): String = fqcn.substringAfterLast('.')

    private fun typeWithNullability(f: EntityField): String {
        val simple = simpleType(f.kotlinType)
        return if (f.nullable) "$simple?" else simple
    }

    /**
     * Fallback initialiser for non-null fields that don't have an explicit
     * [EntityField.initLiteral]. Uses non-extractable Kotlin expressions
     * (function calls, property references, parenthesised literals) so
     * the entity scanner reports `defaultLiteral = null` — that way a
     * column with no DB default still compares as SYNCED (both sides
     * null) instead of triggering a false-positive default mismatch.
     */
    private fun initFor(f: EntityField): String {
        f.initLiteral?.let { return it }
        if (f.nullable) return "null"
        return when (val t = simpleType(f.kotlinType)) {
            "String" -> "String()"
            "Int" -> "Int.MIN_VALUE"
            "Long" -> "Long.MIN_VALUE"
            "Short" -> "Short.MIN_VALUE"
            "Double" -> "Double.MIN_VALUE"
            "Float" -> "Float.MIN_VALUE"
            "Boolean" -> "(false)"
            "BigDecimal" -> "java.math.BigDecimal(\"0\")"
            "LocalDate" -> "java.time.LocalDate.MIN"
            "LocalDateTime" -> "java.time.LocalDateTime.MIN"
            "ByteArray" -> "ByteArray(0)"
            else -> error("no init template for $t")
        }
    }
}
