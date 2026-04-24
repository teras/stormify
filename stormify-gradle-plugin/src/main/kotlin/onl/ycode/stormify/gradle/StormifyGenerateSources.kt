// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.stormify.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Emission shape for [StormifyGenerateSources].
 *
 *  - [COMMON]: `expect` declarations for a KMP `commonMain` source set.
 *  - [JVM] / [NATIVE]: `actual` declarations plus local-only extras for a
 *    KMP target source set. [JVM] additionally emits `@JvmField` /
 *    `@get:JvmName` for Java-friendly access.
 *  - [STANDALONE_JVM] / [STANDALONE_NATIVE]: plain class/object (no
 *    `expect`/`actual`) for plain `kotlin("jvm")`, Android, or single-target
 *    KMP projects. [STANDALONE_JVM] emits `@JvmField` / `@get:JvmName`.
 */
enum class TargetKind { COMMON, JVM, NATIVE, STANDALONE_JVM, STANDALONE_NATIVE }

/**
 * Reads the JSON metadata files emitted by `annproc` and writes the
 * corresponding Kotlin sources for a single source set. The emission shape
 * is selected by [targetKind] — see [TargetKind] for the available modes.
 */
@CacheableTask
abstract class StormifyGenerateSources : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val metadataDirs: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Input
    abstract val targetKind: Property<TargetKind>

    @get:Input
    abstract val sourceSetName: Property<String>

    @get:Input
    abstract val generatedPackage: Property<String>

    @get:Input
    abstract val pathsClass: Property<String>

    @get:Input
    abstract val registrarClass: Property<String>

    @get:Input
    abstract val shimVal: Property<String>

    @get:Input
    abstract val generateRegistrar: Property<Boolean>

    @TaskAction
    fun generate() {
        val outRoot = outputDir.get().asFile
        project.delete(outRoot)
        val pkg = generatedPackage.get()
        val pkgDir = File(outRoot, pkg.replace('.', '/')).apply { mkdirs() }

        val entities = collectEntities()

        when (targetKind.get()) {
            TargetKind.COMMON -> emitCommon(pkgDir, pkg, entities)
            TargetKind.JVM -> emitTarget(pkgDir, pkg, entities, jvm = true)
            TargetKind.NATIVE -> emitTarget(pkgDir, pkg, entities, jvm = false)
            TargetKind.STANDALONE_JVM -> emitStandalone(pkgDir, pkg, entities, jvm = true)
            TargetKind.STANDALONE_NATIVE -> emitStandalone(pkgDir, pkg, entities, jvm = false)
        }
    }

    private fun collectEntities(): List<EntityMeta> {
        val byQn = LinkedHashMap<String, EntityMeta>()
        metadataDirs.files.forEach { dir ->
            if (!dir.isDirectory) return@forEach
            dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.forEach { f ->
                val em = parseEntityJson(f.readText())
                if (em.qualifiedName.isNotEmpty()) byQn.putIfAbsent(em.qualifiedName, em)
            }
        }
        return byQn.values.toList()
    }

    private fun emitCommon(pkgDir: File, pkg: String, all: List<EntityMeta>) {
        val common = all.filter { it.sourceSet == "commonMain" }
        val knownQns = common.map { it.qualifiedName }.toSet()

        common.forEach { e ->
            File(pkgDir, "${e.simpleName}Ref.kt").writeText(buildString {
                append("package $pkg\n\n")
                append("import onl.ycode.stormify.biglist.ReferencePath\n")
                append("import onl.ycode.stormify.biglist.ScalarPath\n\n")
                append("expect class ${e.simpleName}Ref(path: String) : ReferencePath {\n")
                e.properties.forEach { p ->
                    if (p.isReference) {
                        val refSimple = p.type.substringAfterLast('.')
                        if (knownQns.any { it.endsWith(".$refSimple") || it == p.type }) {
                            append("    val ${p.name}: ${refSimple}Ref\n")
                        }
                    } else {
                        append("    val ${p.name}: ScalarPath\n")
                    }
                }
                append("}\n")
            })
        }

        File(pkgDir, "${pathsClass.get()}.kt").writeText(buildString {
            append("@file:Suppress(\"unused\")\n")
            append("package $pkg\n\n")
            append("expect object ${pathsClass.get()} {\n")
            common.forEach { e ->
                append("    val ${e.simpleName}_: ${e.simpleName}Ref\n")
            }
            append("}\n")
        })

        if (generateRegistrar.get()) {
            File(pkgDir, "StormifyShim.kt").writeText(buildString {
                append("package $pkg\n\n")
                append("import onl.ycode.stormify.EntityRegistrar\n\n")
                append("expect val ${shimVal.get()}: EntityRegistrar\n")
            })
        }
    }

    private fun emitTarget(pkgDir: File, pkg: String, all: List<EntityMeta>, jvm: Boolean) {
        val ssName = sourceSetName.get()
        val common = all.filter { it.sourceSet == "commonMain" }
        val local = all.filter { it.sourceSet == ssName && it.sourceSet != "commonMain" }
        emitConcrete(pkgDir, pkg, visible = common + local, actualFor = common.toSet(), isKmpTarget = true, jvm = jvm)
    }

    private fun emitStandalone(pkgDir: File, pkg: String, all: List<EntityMeta>, jvm: Boolean) =
        emitConcrete(pkgDir, pkg, visible = all, actualFor = emptySet(), isKmpTarget = false, jvm = jvm)

    /**
     * Unified emitter for concrete (non-`expect`) declarations.
     *
     * @param actualFor entities with a matching `expect class` in `commonMain`
     *   — emitted with `actual` on the class header and its members.
     * @param isKmpTarget when true, the surrounding `Tables` object and shim
     *   have a matching `expect` counterpart and must carry the `actual`
     *   modifier even if no entities are common-scoped.
     */
    private fun emitConcrete(
        pkgDir: File,
        pkg: String,
        visible: List<EntityMeta>,
        actualFor: Set<EntityMeta>,
        isKmpTarget: Boolean,
        jvm: Boolean,
    ) {
        val visibleSimpleNames = visible.map { it.simpleName }.toSet()
        val jvmField = if (jvm) "@JvmField " else ""

        visible.forEach { e ->
            val isActual = e in actualFor
            File(pkgDir, "${e.simpleName}Ref.kt").writeText(buildString {
                append("package $pkg\n\n")
                if (jvm) {
                    append("import kotlin.jvm.JvmField\n")
                    append("import kotlin.jvm.JvmName\n")
                }
                append("import onl.ycode.stormify.biglist.ReferencePath\n")
                append("import onl.ycode.stormify.biglist.ScalarPath\n\n")
                val classKw = if (isActual) "actual class" else "class"
                val ctorKw = if (isActual) " actual constructor" else ""
                append("$classKw ${e.simpleName}Ref$ctorKw(path: String) : ReferencePath(path) {\n")
                val actualKw = if (isActual) "actual " else ""
                e.properties.forEach { p ->
                    if (p.isReference) {
                        val refSimple = p.type.substringAfterLast('.')
                        if (refSimple !in visibleSimpleNames) return@forEach
                        if (jvm) append("    @get:JvmName(\"${p.name}\")\n")
                        append("    ${actualKw}val ${p.name} get() = ${refSimple}Ref(\"\${toString()}${p.name}.\")\n")
                    } else {
                        append("    $jvmField${actualKw}val ${p.name} = ScalarPath(\"\${toString()}${p.name}\")\n")
                    }
                }
                append("}\n")
            })
        }

        File(pkgDir, "${pathsClass.get()}.kt").writeText(buildString {
            append("@file:Suppress(\"unused\")\n")
            append("package $pkg\n\n")
            if (jvm) append("import kotlin.jvm.JvmField\n\n")
            val objectKw = if (isKmpTarget) "actual object" else "object"
            append("$objectKw ${pathsClass.get()} {\n")
            visible.forEach { e ->
                val actualKw = if (e in actualFor) "actual " else ""
                append("    $jvmField${actualKw}val ${e.simpleName}_ = ${e.simpleName}Ref(\"\")\n")
            }
            append("}\n")
        })

        if (!generateRegistrar.get()) return
        writeRegistrar(pkgDir, pkg, visible)

        File(pkgDir, "StormifyShim.kt").writeText(buildString {
            append("package $pkg\n\n")
            append("import onl.ycode.stormify.EntityRegistrar\n\n")
            val actualKw = if (isKmpTarget) "actual " else ""
            append("${actualKw}val ${shimVal.get()}: EntityRegistrar = ${registrarClass.get()}\n")
        })
    }

    private fun writeRegistrar(pkgDir: File, pkg: String, visible: List<EntityMeta>) {
        File(pkgDir, "${registrarClass.get()}.kt").writeText(buildString {
            append("@file:Suppress(\"unused\", \"UNCHECKED_CAST\")\n")
            append("package $pkg\n\n")
            append("import onl.ycode.stormify.DbValue\n")
            append("import onl.ycode.stormify.EntityMeta\n")
            append("import onl.ycode.stormify.EntityRegistrar\n")
            append("import onl.ycode.stormify.EnumRegistry\n")
            append("import onl.ycode.stormify.PropertyMeta\n")
            append("import onl.ycode.stormify.TypeUtils.castTo\n\n")
            visible.forEach { append("import ${it.qualifiedName}\n") }
            val enumTypes = visible.flatMap { it.enumTypes }.distinct()
            enumTypes.forEach { append("import $it\n") }
            append("\nobject ${registrarClass.get()} : EntityRegistrar {\n")
            append("    override fun register() {\n")
            enumTypes.forEach { fqn ->
                val s = fqn.substringAfterLast('.')
                append("        EnumRegistry.register(\n")
                append("            $s::class,\n")
                append("            $s.entries.toTypedArray(),\n")
                append("            { v -> if (v is DbValue) v.dbValue else (v as Enum<*>).ordinal }\n")
                append("        )\n\n")
            }
            visible.forEach { e -> emitEntityMetaRegistration(this, e) }
            append("    }\n")
            append("}\n")
        })
    }

    private fun emitEntityMetaRegistration(sb: StringBuilder, e: EntityMeta) {
        val typeArgs = if (e.typeParameterCount == 0) ""
        else "<" + List(e.typeParameterCount) { "kotlin.Any?" }.joinToString(", ") + ">"
        val fullClass = "${e.simpleName}$typeArgs"
        val kclass = if (typeArgs.isEmpty()) "${e.simpleName}::class"
        else "@Suppress(\"UNCHECKED_CAST\") (${e.simpleName}::class as kotlin.reflect.KClass<$fullClass>)"

        sb.append("        EntityMeta.register(EntityMeta<$fullClass>(\n")
        sb.append("            $kclass,\n")
        sb.append("            { $fullClass() },\n")
        sb.append("            listOf(\n")
        e.properties.forEachIndexed { i, p ->
            val comma = if (i < e.properties.size - 1) "," else ""
            sb.append("                PropertyMeta(\n")
            sb.append("                    \"${p.name}\", ${p.type}::class, ${p.isReference},\n")
            sb.append("                    { it.${p.name} },\n")
            val notNull = if (!p.nullable)
                " ?: throw IllegalArgumentException(\"${p.name} cannot be null in ${e.simpleName}\")"
            else ""
            sb.append("                    { en, v, s -> en.${p.name} = (castTo(${p.type}::class, v, s) as? ${p.fullType})$notNull },\n")
            sb.append("                    ${if (p.dbName != p.name) "\"${p.dbName}\"" else "null"},\n")
            sb.append("                    ${p.primary},\n")
            sb.append("                    ${if (p.sequence.isNotBlank()) "\"${p.sequence}\"" else "null"},\n")
            sb.append("                    ${p.autoIncrement}, ${p.insertable}, ${p.updatable}, false, ${p.isEnum}, ${p.enumAsString}\n")
            sb.append("                )$comma\n")
        }
        sb.append("            ),\n")
        sb.append("            ${if (e.tableName.isNotBlank()) "\"${e.tableName}\"" else "null"}\n")
        sb.append("        ))\n\n")
    }
}
