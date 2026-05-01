package onl.ycode.stormify.schemasync.entity.source

import onl.ycode.stormify.schemasync.entity.EntityCatalog
import onl.ycode.stormify.schemasync.entity.EntityField
import onl.ycode.stormify.schemasync.entity.KotlinEntity
import onl.ycode.stormify.schemasync.entity.KotlinTypeMapper
import org.jetbrains.kotlin.psi.KtAnnotated
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtTypeReference
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.streams.asSequence

/**
 * Walks one or more source roots, parses every `*.kt` file via [PsiEnvironment],
 * and produces an [EntityCatalog] containing every class annotated with `@DbTable`.
 *
 * Convention-by-name fallback: if a class has properties with `@DbField` but no
 * `@DbTable`, we still treat it as an entity (matches stormify's runtime behavior).
 *
 * FK columns are recorded with [EntityField.referencedEntity] = simple class name;
 * resolution to an actual PK type is a separate post-processing pass.
 */
object EntityScanner {

    /** Convert camelCase to snake_case (matches stormify's default LOWER_CASE_WITH_UNDERSCORES). */
    private fun snake(s: String): String = buildString {
        for ((i, c) in s.withIndex()) {
            if (c.isUpperCase() && i > 0) append('_')
            append(c.lowercaseChar())
        }
    }

    /** Type names that are known scalars or deterministic — never references. */
    private val KNOWN_NON_REFERENCE = setOf(
        "String", "CharSequence",
        "Int", "Integer", "Long", "Short", "Byte",
        "Float", "Double", "BigDecimal", "BigInteger",
        "Boolean",
        "LocalDate", "LocalTime", "LocalDateTime",
        "Instant", "OffsetDateTime", "ZonedDateTime",
        "Date", "Timestamp", "Time",
        "ByteArray", "CharArray",
        "UUID",
        "Any", "Unit",
    )

    /** Generic container types skipped entirely (lazy collections, maps). */
    private val COLLECTION_HEADS = setOf(
        "List", "MutableList", "Set", "MutableSet", "Map", "MutableMap",
        "Collection", "MutableCollection", "Iterable", "MutableIterable",
        "Array", "Sequence",
        "Lazy", // stormify lazy refs — never a column
        "PersistentList", "PersistentSet", "PersistentMap",
        "ImmutableList", "ImmutableSet", "ImmutableMap",
    )

    fun scan(roots: List<Path>): EntityCatalog {
        val files = roots.flatMap { root ->
            if (!Files.exists(root)) emptyList()
            else if (root.isDirectory())
                Files.walk(root).asSequence()
                    .filter { it.extension == "kt" && Files.isRegularFile(it) }
                    .toList()
            else listOf(root)
        }

        val entities = mutableListOf<KotlinEntity>()
        PsiEnvironment().use { env ->
            for (path in files) {
                val ktFile = env.parse(path) ?: continue
                entities += extractEntities(ktFile, path.toString())
            }
        }
        return resolveReferences(EntityCatalog(entities))
    }

    private fun extractEntities(file: KtFile, sourcePath: String): List<KotlinEntity> {
        val out = mutableListOf<KotlinEntity>()
        file.declarations.filterIsInstance<KtClass>().forEach { klass ->
            extractEntity(file, klass, sourcePath)?.let(out::add)
        }
        file.declarations.filterIsInstance<KtClass>().forEach { klass ->
            klass.declarations.filterIsInstance<KtClass>().forEach { nested ->
                extractEntity(file, nested, sourcePath)?.let(out::add)
            }
        }
        return out
    }

    private fun extractEntity(file: KtFile, klass: KtClass, sourcePath: String): KotlinEntity? {
        if (klass.isInterface() || klass.isEnum() || klass.isAnnotation()) return null
        val className = klass.fqName?.asString() ?: klass.name ?: return null

        val dbTable = findAnnotation(klass, "DbTable", "Table")
        val fields = collectFields(klass)
        // Opt-in: a class must declare a stormify/JPA annotation to count as an
        // entity. Matching on a property named `id` would false-positive on
        // ordinary DTOs.
        val hasDbField = fields.any { it.hasAnnotation("DbField") }
        if (dbTable == null && !hasDbField) return null
        if (fields.isEmpty()) return null

        val tableName = dbTable?.literalArg("name")?.takeIf { it.isNotBlank() }
            ?: snake(klass.name ?: return null)

        val mapped = fields.map { it.toEntityField() }
        return KotlinEntity(
            className = className,
            schema = null,
            table = tableName,
            fields = mapped,
            sourcePath = sourcePath,
        )
    }

    private fun collectFields(klass: KtClass): List<RawField> {
        val out = mutableListOf<RawField>()
        klass.primaryConstructor?.valueParameters?.forEach { param ->
            if (param.hasValOrVar()) toRaw(param)?.let(out::add)
        }
        klass.declarations.filterIsInstance<KtProperty>().forEach { prop ->
            toRaw(prop)?.let(out::add)
        }
        return out
    }

    private fun toRaw(p: KtParameter): RawField? {
        val name = p.name ?: return null
        val typeRef = p.typeReference ?: return null
        if (isTransient(p)) return null
        if (isCollection(typeRef)) return null
        val initText = p.defaultValue?.text
        return RawField(p, name, typeRef, initText)
    }

    private fun toRaw(p: KtProperty): RawField? {
        val name = p.name ?: return null
        val typeRef = p.typeReference ?: return null
        if (isTransient(p)) return null
        if (isCollection(typeRef)) return null
        val initText = p.initializer?.text
        return RawField(p, name, typeRef, initText)
    }

