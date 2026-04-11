package onl.ycode.stormify.annproc

import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeParameter
import com.google.devtools.ksp.symbol.Variance

private const val DB_TABLE = "onl.ycode.stormify.DbTable"
private const val ENTITY = "javax.persistence.Entity"
private const val TABLE = "javax.persistence.Table"

private const val DB_FIELD = "onl.ycode.stormify.DbField"
private const val ID = "javax.persistence.Id"
private const val TRANSIENT = "javax.persistence.Transient"
private const val COLUMN = "javax.persistence.Column"
private const val JOIN_COLUMN = "javax.persistence.JoinColumn"
private const val SEQUENCE = "javax.persistence.SequenceGenerator"
private const val ENUMERATED = "javax.persistence.Enumerated"

private const val AUTO_TABLE = "onl.ycode.stormify.AutoTable"

/**
 * Qualified names of the four **roots** of the Kotlin and Java collection hierarchies.
 * Any type that is (or transitively extends) one of these is not persistable as a
 * single column. We only need to know the roots — the recursive supertype walk in
 * [isCollectionOrMap] naturally reaches them from any subtype (`List`, `ArrayList`,
 * `HashMap`, `ConcurrentHashMap`, user-defined subclasses, …). This mirrors the JVM
 * reflection path's `Collection.isAssignableFrom` / `Map.isAssignableFrom` filter.
 */
private val COLLECTION_OR_MAP_ROOTS = setOf(
    "kotlin.collections.Iterable",
    "kotlin.collections.Map",
    "java.lang.Iterable",
    "java.util.Map",
)

/** Returns `true` if [type] is, or transitively extends, a collection or map root. */
private fun isCollectionOrMap(type: KSClassDeclaration): Boolean {
    if (type.qualifiedName?.asString() in COLLECTION_OR_MAP_ROOTS) return true
    for (sup in type.superTypes) {
        val decl = sup.resolve().declaration
        if (decl is KSClassDeclaration && isCollectionOrMap(decl)) return true
    }
    return false
}

private val KOTLIN_BUILTINS = mapOf(
    "kotlin.Int" to "Int", "kotlin.Long" to "Long", "kotlin.Short" to "Short",
    "kotlin.Byte" to "Byte", "kotlin.Float" to "Float", "kotlin.Double" to "Double",
    "kotlin.Boolean" to "Boolean", "kotlin.Char" to "Char", "kotlin.String" to "String",
    "kotlin.ByteArray" to "ByteArray", "kotlin.CharArray" to "CharArray",
)

class EntityProperty(declaration: KSPropertyDeclaration, entity: KSClassDeclaration) {
    val name = declaration.simpleName.getShortName()
    /** Raw class reference used with `castTo(Xxx::class, ...)` — no generic parameters. */
    val type: String
    /** Full property type written as it appears in the source, e.g. `List<Foo>` or `Foo?`. */
    val fullType: String
    val nullable: Boolean
    val dbname: String
    val sequence: String
    val updatable: Boolean
    val insertable: Boolean
    val primary: Boolean
    val autoIncrement: Boolean
    val isReference: Boolean
    val isEnum: Boolean
    val enumAsString: Boolean
    /**
     * True for properties whose declared type is — or extends — any `Collection`/`Map`
     * from either the Kotlin stdlib (`kotlin.collections.*`) or the Java standard
     * library (`java.util.*`). Such properties cannot map to a single database column
     * and are skipped from the generated [EntityMeta], matching the JVM reflection
     * path's `Collection.isAssignableFrom` / `Map.isAssignableFrom` filter.
     */
    val skip: Boolean

