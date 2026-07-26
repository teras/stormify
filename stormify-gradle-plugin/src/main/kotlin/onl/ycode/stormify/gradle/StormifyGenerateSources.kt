// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.stormify.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Reads JSON metadata produced by `annproc` and emits Kotlin sources for one
 * source set. The shape — plain `object`, `expect`, or `actual` — is derived
 * by [Planner] from the entity placement across the project's source-set
 * hierarchy. Plain `kotlin("jvm")` and Android projects bypass the planner
 * and always emit a flat `object Tables`.
 */
@CacheableTask
abstract class StormifyGenerateSources : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val metadataDirs: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

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

    /** false → standalone (plain JVM/Android) emission, ignores the graph inputs below. */
    @get:Input
    abstract val kmpProject: Property<Boolean>

    /** sourceSet → its direct `dependsOn` parents. */
    @get:Input
    abstract val sourceSetParents: MapProperty<String, List<String>>

    /** Source sets that correspond to a `KotlinTarget` (the leafs). */
    @get:Input
    abstract val leafSourceSets: SetProperty<String>

    /** Source sets whose emission should carry `@JvmField` / `@get:JvmName`. */
    @get:Input
    abstract val jvmFlavoredSourceSets: SetProperty<String>

    /**
     * Source set that hosts the `expect val` shim when every leaf has an
     * `actual val`. For the production graph this is `commonMain`; for the
     * parallel test graph wired by the plugin it is `commonTest`.
     */
    @get:Input
    abstract val rootSourceSetName: Property<String>

    @TaskAction
    fun generate() {
        val outRoot = outputDir.get().asFile
        // Plain stdlib delete; `project.delete(...)` would capture `project`
        // and break configuration cache.
        outRoot.deleteRecursively()
        val mySs = sourceSetName.get()
        val ctx = EmissionContext(
            pkg = generatedPackage.get(),
            pathsCls = pathsClass.get(),
            registrarCls = registrarClass.get(),
            shimName = shimVal.get(),
            mySs = mySs,
            jvm = jvmFlavoredSourceSets.get().contains(mySs),
        )
        val pkgDir = File(outRoot, ctx.pkg.replace('.', '/')).apply { mkdirs() }
        val entities = collectEntities()
        val genRegistrar = generateRegistrar.get()

        if (!kmpProject.get()) {
            emitPaths(pkgDir, ctx, entities, kmpActual = false, actualEntities = emptySet())
            if (genRegistrar) {
                writeRegistrar(pkgDir, ctx, entities)
                writeShim(pkgDir, ctx, prefix = "", initializer = ctx.registrarCls)
            }
            return
        }

        val planner = Planner(
            PlannerInputs(
                entities = entities,
                parents = sourceSetParents.get(),
                leafSourceSets = leafSourceSets.get(),
            )
        )
        when (val role = planner.roleOf(mySs)) {
            null -> Unit
            is Role.Plain -> emitPaths(pkgDir, ctx, role.visibleEntities, kmpActual = false, actualEntities = emptySet())
            is Role.Expect -> emitExpect(pkgDir, ctx, role.visibleEntities)
            is Role.Actual -> emitPaths(pkgDir, ctx, role.visibleEntities, kmpActual = true, actualEntities = role.actualForExpect)
        }

        if (genRegistrar) emitShimAndRegistrar(pkgDir, ctx, planner)
    }

    /**
     * Registrar + shim emission rules for KMP:
     *  - The registrar (`GeneratedEntities` object) lives at every leaf with
     *    visible entities. It references platform-specific runtime helpers
     *    (`TypeUtils.castTo`, `EntityMeta.register`, …) that don't legally
     *    compile in commonMain metadata, so it never lives there.
     *  - The shim (`val stormifyEntities`) is the user-facing handle. An
     *    `expect val` is published in commonMain whenever every leaf has at
     *    least one visible entity (so a matching `actual val` exists in each
     *    leaf). When some leaf is empty the expect is omitted — the shim
     *    becomes leaf-only.
     */
    private fun emitShimAndRegistrar(pkgDir: File, ctx: EmissionContext, planner: Planner) {
        val leafs = leafSourceSets.get()
        val visibleByLeaf = planner.visibleByLeaf
        val everyLeafHasEntities = leafs.isNotEmpty() && leafs.all { visibleByLeaf[it]?.isNotEmpty() == true }

        if (ctx.mySs == rootSourceSetName.get() && everyLeafHasEntities)
            writeShim(pkgDir, ctx, prefix = "expect ", initializer = null)

        if (ctx.mySs in leafs) {
            val visibleHere = visibleByLeaf[ctx.mySs] ?: emptyList()
            if (visibleHere.isEmpty()) return
            writeRegistrar(pkgDir, ctx, visibleHere)
            writeShim(
                pkgDir, ctx,
                prefix = if (everyLeafHasEntities) "actual " else "",
                initializer = ctx.registrarCls,
            )
        }
    }

    private fun writeShim(pkgDir: File, ctx: EmissionContext, prefix: String, initializer: String?) {
        File(pkgDir, "StormifyShim.kt").writeText(buildString {
            append("package ${ctx.pkg}\n\n")
            append("import onl.ycode.stormify.EntityRegistrar\n\n")
            append("${prefix}val ${ctx.shimName}: EntityRegistrar")
            if (initializer != null) append(" = $initializer")
            append("\n")
        })
    }

    private fun collectEntities(): List<EntityMeta> {
        val byQn = LinkedHashMap<String, EntityMeta>()
        metadataDirs.files.forEach { dir ->
            if (!dir.isDirectory) return@forEach
            dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.forEach { f ->
                val em = parseEntityJsonCached(f)
                if (em.qualifiedName.isNotEmpty()) {
                    val prev = byQn.putIfAbsent(em.qualifiedName, em)
                    if (prev != null && prev.sourceSet != em.sourceSet) {
                        logger.debug("Stormify: duplicate entity ${em.qualifiedName} — kept '${prev.sourceSet}', dropped '${em.sourceSet}'")
                    }
                }
            }
        }
        return byQn.values.toList()
    }

    private fun emitExpect(pkgDir: File, ctx: EmissionContext, entities: List<EntityMeta>) {
        val knownQns = entities.map { it.qualifiedName }.toSet()

        entities.forEach { e ->
            File(pkgDir, "${e.simpleName}Ref.kt").writeText(buildString {
                append("package ${ctx.pkg}\n\n")
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

        File(pkgDir, "${ctx.pathsCls}.kt").writeText(buildString {
            append("@file:Suppress(\"unused\")\n")
            append("package ${ctx.pkg}\n\n")
            append("expect object ${ctx.pathsCls} {\n")
            entities.forEach { e -> append("    val ${e.simpleName}_: ${e.simpleName}Ref\n") }
            append("}\n")
        })
    }

    private fun emitPaths(
        pkgDir: File,
        ctx: EmissionContext,
        visible: List<EntityMeta>,
        actualEntities: Set<EntityMeta>,
        kmpActual: Boolean,
    ) {
        val visibleSimpleNames = visible.map { it.simpleName }.toSet()
        val jvmField = if (ctx.jvm) "@JvmField " else ""
        val actualQns = actualEntities.map { it.qualifiedName }.toSet()

        visible.forEach { e ->
            val isActual = e.qualifiedName in actualQns
            File(pkgDir, "${e.simpleName}Ref.kt").writeText(buildString {
                append("package ${ctx.pkg}\n\n")
                if (ctx.jvm) {
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
                        if (ctx.jvm) append("    @get:JvmName(\"${p.name}\")\n")
                        append("    ${actualKw}val ${p.name} get() = ${refSimple}Ref(\"\${toString()}${p.name}.\")\n")
                    } else {
                        append("    $jvmField${actualKw}val ${p.name} = ScalarPath(\"\${toString()}${p.name}\")\n")
                    }
                }
                append("}\n")
            })
        }

        File(pkgDir, "${ctx.pathsCls}.kt").writeText(buildString {
            append("@file:Suppress(\"unused\")\n")
            append("package ${ctx.pkg}\n\n")
            if (ctx.jvm) append("import kotlin.jvm.JvmField\n\n")
            val objectKw = if (kmpActual) "actual object" else "object"
            append("$objectKw ${ctx.pathsCls} {\n")
            visible.forEach { e ->
                val actualKw = if (e.qualifiedName in actualQns) "actual " else ""
                append("    $jvmField${actualKw}val ${e.simpleName}_ = ${e.simpleName}Ref(\"\")\n")
            }
            append("}\n")
        })
    }

    private fun writeRegistrar(pkgDir: File, ctx: EmissionContext, visible: List<EntityMeta>) {
        val enumTypes = visible.flatMap { it.enumTypes }.distinct()
        // Generated declarations are named after simple names (UserRef, Tables.User_,
        // enum imports) and all land in one file — same-named classes in different
        // packages would produce cryptic redeclaration / conflicting-import errors.
        // Fail fast with an actionable message.
        // NOTE: the same check exists in the annotation processor (KotlinTableProcessor).
        val bySimpleName = mutableMapOf<String, MutableSet<String>>()
        visible.forEach { bySimpleName.getOrPut(it.simpleName) { mutableSetOf() } += it.qualifiedName }
        enumTypes.forEach { bySimpleName.getOrPut(it.substringAfterLast('.')) { mutableSetOf() } += it }
        val clashes = bySimpleName.filterValues { it.size > 1 }
        if (clashes.isNotEmpty()) throw GradleException(
            "Stormify: generated code is named after simple class names, but " +
                    clashes.entries.joinToString("; ") { (name, qns) -> "'$name' (${qns.joinToString(", ")})" } +
                    " share the same simple name. Rename one of them to avoid the collision."
        )
        File(pkgDir, "${ctx.registrarCls}.kt").writeText(buildString {
            append("@file:Suppress(\"unused\", \"UNCHECKED_CAST\")\n")
            append("package ${ctx.pkg}\n\n")
            append("import onl.ycode.stormify.DbValue\n")
            append("import onl.ycode.stormify.EntityMeta\n")
            append("import onl.ycode.stormify.EntityRegistrar\n")
            append("import onl.ycode.stormify.EnumRegistry\n")
            append("import onl.ycode.stormify.PropertyMeta\n")
            append("import onl.ycode.stormify.TypeUtils.castTo\n\n")
            visible.forEach { append("import ${it.qualifiedName}\n") }
            enumTypes.forEach { append("import $it\n") }
            append("\nobject ${ctx.registrarCls} : EntityRegistrar {\n")
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
        e.properties.joinTo(sb, separator = ",\n", postfix = "\n") { p ->
            val notNull = if (!p.nullable)
                " ?: throw IllegalArgumentException(\"${p.name.kEsc()} cannot be null in ${e.simpleName.kEsc()}\")"
            else ""
            buildString {
                append("                PropertyMeta(\n")
                append("                    \"${p.name.kEsc()}\", ${p.type}::class, ${p.isReference},\n")
                append("                    { it.${p.name} },\n")
                append("                    { en, v, s -> en.${p.name} = (castTo(${p.type}::class, v, s) as? ${p.fullType})$notNull },\n")
                append("                    ${if (p.dbName != p.name) "\"${p.dbName.kEsc()}\"" else "null"},\n")
                append("                    ${p.primary},\n")
                append("                    ${if (p.sequence.isNotBlank()) "\"${p.sequence.kEsc()}\"" else "null"},\n")
                append("                    ${p.autoIncrement}, ${p.insertable}, ${p.updatable}, false, ${p.isEnum}, ${p.enumAsString}\n")
                append("                )")
            }
        }
        sb.append("            ),\n")
        sb.append("            ${if (e.tableName.isNotBlank()) "\"${e.tableName.kEsc()}\"" else "null"}\n")
        sb.append("        ))\n\n")
    }

    /** Bundles per-task constants resolved from `Property<T>` getters once. */
    private data class EmissionContext(
        val pkg: String,
        val pathsCls: String,
        val registrarCls: String,
        val shimName: String,
        val mySs: String,
        val jvm: Boolean,
    )
}

/** Escapes characters that would break a Kotlin string-literal interpolation. */
private fun String.kEsc(): String = buildString(length) {
    for (c in this@kEsc) when (c) {
        '\\' -> append("\\\\")
        '"' -> append("\\\"")
        '$' -> append("\\$")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> append(c)
    }
}

// JSON parse cache, scoped to the daemon JVM. Multiple StormifyGenerateSources
// tasks run per build (one per production source set in KMP) and read the
// same JSON files; caching avoids reparsing N×M times. Keyed by canonical
// path + lastModified so a rewrite invalidates the entry. Bounded so a
// long-running daemon across many projects doesn't accumulate stale entries
// for deleted JSONs.
private const val PARSE_CACHE_MAX = 4096
private val parseCache = java.util.Collections.synchronizedMap(
    object : LinkedHashMap<String, Pair<Long, EntityMeta>>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Pair<Long, EntityMeta>>) =
            size > PARSE_CACHE_MAX
    }
)

private fun parseEntityJsonCached(f: File): EntityMeta {
    val key = f.absolutePath
    val ts = f.lastModified()
    synchronized(parseCache) {
        parseCache[key]?.let { (cachedTs, em) -> if (cachedTs == ts) return em }
    }
    val em = parseEntityJson(f.readText())
    synchronized(parseCache) { parseCache[key] = ts to em }
    return em
}
