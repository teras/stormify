// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.stormify.annproc

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import java.io.Writer

private const val DB_TABLE = "onl.ycode.stormify.DbTable"
private const val ENTITY = "javax.persistence.Entity"
private const val DB_FIELD = "onl.ycode.stormify.DbField"
private const val JPA_ID = "javax.persistence.Id"
private const val JPA_COLUMN = "javax.persistence.Column"
private const val JPA_JOIN_COLUMN = "javax.persistence.JoinColumn"

class KotlinTableProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor = KotlinTableProcessor(environment)
}

class KotlinTableProcessor(private val env: SymbolProcessorEnvironment) : SymbolProcessor {

    override fun process(resolver: Resolver): List<KSAnnotated> {
        // Classes explicitly marked as entities.
        val explicit = resolver.getSymbolsWithAnnotation(DB_TABLE).filterIsInstance<KSClassDeclaration>().toSet() +
                resolver.getSymbolsWithAnnotation(ENTITY).filterIsInstance<KSClassDeclaration>().toSet()

        // Classes that have at least one property carrying an entity-relevant annotation
        // (@DbField / @Id / @Column / @JoinColumn) are also considered entities, so tests that
        // rely on auto-derived table names still work on native targets without reflection.
        val implicit = sequenceOf(DB_FIELD, JPA_ID, JPA_COLUMN, JPA_JOIN_COLUMN)
            .flatMap { resolver.getSymbolsWithAnnotation(it) }
            .mapNotNull { sym ->
                when (sym) {
                    is KSPropertyDeclaration -> sym.parentDeclaration as? KSClassDeclaration
                    is KSClassDeclaration -> sym
                    else -> null
                }
            }
            .toSet()

        val entities = (explicit + implicit).filter { it.classKind.name == "CLASS" }.toSet()
        if (entities.isNotEmpty()) {
            generateRegistrar(entities)
            generatePaths(entities)
        }
        return emptyList()
    }

    private fun generateRegistrar(entities: Collection<KSClassDeclaration>) {
        // Collect all enum types used in entity properties
        val enumTypes = mutableSetOf<String>()
        val entityProps = entities.associateWith { EntityProperty.find(it) }
        entityProps.values.forEach { props ->
            props.filter { it.isEnum }.forEach { enumTypes.add(it.type) }
        }

        env.codeGenerator.createNewFile(Dependencies(false), "db.stormify", "Registrar").bufferedWriter().use { w ->
            w.write("package db.stormify\n\n")
            w.write("import kotlinx.atomicfu.atomic\n")
            w.write("import onl.ycode.stormify.DbValue\n")
            w.write("import onl.ycode.stormify.EntityMeta\n")
            w.write("import onl.ycode.stormify.EntityRegistrar\n")
            w.write("import onl.ycode.stormify.EnumRegistry\n")
            w.write("import onl.ycode.stormify.PropertyMeta\n")
            w.write("import onl.ycode.stormify.Stormify\n")
            w.write("import onl.ycode.stormify.TypeUtils.castTo\n\n")
            entities.forEach { w.write("import ${it.qualifiedName?.asString()}\n") }
            enumTypes.forEach { w.write("import $it\n") }

            w.write("\nobject GeneratedEntities : EntityRegistrar {\n")
            w.write("    private val initialized = atomic(false)\n\n")
            w.write("    override fun register() {\n")
            w.write("        if (!initialized.compareAndSet(false, true)) return\n\n")

            // Register enum types
            enumTypes.forEach { enumFqn ->
                val simpleName = enumFqn.substringAfterLast('.')
                w.write("        EnumRegistry.register(\n")
                w.write("            $simpleName::class,\n")
                w.write("            { v -> $simpleName.entries.let { e -> if (e.firstOrNull() is DbValue) e.firstOrNull { (it as DbValue).dbValue == v } else e.getOrNull(v) } },\n")
                w.write("            { v -> if (v is DbValue) v.dbValue else (v as Enum<*>).ordinal },\n")
                w.write("            { n -> $simpleName.entries.firstOrNull { it.name.equals(n, ignoreCase = true) } }\n")
                w.write("        )\n\n")
            }

            entities.forEach { entity ->
                val className = entity.simpleName.asString()
                val tableName = EntityProperty.findTableName(entity)
                val props = entityProps[entity]!!
                val typeParams = entity.typeParameters.size
                writeEntityMeta(w, className, tableName, props, typeParams)
            }

            w.write("    }\n")
            w.write("}\n")
        }
    }

