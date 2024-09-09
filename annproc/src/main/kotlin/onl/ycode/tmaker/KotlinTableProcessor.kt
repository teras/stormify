// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.tmaker

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import java.io.File
import java.io.OutputStream
import java.net.URI
import java.net.URL
import java.net.URLClassLoader

private const val AUTO_TABLE = "onl.ycode.stormify.AutoTable"
private const val DB_TABLE = "onl.ycode.stormify.DbTable"
private const val ENTITY = "javax.persistence.Entity"

class KotlinTableProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor = KotlinTableProcessor(environment)
}

class KotlinTableProcessor(private val env: SymbolProcessorEnvironment) : SymbolProcessor {

    @OptIn(KspExperimental::class)
    override fun process(resolver: Resolver): List<KSAnnotated> {
        parseDbTableAnnotations(resolver)
        parseAutoTableAnnotations(resolver)
        return emptyList()
    }

    private fun parseDbTableAnnotations(resolver: Resolver) {
        val symbols = resolver.getSymbolsWithAnnotation(DB_TABLE).filterIsInstance<KSClassDeclaration>().toSet() +
                resolver.getSymbolsWithAnnotation(ENTITY).filterIsInstance<KSClassDeclaration>().toSet()
        if (symbols.isEmpty()) return

        val dec = env.codeGenerator.createNewFile(Dependencies(false), "db.stormify", "Registrare")
        dec.bufferedWriter().use { w ->
            w.write("package db.stormify\n\n")
            w.write("object Registrar {\n")

            val depClassLoader = URLClassLoader((env.options["stormify.class.path"] ?: "").split(File.pathSeparator).map { URL(
                "file://$it"
            ) }.toTypedArray())
            depClassLoader.getResources("hello").toList().forEach {
                w.write("    // ${it.toURI()}\n")
            }

            symbols.forEach {
                w.write("    // ${it.qualifiedName?.asString()}\n")
            }
            w.write("}\n")
            w.flush()
            w.close()
        }
    }

    @OptIn(KspExperimental::class)
    private fun parseAutoTableAnnotations(resolver: Resolver) {
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

        val allPackages = resolver.getAllFiles().map { it.packageName.asString() }.toSet()
        val collectedClasses = mutableListOf<KSClassDeclaration>()
        allPackages.forEach { packageName ->
            val declarations = resolver.getDeclarationsFromPackage(packageName)
            declarations.forEach { decl ->
                if (decl is KSClassDeclaration && isSubclassOf(decl, AUTO_TABLE)) {
                    collectedClasses += decl
                }
            }
        }
        val properties = collectedClasses.map {
            val name = "${it.packageName.asString()}.${it.simpleName.asString()}"
            val props = it.getAllProperties().map { it.simpleName.asString() }.toList()
            name to props
        }.toMap()

        writeToSharedLocation(properties, reqPackage, reqClass, fileOut)
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

