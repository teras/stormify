package onl.ycode.stormify.annproc

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

private const val AUTO_TABLE = "onl.ycode.stormify.AutoTable"

private val KOTLIN_BUILTINS = mapOf(
    "kotlin.Int" to "Int", "kotlin.Long" to "Long", "kotlin.Short" to "Short",
    "kotlin.Byte" to "Byte", "kotlin.Float" to "Float", "kotlin.Double" to "Double",
    "kotlin.Boolean" to "Boolean", "kotlin.Char" to "Char", "kotlin.String" to "String",
    "kotlin.ByteArray" to "ByteArray", "kotlin.CharArray" to "CharArray",
)

class EntityProperty(declaration: KSPropertyDeclaration) {
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
    /**
     * True for properties that are `List<X>` where `X` is a `@DbTable`-annotated class,
     * or for properties backed by a delegate returning a collection (e.g. `lazyDetails`).
     * Such properties are not persisted as columns on the owning entity — they are resolved
     * lazily from a separate table via stormify's details/collection helpers.
     */
    val skip: Boolean

    init {
        val resolved = declaration.type.resolve()
        nullable = resolved.isMarkedNullable
        type = rawTypeName(resolved)
        fullType = fullTypeName(resolved)

        var _dbname = ""
        var _sequence = ""
        var _updt = true
        var _insertable = true
        var _primary = false
        var _autoIncrement = false
        declaration.annotations.forEach { ann ->
            when (ann.annotationType.resolve().declaration.qualifiedName?.asString()) {
                DB_FIELD -> {
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
            }
        }
        dbname = _dbname.ifEmpty { name }
        sequence = _sequence
        updatable = _updt
        insertable = _insertable
        primary = _primary
        autoIncrement = _autoIncrement

        val typeDecl = resolved.declaration
        isReference = typeDecl is KSClassDeclaration && (
                typeDecl.annotations.any { ann ->
                    ann.annotationType.resolve().declaration.qualifiedName?.asString() in setOf(DB_TABLE, ENTITY)
                } || typeDecl.superTypes.any { sup ->
                    sup.resolve().declaration.qualifiedName?.asString() == AUTO_TABLE
                })

        // Delegated collection properties (e.g. `var children by lazyDetails<AutoChildEntity>()`)
        // are not real columns — skip them entirely from the generated EntityMeta.
        val baseQn = typeDecl.qualifiedName?.asString()
        val isList = baseQn == "kotlin.collections.List" || baseQn == "kotlin.collections.MutableList"
        val elementIsEntity = if (isList) {
            val elemDecl = resolved.arguments.firstOrNull()?.type?.resolve()?.declaration
            elemDecl is KSClassDeclaration && (
                    elemDecl.annotations.any { ann ->
                        ann.annotationType.resolve().declaration.qualifiedName?.asString() in setOf(DB_TABLE, ENTITY)
                    } || elemDecl.superTypes.any { sup ->
                        sup.resolve().declaration.qualifiedName?.asString() == AUTO_TABLE
                    })
        } else false
        skip = isList && elementIsEntity
    }

    companion object {
        fun find(entity: KSClassDeclaration): Collection<EntityProperty> {
            return entity.getAllProperties().mapNotNull {
                if (it.annotations.any { ann -> ann.annotationType.resolve().declaration.qualifiedName?.asString() == TRANSIENT })
                    return@mapNotNull null
                EntityProperty(it).takeUnless { p -> p.skip }
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
