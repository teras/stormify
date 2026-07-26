// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.stormify.annproc

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import java.io.File

private const val DB_TABLE = "onl.ycode.stormify.DbTable"
private const val ENTITY = "javax.persistence.Entity"
private const val DB_FIELD = "onl.ycode.stormify.DbField"
private const val JPA_ID = "javax.persistence.Id"
private const val JPA_COLUMN = "javax.persistence.Column"
private const val JPA_JOIN_COLUMN = "javax.persistence.JoinColumn"

class KotlinTableProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor = KotlinTableProcessor(environment)
}

/**
 * Stormify annotation processor.
 *
 * Public mode (used by the `onl.ycode.stormify` Gradle plugin): writes
 * one JSON metadata file per discovered entity into the directory pointed
 * at by the KSP option `stormify.metaOutputDir`. The plugin runs a
 * follow-up task that reads the JSONs and emits the actual Kotlin sources.
 * Plain Java I/O is used (not KSP's `codeGenerator`) so the JSON dir is
 * outside KSP's `outputBaseDir` and survives KSP's unconditional output-dir
 * cleanup on every run.
 *
 * Internal-only direct mode (used by the stormify library's own test
 * suite): if `stormify.metaOutputDir` is absent, the processor falls back
 * to emitting `GeneratedEntities.kt` and `Paths.kt` directly via KSP's
 * code generator. This path is NOT for end-user consumption — public
 * users MUST apply the Stormify Gradle plugin.
 */
class KotlinTableProcessor(private val env: SymbolProcessorEnvironment) : SymbolProcessor {

    private val metaOutputDir: String? = env.options["stormify.metaOutputDir"]?.takeIf { it.isNotBlank() }

    private val generatedPackage: String =
        env.options["stormify.generatedPackage"] ?: "onl.ycode.stormify.generated"
    private val registrarClass: String =
        env.options["stormify.registrarClass"] ?: "GeneratedEntities"
    private val pathsClass: String =
        env.options["stormify.pathsClass"] ?: "Tables"
    private val jvmTarget: Boolean =
        env.platforms.any { it is JvmPlatformInfo }
    private val jvmFieldPrefix: String = if (jvmTarget) "@JvmField " else ""

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val explicit = resolver.getSymbolsWithAnnotation(DB_TABLE).filterIsInstance<KSClassDeclaration>().toSet() +
                resolver.getSymbolsWithAnnotation(ENTITY).filterIsInstance<KSClassDeclaration>().toSet()
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
        if (entities.isEmpty()) return emptyList()