    init {
        val resolved = declaration.type.resolve()
        nullable = resolved.isMarkedNullable
        type = rawTypeName(resolved)
        fullType = fullTypeName(resolved)

        // Collect annotations from the property declaration AND constructor parameters
        // (Kotlin 2.x defaults annotations on constructor `var`/`val` params to the parameter
        // target, not the property — pick them up here so users don't need
        // -Xannotation-default-target=param-property). Walk the class hierarchy for inherited props.
        val ctorParamAnnotations = run {
            val propName = declaration.simpleName.asString()
            var cls: KSClassDeclaration? = entity
            while (cls != null) {
                val param = cls.primaryConstructor?.parameters?.find { it.name?.asString() == propName }
                if (param != null) return@run param.annotations.toList()
                cls = cls.superTypes.firstOrNull()?.resolve()?.declaration as? KSClassDeclaration
            }
            emptyList()
        }
        val allAnnotations = declaration.annotations.toList() + ctorParamAnnotations

        var _dbname = ""
        var _sequence = ""
        var _updt = true
        var _insertable = true
        var _primary = false
        var _autoIncrement = false
        var _enumAsString = false
        allAnnotations.forEach { ann ->
            when (ann.annotationType.resolve().declaration.qualifiedName?.asString()) {
                DB_FIELD -> {
                    _enumAsString = ann.arguments.firstOrNull { it.name?.asString() == "enumAsString" }?.value?.toString()
                        ?.toBoolean() ?: false
                    _dbname = ann.arguments.firstOrNull { it.name?.asString() == "name" }?.value?.toString() ?: ""
                    _primary = ann.arguments.firstOrNull { it.name?.asString() == "primaryKey" }?.value?.toString()
                        ?.toBoolean() ?: false
                    _autoIncrement = ann.arguments.firstOrNull { it.name?.asString() == "autoIncrement" }?.value?.toString()
                        ?.toBoolean() ?: false
                    _sequence =
                        ann.arguments.firstOrNull { it.name?.asString() == "primarySequence" }?.value?.toString() ?: ""
                    _updt =
                        ann.arguments.firstOrNull { it.name?.asString() == "updatable" }?.value?.toString()?.toBoolean()
                            ?: true
                    _insertable = ann.arguments.firstOrNull { it.name?.asString() == "creatable" }?.value?.toString()
                        ?.toBoolean() ?: true
                }

                COLUMN, JOIN_COLUMN -> {
                    _dbname = ann.arguments.firstOrNull { it.name?.asString() == "name" }?.value?.toString() ?: ""
                    _updt =
                        ann.arguments.firstOrNull { it.name?.asString() == "updatable" }?.value?.toString()?.toBoolean()
                            ?: true
                    _insertable = ann.arguments.firstOrNull { it.name?.asString() == "insertable" }?.value?.toString()
                        ?.toBoolean() ?: true
                }

                SEQUENCE -> _sequence = ann.arguments.firstOrNull { it.name?.asString() == "name" }?.value?.toString() ?: ""
                ID -> _primary = true
                ENUMERATED -> {
                    val enumType = ann.arguments.firstOrNull { it.name?.asString() == "value" }?.value?.toString() ?: ""
                    if (enumType.endsWith("STRING")) _enumAsString = true
                }
            }
        }
        dbname = _dbname.ifEmpty { name }
        sequence = _sequence
        updatable = _updt
        insertable = _insertable
        primary = _primary
        autoIncrement = _autoIncrement

        val typeDecl = resolved.declaration
        isEnum = typeDecl is KSClassDeclaration && typeDecl.classKind == ClassKind.ENUM_CLASS
        enumAsString = _enumAsString && isEnum
        isReference = !isEnum && typeDecl is KSClassDeclaration && (
                typeDecl.annotations.any { ann ->
                    ann.annotationType.resolve().declaration.qualifiedName?.asString() in setOf(DB_TABLE, ENTITY)
                } || typeDecl.superTypes.any { sup ->
                    sup.resolve().declaration.qualifiedName?.asString() == AUTO_TABLE
                })

        // Collection- and map-typed properties (e.g. `var children by lazyDetails<AutoChildEntity>()`,
        // or any `List<X>` / `Set<X>` / `Map<K, V>` declared on an entity) cannot map to a
        // single database column and are skipped entirely from the generated EntityMeta.
        // This mirrors the JVM reflection path which uses `Collection.isAssignableFrom` /
        // `Map.isAssignableFrom` at runtime, so both discovery paths agree on which fields
        // are persistable.
        skip = typeDecl is KSClassDeclaration && isCollectionOrMap(typeDecl)
    }

    companion object {
        fun find(entity: KSClassDeclaration): Collection<EntityProperty> {
            // Also check constructor parameter annotations for @Transient
            val ctorParamNames = entity.primaryConstructor?.parameters
                ?.filter { p -> p.annotations.any { it.annotationType.resolve().declaration.qualifiedName?.asString() == TRANSIENT } }
                ?.mapNotNull { it.name?.asString() }?.toSet() ?: emptySet()
            return entity.getAllProperties().mapNotNull {
                val propName = it.simpleName.asString()
                // Skip library-internal backticked fields (convention: names starting with "!"
                // are private/hidden storage — e.g. `!stormify`, `!hasRun`, `!siblingGroup`).
                if (propName.startsWith("!")) return@mapNotNull null
                if (propName in ctorParamNames) return@mapNotNull null
                if (it.annotations.any { ann -> ann.annotationType.resolve().declaration.qualifiedName?.asString() == TRANSIENT })
                    return@mapNotNull null
                EntityProperty(it, entity).takeUnless { p -> p.skip }
            }.toList()
        }

        fun findTableName(declaration: KSClassDeclaration): String {
            var name = ""
            declaration.annotations.forEach { ann ->
                when (ann.annotationType.resolve().declaration.qualifiedName?.asString()) {
                    DB_TABLE, ENTITY, TABLE ->
                        name = ann.arguments.firstOrNull { it.name?.asString() == "name" }?.value?.toString() ?: ""
                }
            }
            return name
        }

        /**
         * Returns the class name (without generic parameters) used with `::class`.
         * Type parameters are mapped to `kotlin.Any` because KClass references cannot point at
         * type variables at runtime.
         */
        private fun rawTypeName(type: KSType): String {
            val decl = type.declaration
            if (decl is KSTypeParameter) return "kotlin.Any"
            val qn = decl.qualifiedName?.asString() ?: "kotlin.Any"
            return KOTLIN_BUILTINS[qn] ?: qn
        }

        /**
         * Returns the fully-qualified property type as it appears in source, including generic
         * arguments and nullability. Used for unchecked casts in setters so the assignment
         * compiles on strict-typing targets (native).
         */
        private fun fullTypeName(type: KSType): String {
            val decl = type.declaration
            if (decl is KSTypeParameter) return if (type.isMarkedNullable) "kotlin.Any?" else "kotlin.Any"
            val base = decl.qualifiedName?.asString() ?: "kotlin.Any"
            val args = type.arguments
            val rendered = if (args.isEmpty()) base else {
                args.joinToString(separator = ", ", prefix = "$base<", postfix = ">") { arg ->
                    val argType = arg.type?.resolve()
                    val inner = if (argType == null) "*" else fullTypeName(argType)
                    when (arg.variance) {
                        Variance.COVARIANT -> "out $inner"
                        Variance.CONTRAVARIANT -> "in $inner"
                        Variance.STAR -> "*"
                        else -> inner
                    }
                }
            }
            return if (type.isMarkedNullable) "$rendered?" else rendered
        }
    }
}
