package onl.ycode.stormify.annproc

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.FileLocation
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeParameter
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Variance

private const val DB_TABLE = "onl.ycode.stormify.DbTable"
private const val ENTITY = "javax.persistence.Entity"
private const val TABLE = "javax.persistence.Table"

private const val DB_FIELD = "onl.ycode.stormify.DbField"
private const val ID = "javax.persistence.Id"
private val TRANSIENT_ANNOTATIONS = setOf(
    "javax.persistence.Transient",
    "kotlin.jvm.Transient",
)
private const val COLUMN = "javax.persistence.Column"
private const val JOIN_COLUMN = "javax.persistence.JoinColumn"
private const val SEQUENCE = "javax.persistence.SequenceGenerator"
private const val GENERATED_VALUE = "javax.persistence.GeneratedValue"
private const val ENUMERATED = "javax.persistence.Enumerated"

private const val AUTO_TABLE = "onl.ycode.stormify.AutoTable"

/** Annotations that declare a property as a mapped column. */
private val MAPPING_ANNOTATIONS = setOf(DB_FIELD, ID, COLUMN, JOIN_COLUMN)

private fun KSAnnotation.qualifiedName(): String? =
    annotationType.resolve().declaration.qualifiedName?.asString()

/**
 * The function a delegated property delegates to (`db`, `lazy`, `observable`, ...), read
 * from its source, since KSP does not expose the delegate expression. Null when the
 * source is not available (a property compiled in another module) or not recognised.
 */
private fun KSPropertyDeclaration.delegateName(): String? {
    val loc = location as? FileLocation ?: return null
    val lines = runCatching { java.io.File(loc.filePath).readLines() }.getOrNull() ?: return null
    val text = lines.drop((loc.lineNumber - 1).coerceAtLeast(0)).take(8).joinToString(" ")
    val name = Regex.escape(simpleName.asString())
    val decl = Regex("\\b(?:val|var)\\s+$name\\b").find(text) ?: return null
    return Regex("\\bby\\s+([A-Za-z_][\\w.]*)").find(text, decl.range.last + 1)
        ?.groupValues?.get(1)?.substringAfterLast('.')
}

/** True if this annotation marks a property as transient (JPA or Kotlin). */
private fun KSAnnotation.isTransient(): Boolean =
    annotationType.resolve().declaration.qualifiedName?.asString() in TRANSIENT_ANNOTATIONS

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

internal class EntityProperty(declaration: KSPropertyDeclaration, entity: KSClassDeclaration) {
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
    /**
     * True when the generated code cannot assign the property: a `val`, or a `var`
     * whose setter is private or protected. The processor rejects such a property.
     */
    val readOnly: Boolean = !declaration.isMutable ||
            declaration.setter?.let { it.modifiers.contains(Modifier.PRIVATE) || it.modifiers.contains(Modifier.PROTECTED) } == true

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

        // Stormify and JPA annotations may be combined on one property. Every attribute
        // is merged the same way regardless of annotation order, matching the JVM
        // reflection path: a name comes from the first source that sets one (@DbField,
        // @Column, @JoinColumn), a flag that defaults to false is set by any source, and
        // a flag that defaults to true is cleared by any source.
        fun KSAnnotation.arg(name: String): String? =
            arguments.firstOrNull { it.name?.asString() == name }?.value?.toString()
        fun KSAnnotation.nameArg(name: String): String? = arg(name)?.takeIf { it.isNotBlank() }
        fun KSAnnotation.flag(name: String, default: Boolean): Boolean = arg(name)?.toBoolean() ?: default

        val byName = allAnnotations.groupBy { it.annotationType.resolve().declaration.qualifiedName?.asString() }
        fun first(qn: String) = byName[qn]?.firstOrNull()
        val dbField = first(DB_FIELD)
        val column = first(COLUMN)
        val joinColumn = first(JOIN_COLUMN)
        val sequenceGenerator = first(SEQUENCE)

        dbname = dbField?.nameArg("name") ?: column?.nameArg("name") ?: joinColumn?.nameArg("name") ?: name
        // In JPA `name` names the generator and `sequenceName` the database sequence;
        // the generator name is the sequence only when `sequenceName` is left empty.
        sequence = dbField?.nameArg("primarySequence") ?: sequenceGenerator?.nameArg("sequenceName")
            ?: sequenceGenerator?.nameArg("name") ?: ""
        updatable = dbField?.flag("updatable", true) != false &&
                column?.flag("updatable", true) != false && joinColumn?.flag("updatable", true) != false
        insertable = dbField?.flag("insertable", true) != false &&
                column?.flag("insertable", true) != false && joinColumn?.flag("insertable", true) != false
        primary = dbField?.flag("primaryKey", false) == true || first(ID) != null
        autoIncrement = dbField?.flag("autoIncrement", false) == true ||
                byName[GENERATED_VALUE].orEmpty().any { it.arg("strategy")?.endsWith("IDENTITY") == true }
        val enumString = dbField?.flag("enumAsString", false) == true ||
                byName[ENUMERATED].orEmpty().any { it.arg("value")?.endsWith("STRING") == true }

