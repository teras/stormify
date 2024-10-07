package onl.ycode.stormify.annproc

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration

private const val DB_TABLE = "onl.ycode.stormify.DbTable"
private const val ENTITY = "javax.persistence.Entity"
private const val TABLE = "javax.persistence.Table"

private const val DB_FIELD = "onl.ycode.stormify.DbField"
private const val ID = "javax.persistence.Id"
private const val TRANSIENT = "javax.persistence.Transient"
private const val COLUMN = "javax.persistence.Column"
private const val JOIN_COLUMN = "javax.persistence.JoinColumn"
private const val SEQUENCE = "javax.persistence.SequenceGenerator"

class EntityProperty(declaration: KSPropertyDeclaration) {
    val name = declaration.simpleName.getShortName()
    val type = declaration.type.resolve().declaration.qualifiedName?.asString().cname
    val nullable = declaration.type.resolve().isMarkedNullable
    val dbname: String
    val sequence: String
    val updatable: Boolean
    val insertable: Boolean
    val primary: Boolean

    init {
        var _dbname = ""
        var _sequence = ""
        var _updt = true
        var _insertable = true
        var _primary = false
        declaration.annotations.forEach { ann ->
            when (ann.annotationType.resolve().declaration.qualifiedName?.asString()) {
                DB_FIELD -> {
                    _dbname = ann.arguments.first { it.name?.asString() == "name" }.value.toString()
                    _primary = ann.arguments.firstOrNull { it.name?.asString() == "primaryKey" }?.value?.toString()
                        ?.toBoolean()
                        ?: false
                    _sequence =
                        ann.arguments.firstOrNull { it.name?.asString() == "primarySequence" }?.value?.toString() ?: ""
                    _updt =
                        ann.arguments.firstOrNull { it.name?.asString() == "updatable" }?.value?.toString()?.toBoolean()
                            ?: true
                    _insertable = ann.arguments.firstOrNull { it.name?.asString() == "creatable" }?.value?.toString()
                        ?.toBoolean() ?: true
                }

                COLUMN, JOIN_COLUMN -> {
                    _dbname = ann.arguments.first { it.name?.asString() == "name" }.value.toString()
                    _updt =
                        ann.arguments.firstOrNull { it.name?.asString() == "updatable" }?.value?.toString()?.toBoolean()
                            ?: true
                    _insertable = ann.arguments.firstOrNull { it.name?.asString() == "insertable" }?.value?.toString()
                        ?.toBoolean() ?: true
                }

                SEQUENCE -> _sequence = ann.arguments.first { it.name?.asString() == "name" }.value.toString()
                ID -> _primary = true
            }
        }
        dbname = _dbname.ifEmpty { name }
        sequence = _sequence
        updatable = _updt
        insertable = _insertable
        primary = _primary
    }

    companion object {
        fun find(entity: KSClassDeclaration): Collection<EntityProperty> {
            return entity.getAllProperties().mapNotNull {
                if (it.annotations.any { ann -> ann.annotationType.resolve().declaration.qualifiedName?.asString() == TRANSIENT })
                    return@mapNotNull null
                EntityProperty(it)
            }.toList()
        }

        fun findTableName(declaration: KSClassDeclaration): String {
            var name = ""
            declaration.annotations.forEach { ann ->
                when (ann.annotationType.resolve().declaration.qualifiedName?.asString()) {
                    DB_TABLE, ENTITY, TABLE ->
                        name = ann.arguments.first { it.name?.asString() == "name" }.value.toString()
                }
            }
            return name
        }
    }

    private val String?.cname get() = if (this?.startsWith("kotlin.") == true) this.substring(7) else this

}