        if (metaOutputDir != null) {
            writeMetadata(entities)
        } else {
            writeDirectKotlin(entities)
        }
        return emptyList()
    }

    private fun writeDirectKotlin(entities: Collection<KSClassDeclaration>) {
        val enumTypes = mutableSetOf<String>()
        val entityProps = entities.associateWith { EntityProperty.find(it) }
        entityProps.values.forEach { props ->
            props.filter { it.isEnum }.forEach { enumTypes.add(it.type) }
        }

        // Generated declarations are named after simple names (UserRef, Tables.User_,
        // enum imports) and all land in one file — same-named classes in different
        // packages would produce cryptic redeclaration / conflicting-import errors.
        // Fail fast with an actionable message.
        // NOTE: the same check exists in the Gradle plugin (StormifyGenerateSources).
        val bySimpleName = mutableMapOf<String, MutableSet<String>>()
        entities.forEach {
            bySimpleName.getOrPut(it.simpleName.asString()) { mutableSetOf() } +=
                (it.qualifiedName?.asString() ?: it.simpleName.asString())
        }
        enumTypes.forEach { bySimpleName.getOrPut(it.substringAfterLast('.')) { mutableSetOf() } += it }
        val clashes = bySimpleName.filterValues { it.size > 1 }
        if (clashes.isNotEmpty()) {
            env.logger.error(
                "Stormify: generated code is named after simple class names, but " +
                        clashes.entries.joinToString("; ") { (name, qns) -> "'$name' (${qns.joinToString(", ")})" } +
                        " share the same simple name. Rename one of them to avoid the collision."
            )
            return
        }

        env.codeGenerator.createNewFile(Dependencies(false), generatedPackage, registrarClass).bufferedWriter().use { w ->
            w.write("package $generatedPackage\n\n")
            w.write("import onl.ycode.stormify.DbValue\n")
            w.write("import onl.ycode.stormify.EntityMeta\n")
            w.write("import onl.ycode.stormify.EntityRegistrar\n")
            w.write("import onl.ycode.stormify.EnumRegistry\n")
            w.write("import onl.ycode.stormify.PropertyMeta\n")
            w.write("import onl.ycode.stormify.Stormify\n")
            w.write("import onl.ycode.stormify.TypeUtils.castTo\n\n")
            entities.forEach { w.write("import ${it.qualifiedName?.asString()}\n") }
            enumTypes.forEach { w.write("import $it\n") }

            w.write("\nobject $registrarClass : EntityRegistrar {\n")
            w.write("    override fun register() {\n")

            enumTypes.forEach { enumFqn ->
                val simpleName = enumFqn.substringAfterLast('.')
                w.write("        EnumRegistry.register(\n")
                w.write("            $simpleName::class,\n")
                w.write("            $simpleName.entries.toTypedArray(),\n")
                w.write("            { v -> if (v is DbValue) v.dbValue else (v as Enum<*>).ordinal }\n")
                w.write("        )\n\n")
            }

            entities.forEach { entity ->
                val className = entity.simpleName.asString()
                val tableName = EntityProperty.findTableName(entity)
                val props = entityProps[entity]!!
                val typeParams = entity.typeParameters.size
                writeEntityMetaDirect(w, className, tableName, props, typeParams)
            }

            w.write("    }\n")
            w.write("}\n")
        }

        env.codeGenerator.createNewFile(Dependencies(true), generatedPackage, pathsClass).bufferedWriter().use { w ->
            val entityQNames = entities.mapNotNull { it.qualifiedName?.asString() }.toSet()
            w.write("@file:Suppress(\"unused\")\n")
            w.write("package $generatedPackage\n\n")
            if (jvmTarget) {
                w.write("import kotlin.jvm.JvmField\n")
                w.write("import kotlin.jvm.JvmName\n")
            }
            w.write("import onl.ycode.stormify.biglist.ReferencePath\n")
            w.write("import onl.ycode.stormify.biglist.ScalarPath\n\n")
            for (entity in entities) {
                val className = entity.simpleName.asString()
                val props = entityProps[entity] ?: continue
                writeRefClassDirect(w, className, props, entityQNames)
            }
            w.write("object $pathsClass {\n")
            for (entity in entities) {
                val className = entity.simpleName.asString()
                w.write("    ${jvmFieldPrefix}val ${className}_ = ${className}Ref(\"\")\n")
            }
            w.write("}\n")
        }
    }

    private fun writeRefClassDirect(
        w: java.io.Writer,
        className: String,
        props: Collection<EntityProperty>,
        entityQNames: Set<String>
    ) {
        w.write("class ${className}Ref(path: String) : ReferencePath(path) {\n")
        for (prop in props) {
            if (prop.isReference) {
                val refTypeName = prop.type
                val shortName = refTypeName.substringAfterLast(".")
                val isKnownEntity = entityQNames.any { it.endsWith(".$shortName") || it == refTypeName }
                if (!isKnownEntity) continue
                if (jvmTarget) w.write("    @get:JvmName(\"${prop.name}\")\n")
                w.write("    val ${prop.name} get() = ${shortName}Ref(\"\${toString()}${prop.name}.\")\n")
            } else {
                w.write("    ${jvmFieldPrefix}val ${prop.name} = ScalarPath(\"\${toString()}${prop.name}\")\n")
            }
        }
        w.write("}\n\n")
    }

    private fun writeEntityMetaDirect(
        w: java.io.Writer,
        className: String,
        tableName: String,
        properties: Collection<EntityProperty>,
        typeParamCount: Int
    ) {
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
            val fullType = prop.fullType
            val notNullSuffix = if (!prop.nullable)
                " ?: throw IllegalArgumentException(\"${prop.name} cannot be null in $className\")"
            else ""
            w.write("                    @Suppress(\"UNCHECKED_CAST\") { e, v, s -> e.${prop.name} = (castTo(${prop.type}::class, v, s) as? $fullType)$notNullSuffix },\n")
            w.write("                    ${if (prop.dbname != prop.name) "\"${prop.dbname}\"" else "null"},\n")
            w.write("                    ${prop.primary},\n")
            w.write("                    ${if (prop.sequence.isNotBlank()) "\"${prop.sequence}\"" else "null"},\n")
            w.write("                    ${prop.autoIncrement}, ${prop.insertable}, ${prop.updatable}, false, ${prop.isEnum}, ${prop.enumAsString}\n")
            w.write("                )$comma\n")
        }

        w.write("            ),\n")
        w.write("            ${if (tableName.isNotBlank()) "\"$tableName\"" else "null"}\n")
        w.write("        ))\n\n")
    }

    private fun writeMetadata(entities: Collection<KSClassDeclaration>) {
        val outDir = File(metaOutputDir!!).apply { mkdirs() }

        val expectedFiles = mutableSetOf<String>()
        entities.forEach { entity ->
            val qn = entity.qualifiedName?.asString() ?: return@forEach
            val fileName = "$qn.json"
            expectedFiles += fileName
            val target = File(outDir, fileName)
            val json = renderEntityJson(entity)
            // Skip rewriting identical content so Gradle's input fingerprint
            // stays stable and downstream tasks don't re-run unnecessarily.
            if (!target.exists() || target.readText() != json) {
                target.writeText(json)
            }
        }

        outDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.filter { it.name !in expectedFiles }
            ?.forEach { it.delete() }
    }

    private fun renderEntityJson(entity: KSClassDeclaration): String {
        val qn = entity.qualifiedName!!.asString()
        val simpleName = entity.simpleName.asString()
        val tableName = EntityProperty.findTableName(entity)
        val typeParameterCount = entity.typeParameters.size
        val sourceSet = entity.containingFile?.let { sourceSetFromPath(it.filePath) } ?: "commonMain"
        val sourceFile = entity.containingFile?.filePath ?: ""
        val props = EntityProperty.find(entity)
        val enumTypes = props.filter { it.isEnum }.map { it.type }.distinct()

        return JsonWriter().obj {
            str("qualifiedName", qn)
            str("simpleName", simpleName)
            str("tableName", tableName)
            int("typeParameterCount", typeParameterCount)
            str("sourceSet", sourceSet)
            str("sourceFile", sourceFile)
            objList("properties", props.map { p ->
                { o: JsonObject ->
                    o.str("name", p.name)
                    o.str("dbName", p.dbname)
                    o.str("type", p.type)
                    o.str("fullType", p.fullType)
                    o.bool("nullable", p.nullable)
                    o.bool("primary", p.primary)
                    o.str("sequence", p.sequence)
                    o.bool("autoIncrement", p.autoIncrement)
                    o.bool("insertable", p.insertable)
                    o.bool("updatable", p.updatable)
                    o.bool("isReference", p.isReference)
                    o.bool("isEnum", p.isEnum)
                    o.bool("enumAsString", p.enumAsString)
                }
            })
            strList("enumTypes", enumTypes)
        }
    }

    /**
     * Best-effort source-set extraction from a Kotlin source file path.
     * Looks for the conventional `src/<sourceSet>/kotlin/...` segment and
     * returns the source set name, e.g. `commonMain`, `jvmMain`,
     * `linuxX64Main`, or `main` for plain JVM/Android. Falls back to
     * `commonMain` if the path does not match the convention.
     */
    private fun sourceSetFromPath(path: String): String {
        val parts = path.replace('\\', '/').split('/')
        val srcIdx = parts.indexOf("src")
        if (srcIdx >= 0 && srcIdx + 1 < parts.size) {
            return parts[srcIdx + 1]
        }
        return "commonMain"
    }
}
