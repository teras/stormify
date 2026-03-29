// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.stormify.annproc

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import java.io.Writer

private const val DB_TABLE = "onl.ycode.stormify.DbTable"
private const val ENTITY = "javax.persistence.Entity"

class KotlinTableProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor = KotlinTableProcessor(environment)
}

class KotlinTableProcessor(private val env: SymbolProcessorEnvironment) : SymbolProcessor {

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val entities = resolver.getSymbolsWithAnnotation(DB_TABLE).filterIsInstance<KSClassDeclaration>().toSet() +
                resolver.getSymbolsWithAnnotation(ENTITY).filterIsInstance<KSClassDeclaration>().toSet()
        if (entities.isNotEmpty())
            generateRegistrar(entities)
        return emptyList()
    }

    private fun generateRegistrar(entities: Collection<KSClassDeclaration>) {
        env.codeGenerator.createNewFile(Dependencies(false), "db.stormify", "Registrar").bufferedWriter().use { w ->
            w.write("package db.stormify\n\n")
            w.write("import kotlinx.atomicfu.atomic\n")
            w.write("import onl.ycode.stormify.EntityMeta\n")
            w.write("import onl.ycode.stormify.EntityRegistrar\n")
            w.write("import onl.ycode.stormify.PropertyMeta\n")
            w.write("import onl.ycode.stormify.Stormify\n")
            w.write("import onl.ycode.stormify.TypeUtils.castTo\n\n")
            entities.forEach { w.write("import ${it.qualifiedName?.asString()}\n") }

            w.write("\nobject GeneratedEntities : EntityRegistrar {\n")
            w.write("    private val initialized = atomic(false)\n\n")
            w.write("    override fun register() {\n")
            w.write("        if (!initialized.compareAndSet(false, true)) return\n\n")

            entities.forEach { entity ->
                val className = entity.simpleName.asString()
                val tableName = EntityProperty.findTableName(entity)
                val props = EntityProperty.find(entity)
                writeEntityMeta(w, className, tableName, props)
            }

            w.write("    }\n")
            w.write("}\n")
        }
    }

    private fun writeEntityMeta(
        w: Writer,
        className: String,
        tableName: String,
        properties: Collection<EntityProperty>
    ) {
        w.write("        EntityMeta.register(EntityMeta(\n")
        w.write("            ${className}::class,\n")
        w.write("            { ${className}() },\n")
        w.write("            listOf(\n")

        properties.forEachIndexed { i, prop ->
            val comma = if (i < properties.size - 1) "," else ""
            w.write("                PropertyMeta(\n")
            w.write("                    \"${prop.name}\", ${prop.type}::class, ${prop.isReference},\n")
            w.write("                    { it.${prop.name} },\n")
            w.write("                    { e, v, s -> e.${prop.name} = castTo(${prop.type}::class, v, s)")
            if (!prop.nullable) w.write(" ?: throw IllegalArgumentException(\"${prop.name} cannot be null in $className\")")
            w.write(" },\n")
            w.write("                    ${if (prop.dbname != prop.name) "\"${prop.dbname}\"" else "null"},\n")
            w.write("                    ${prop.primary},\n")
            w.write("                    ${if (prop.sequence.isNotBlank()) "\"${prop.sequence}\"" else "null"},\n")
            w.write("                    ${prop.insertable}, ${prop.updatable}, false\n")
            w.write("                )$comma\n")
        }

        w.write("            ),\n")
        w.write("            ${if (tableName.isNotBlank()) "\"$tableName\"" else "null"}\n")
        w.write("        ))\n\n")
    }
}