        val typeDecl = resolved.declaration
        isEnum = typeDecl is KSClassDeclaration && typeDecl.classKind == ClassKind.ENUM_CLASS
        enumAsString = enumString && isEnum
        isReference = !isEnum && typeDecl is KSClassDeclaration &&
                typeDecl.classKind == ClassKind.CLASS && (
                typeDecl.annotations.any { ann ->
                    ann.annotationType.resolve().declaration.qualifiedName?.asString() in setOf(DB_TABLE, ENTITY)
                } || typeDecl.superTypes.any { sup ->
                    sup.resolve().declaration.qualifiedName?.asString() == AUTO_TABLE
                } || typeDecl.getAllProperties().any { prop ->
                    prop.annotations.any { ann ->
                        ann.annotationType.resolve().declaration.qualifiedName?.asString() in
                            setOf(ID, DB_FIELD, COLUMN, JOIN_COLUMN)
                    }
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
        /**
         * The mapped properties of [entity]. A property is either a column, or skipped
         * because it stores nothing (computed) or is marked transient, or an error
         * reported through [logger]: the generated code must be able to assign every
         * column, so a read-only property or a delegate other than `db()` is rejected.
         */
        fun find(entity: KSClassDeclaration, logger: KSPLogger? = null): Collection<EntityProperty> {
            // Also check constructor parameter annotations for @Transient
            val ctorParamNames = entity.primaryConstructor?.parameters
                ?.filter { p -> p.annotations.any { it.isTransient() } }
                ?.mapNotNull { it.name?.asString() }?.toSet() ?: emptySet()
            val entityName = entity.simpleName.asString()
            return entity.getAllProperties().mapNotNull {
                val propName = it.simpleName.asString()
                if (propName in ctorParamNames) return@mapNotNull null
                // Transient: Java keyword, @kotlin.jvm.Transient, @javax.persistence.Transient
                // (any use-site target, so `@delegate:Transient` counts too)
                if (Modifier.JAVA_TRANSIENT in it.modifiers) return@mapNotNull null
                if (it.annotations.any { ann -> ann.isTransient() }) return@mapNotNull null
                // A computed property (`val x get() = ...`) stores nothing: not a column.
                // Silent, unless a column annotation says the author expected one.
                if (!it.hasBackingField && !it.isDelegated()) {
                    if (it.annotations.any { ann -> ann.qualifiedName() in MAPPING_ANNOTATIONS })
                        logger?.warn("Stormify: property $entityName.$propName has no backing field; " +
                                "its column annotation is ignored and it is not a column", it)
                    return@mapNotNull null
                }
                val prop = EntityProperty(it, entity).takeUnless { p -> p.skip } ?: return@mapNotNull null
                // The generated code assigns every column through the property's setter, so
                // a mutable property is a column whatever it delegates to; a read-only one
                // cannot be loaded and is rejected, with the fix that fits its shape.
                if (prop.readOnly) {
                    val delegate = if (it.isDelegated()) it.delegateName() else null
                    val message = when {
                        // A var whose setter is private or protected, delegated or not.
                        it.isMutable -> "has no public setter: make its setter public to be mapped by the annotation processor"
                        !it.isDelegated() -> "must be a var (or use by db()) to be mapped by the annotation processor"
                        delegate == "db" -> "must be a var to be mapped by the annotation processor"
                        else -> "is a read-only delegate" + (delegate?.let { d -> " ($d())" } ?: "") +
                                " the annotation processor cannot load: mark it @delegate:Transient, " +
                                "or make it a var ... by db()"
                    }
                    logger?.error("Stormify: property $entityName.$propName $message", it)
                    return@mapNotNull null
                }
                prop
            }.toList()
        }

        /**
         * Table name override: `@DbTable`, then JPA `@Table`, then JPA `@Entity` (whose name is
         * also the JPA default table name), regardless of annotation order. Same precedence
         * as the JVM reflection path. Empty when none of them sets a name.
         */
        fun findTableName(declaration: KSClassDeclaration): String {
            val names = declaration.annotations.associate { ann ->
                ann.annotationType.resolve().declaration.qualifiedName?.asString() to
                        ann.arguments.firstOrNull { it.name?.asString() == "name" }?.value?.toString()
            }
            return listOf(DB_TABLE, TABLE, ENTITY).firstNotNullOfOrNull { names[it]?.takeIf { n -> n.isNotBlank() } } ?: ""
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
