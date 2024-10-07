// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.stormify.annproc

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import java.io.BufferedWriter
import java.io.OutputStream
import java.io.Writer

private const val AUTO_TABLE = "onl.ycode.stormify.AutoTable"
private const val DB_TABLE = "onl.ycode.stormify.DbTable"
private const val ENTITY = "javax.persistence.Entity"

class KotlinTableProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor = KotlinTableProcessor(environment)
}

class KotlinTableProcessor(private val env: SymbolProcessorEnvironment) : SymbolProcessor {

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val entities = resolver.getSymbolsWithAnnotation(DB_TABLE).filterIsInstance<KSClassDeclaration>().toSet() +
                resolver.getSymbolsWithAnnotation(ENTITY).filterIsInstance<KSClassDeclaration>().toSet()
        if (entities.isNotEmpty()) {
            parseDbTableAnnotations(entities)
            parseAutoTableAnnotations(entities)
        }
        return emptyList()
    }

    private fun parseDbTableAnnotations(entities: Collection<KSClassDeclaration>) {
        env.codeGenerator.createNewFile(Dependencies(false), "db.stormify", "Registrar").bufferedWriter().use { w ->
            startWriting(w, entities)
            entities.forEach {
                writeEntity(
                    w,
                    it.simpleName.asString(),
                    it.simpleName.asString().uppercase(),
                    it.getAllProperties().map { EntityProperty(it) }
                )
            }
            finalizeWriting(w)
        }
    }

    private fun startWriting(w: Writer, entities: Collection<KSClassDeclaration>) {
        w.write(
            """
package db.stormify

import onl.ycode.stormify.TableInfo
import onl.ycode.stormify.TableInfo.Companion.register
import onl.ycode.stormify.TypeUtils.castTo
import onl.ycode.stormify.TypeUtils.err
"""
        )
        entities.forEach { w.write("import ${it.qualifiedName?.asString()}\n") }
        w.write(
            """
fun registerAll() {
"""
        )
    }

    private fun finalizeWriting(w: Writer) {
        w.write("}\n")
        w.flush()
        w.close()
    }

    private fun writeEntity(
        w: BufferedWriter,
        className: String,
        dbName: String,
        properties: Sequence<EntityProperty>
    ) {
        w.write(
            """
    register(
         TableInfo(
            $className::class,
            "$dbName",
            { $className() },
            { entity, name, value, stormify ->
                when (name.lowercase()) {
            """
        )
        properties.forEach {
            w.write(
                """
                    "${it.name.lowercase()}" -> {
                        entity.${it.name} = castTo(${it.type}::class, value, stormify)${if (it.nullable) "" else " ?: err(name, \"$className\")"}
                        true
                    }
                """
            )
        }
        w.write("\n                    else -> false\n                }\n            },\n")
        w.write(
            """
            listOf("id"),
            listOf("ID"),
            listOf(Int::class),
            listOf(""),
            { listOf() },
            listOf(
"""
        )
        w.write(
            properties.joinToString(",\n") {
                "                \"${it.name}\""
            }
        )
        w.write(
            """
            ),
            listOf(
"""
        )
        w.write(
            properties.joinToString(",\n") {
                "                \"${it.name.uppercase()}\""
            }
        )
        w.write(
            """
            ),
            listOf(
"""
        )
        w.write(
            properties.joinToString(",\n") {
                "                ${it.type}::class"
            }
        )
        w.write(
            """
            ),
            {listOf(
"""
        )
        w.write(
            properties.joinToString(",\n") {
                "                it.${it.name}"
            }
        )
        w.write(
            """)},
            "",
            "",
            "",
            "",
        )
    )
   """
        )

    }


    @OptIn(KspExperimental::class)
    private fun parseAutoTableAnnotations(entities: Collection<KSClassDeclaration>) {
        val className = env.options.getOrDefault("stormify.meta.class", "tables.T")
        val dot = className.lastIndexOf('.')
        require(dot != -1) { "Invalid class name: $className" }
        val reqPackage = className.substring(0, dot)
        val reqClass = className.substring(dot + 1)

        val fileOut = try {
            env.codeGenerator.createNewFile(Dependencies(false), reqPackage, reqClass)
        } catch (e: Exception) {
            return
        }

//        val allPackages = resolver.getAllFiles().map { it.packageName.asString() }.toSet()
//        val collectedClasses = mutableListOf<KSClassDeclaration>()
//        allPackages.forEach { packageName ->
//            val declarations = resolver.getDeclarationsFromPackage(packageName)
//            declarations.forEach { decl ->
//                if (decl is KSClassDeclaration && isSubclassOf(decl, AUTO_TABLE)) {
//                    collectedClasses += decl
//                }
//            }
//        }
//        val properties = collectedClasses.map {
//            val name = "${it.packageName.asString()}.${it.simpleName.asString()}"
//            val props = it.getAllProperties().map { it.simpleName.asString() }.toList()
//            name to props
//        }.toMap()
//
//        writeToSharedLocation(properties, reqPackage, reqClass, fileOut)
        fileOut.close()
    }

    private fun isSubclassOf(classDeclaration: KSClassDeclaration, superclassName: String): Boolean {
        // Check all the super types of this class
        return classDeclaration.superTypes.any {
            val resolvedType = it.resolve()
            resolvedType.declaration.qualifiedName?.asString() == superclassName
        }
    }

    private fun writeToSharedLocation(
        methods: Map<String, Collection<String>>,
        reqPackage: String,
        reqClass: String,
        fileOut: OutputStream
    ) {
        if (methods.isEmpty()) return
        fileOut.bufferedWriter().use { writer ->
            writer.appendLine("package $reqPackage")
            writer.appendLine()
            writer.appendLine("object $reqClass {")
            var firstEntry = true
            for ((key, value) in methods) {
                if (firstEntry) firstEntry = false
                else writer.appendLine()
                writer.appendLine("    object " + key.substring(key.lastIndexOf('.') + 1) + " {")
                writer.appendLine("        // val __classname = \"$key\"")
                for (property in value)
                    writer.appendLine("        val $property = \"$property\"")
                writer.appendLine("    }")
            }
            writer.appendLine("}")
        }
    }
}