    private fun generatePaths(entities: Collection<KSClassDeclaration>) {
        val entityQNames = entities.mapNotNull { it.qualifiedName?.asString() }.toSet()
        val entityProps = entities.associate { it.simpleName.asString() to EntityProperty.find(it) }

        env.codeGenerator.createNewFile(Dependencies(true), "db.stormify", "Paths").bufferedWriter().use { w ->
            w.write("@file:Suppress(\"unused\")\n")
            w.write("package db.stormify\n\n")
            w.write("import onl.ycode.stormify.biglist.ScalarPath\n\n")

            // Generate a Ref class per entity
            for (entity in entities) {
                val className = entity.simpleName.asString()
                val props = entityProps[className] ?: continue
                writeRefClass(w, className, props, entityQNames)
            }

            // Root objects inside Paths
            w.write("object Paths {\n")
            for (entity in entities) {
                val className = entity.simpleName.asString()
                w.write("    @JvmField val ${className}_ = ${className}Ref(\"\")\n")
            }
            w.write("}\n")
        }
    }

    private fun writeRefClass(
        w: Writer,
        className: String,
        props: Collection<EntityProperty>,
        entityQNames: Set<String>
    ) {
        w.write("class ${className}Ref(private val p: String) {\n")

        for (prop in props) {
            if (prop.isReference) {
                val refTypeName = prop.type
                val shortName = refTypeName.substringAfterLast(".")
                val isKnownEntity = entityQNames.any { it.endsWith(".$shortName") || it == refTypeName }
                if (!isKnownEntity) continue

                w.write("    @get:JvmName(\"${prop.name}\")\n")
                w.write("    val ${prop.name} get() = ${shortName}Ref(\"\${p}${prop.name}.\")\n")
            } else {
                w.write("    @JvmField val ${prop.name} = ScalarPath(\"\${p}${prop.name}\")\n")
            }
        }

        w.write("}\n\n")
    }

    private fun writeEntityMeta(
        w: Writer,
        className: String,
        tableName: String,
        properties: Collection<EntityProperty>,
        typeParamCount: Int
    ) {
        // For generic classes we fix the type arguments to `Any?` so the property setters
        // can assign through without running into star-projection write restrictions.
        val typeArgs = if (typeParamCount == 0) "" else
            "<" + List(typeParamCount) { "kotlin.Any?" }.joinToString(", ") + ">"
        val fullClassName = "$className$typeArgs"
        val kclassExpr = if (typeArgs.isEmpty())
            "${className}::class"
        else
            "@Suppress(\"UNCHECKED_CAST\") (${className}::class as kotlin.reflect.KClass<$fullClassName>)"

        w.write("        EntityMeta.register(EntityMeta<$fullClassName>(\n")
        w.write("            $kclassExpr,\n")
        w.write("            { $fullClassName() },\n")
        w.write("            listOf(\n")

        properties.forEachIndexed { i, prop ->
            val comma = if (i < properties.size - 1) "," else ""
            w.write("                PropertyMeta(\n")
            w.write("                    \"${prop.name}\", ${prop.type}::class, ${prop.isReference},\n")
            w.write("                    { it.${prop.name} },\n")
            // Setter uses an unchecked cast to the full property type so that generic
            // types (e.g. List<Foo>) and type parameters assign cleanly on strict-typing
            // targets like Kotlin/Native.
            val fullType = prop.fullType
            val notNullSuffix = if (!prop.nullable)
                " ?: throw IllegalArgumentException(\"${prop.name} cannot be null in $className\")"
            else ""
            w.write("                    @Suppress(\"UNCHECKED_CAST\") { e, v, s -> e.${prop.name} = (castTo(${prop.type}::class, v, s) as? $fullType)$notNullSuffix },\n")
            w.write("                    ${if (prop.dbname != prop.name) "\"${prop.dbname}\"" else "null"},\n")
            w.write("                    ${prop.primary},\n")
            w.write("                    ${if (prop.sequence.isNotBlank()) "\"${prop.sequence}\"" else "null"},\n")
            // Order: isAutoIncrement, isCreatable, isUpdatable, isTransient, isEnum, enumAsString
            w.write("                    ${prop.autoIncrement}, ${prop.insertable}, ${prop.updatable}, false, ${prop.isEnum}, ${prop.enumAsString}\n")
            w.write("                )$comma\n")
        }

        w.write("            ),\n")
        w.write("            ${if (tableName.isNotBlank()) "\"$tableName\"" else "null"}\n")
        w.write("        ))\n\n")
    }
}