    private fun isTransient(target: KtAnnotated): Boolean =
        findAnnotation(target, "Transient") != null

    private fun isCollection(typeRef: KtTypeReference): Boolean {
        val head = typeRef.text.substringBefore('<').trim().removePrefix("kotlin.collections.").substringAfterLast('.')
        return head in COLLECTION_HEADS
    }

    private class RawField(
        val owner: KtAnnotated,
        val kotlinName: String,
        val typeRef: KtTypeReference,
        val initializerText: String?,
    ) {
        fun hasAnnotation(name: String) = findAnnotation(owner, name) != null

        fun toEntityField(): EntityField {
            val dbField = findAnnotation(owner, "DbField")
            val typeText = typeRef.text.trim()
            val nullable = typeText.endsWith("?")
            val baseType = typeText.removeSuffix("?").trim()
            val simpleType = baseType.substringBefore('<').substringAfterLast('.')

            val column = dbField?.literalArg("name")?.takeIf { it.isNotBlank() }
                ?: snake(kotlinName)

            val explicitPk = dbField?.boolArg("primaryKey") == true
            val conventionPk = dbField == null && kotlinName == "id"
            val primaryKey = explicitPk || conventionPk

            val isReference = baseType !in KNOWN_NON_REFERENCE &&
                simpleType !in KNOWN_NON_REFERENCE &&
                KotlinTypeMapper.categoryFor(baseType) == null &&
                !KotlinTypeMapper.isDeterministic(baseType)

            return EntityField(
                name = kotlinName,
                column = column,
                type = baseType,
                primaryKey = primaryKey,
                nullable = nullable,
                autoIncrement = dbField?.boolArg("autoIncrement") == true,
                sequence = dbField?.literalArg("primarySequence").orEmpty(),
                creatable = dbField?.boolArg("creatable") ?: true,
                updatable = dbField?.boolArg("updatable") ?: true,
                referencedEntity = if (isReference) simpleType else null,
                defaultLiteral = sanitizeLiteral(initializerText),
            )
        }
    }


    /**
     * Replaces FK field types with the target entity's PK type so the
     * downstream classifier/generator sees a concrete scalar instead of a class name.
     */
    private fun resolveReferences(catalog: EntityCatalog): EntityCatalog {
        // Build maps both ways: by FQN (preferred) and by simple-name (best-effort).
        // Entities with composite PKs are excluded from auto-resolution since a FK
        // can't simply mirror the first PK column's type.
        val singlePkEntities = catalog.entities.filter { e -> e.fields.count { it.primaryKey } == 1 }
        val pkByFqn: Map<String, String> = singlePkEntities.associate { e ->
            e.className to e.fields.first { it.primaryKey }.type
        }
        val pkBySimple: Map<String, String> = singlePkEntities
            .groupBy { it.className.substringAfterLast('.') }
            // Only auto-resolve when there's a unique simple-name match.
            .filterValues { it.size == 1 }
            .mapValues { (_, list) -> list.first().fields.first { it.primaryKey }.type }

        val resolved = catalog.entities.map { entity ->
            entity.copy(fields = entity.fields.map { f ->
                val ref = f.referencedEntity ?: return@map f
                val pkType = pkByFqn[ref] ?: pkBySimple[ref] ?: return@map f
                f.copy(type = pkType)
            })
        }
        return EntityCatalog(resolved)
    }
}

private data class AnnotationData(val named: Map<String, String>) {
    fun literalArg(key: String): String? = named[key]
    fun boolArg(key: String): Boolean? = named[key]?.let { it == "true" }
}

/**
 * Returns the initializer text only when it's a "safe" literal we can translate
 * to SQL: numeric constants, string literals, true/false. Returns null for null,
 * function calls, references, or anything that requires resolution.
 */
private fun sanitizeLiteral(text: String?): String? {
    val t = text?.trim() ?: return null
    if (t.isEmpty() || t == "null") return null
    if (t == "true" || t == "false") return t
    if (t.startsWith("\"") && t.endsWith("\"") && !t.contains("\${")) return t
    // Strict numeric literal: optional sign, digits, optional dot, optional suffix L/F/f/d.
    val numeric = Regex("""^[-+]?\d[\d_]*(\.\d[\d_]*)?[LlFfDd]?$""")
    if (numeric.matches(t)) return t
    return null
}

/**
 * Positional argument names by annotation, in declaration order. Used to map
 * `@DbTable("users")` → `name = "users"` etc., since PSI without resolution
 * doesn't bind positional args back to parameter names.
 */
private val POSITIONAL_ARG_NAMES: Map<String, List<String>> = mapOf(
    "DbTable" to listOf("name"),
    "Table" to listOf("name"),
    "DbField" to listOf("name", "primaryKey", "primarySequence", "autoIncrement", "creatable", "updatable", "enumAsString"),
)

private fun findAnnotation(target: KtAnnotated, vararg simpleNames: String): AnnotationData? {
    for (entry in target.annotationEntries) {
        val text = entry.shortName?.asString() ?: continue
        if (text in simpleNames) {
            val positionalNames = POSITIONAL_ARG_NAMES[text].orEmpty()
            val args = mutableMapOf<String, String>()
            var posIdx = 0
            for (arg in entry.valueArguments) {
                val raw = arg.getArgumentExpression()?.text ?: continue
                val unquoted = if (raw.startsWith("\"") && raw.endsWith("\"")) raw.substring(1, raw.length - 1) else raw
                val argName = arg.getArgumentName()?.asName?.asString()
                    ?: positionalNames.getOrNull(posIdx++)
                    ?: continue
                args[argName] = unquoted
            }
            return AnnotationData(args)
        }
    }
    return null
}
