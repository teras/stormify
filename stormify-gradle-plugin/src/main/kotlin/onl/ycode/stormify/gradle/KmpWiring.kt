// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.stormify.gradle

import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.KotlinTarget

/**
 * Per-leaf-target metadata slot. Android compilations use per-variant
 * `kspDebugKotlin<Target>` / `compileReleaseKotlin<Target>` task names; every
 * other target has a single `kspKotlin<Target>` / `compileKotlin<Target>` pair.
 */
private data class MetaSlot(
    val target: KotlinTarget,
    val sourceSetName: String,
    val dirAbsPath: String,
    val kspTaskNames: Set<String>,
    val compileTaskMatcher: (String) -> Boolean,
)

/**
 * Bundles configuration shared by every [StormifyGenerateSources] task in a
 * KMP build — only `name` / `sourceSetName` vary per call.
 */
private data class GenSpec(
    val pkg: String,
    val pathsCls: String,
    val registrarCls: String,
    val shimVal: String,
    val generateRegistrar: Boolean,
    val sourceSetParents: Map<String, List<String>>,
    val leafSourceSets: Set<String>,
    val jvmFlavoredSourceSets: Set<String>,
    val metaDirs: List<java.io.File>,
    val dependsOnTaskNames: List<String>,
)

/**
 * Wires KSP and the [StormifyGenerateSources] task chain for Kotlin
 * Multiplatform projects.
 *
 *  - **Phase 1 — KSP per leaf target.** Each `kspKotlin<Target>` (or per-variant
 *    `kspDebug/ReleaseKotlin<Target>` for Android) runs `annproc` in metadata
 *    mode and writes one JSON file per discovered entity to
 *    `build/intermediates/stormify-meta/<leafSs>/`. Annproc records the
 *    declaring source set (commonMain, an intermediate, or the leaf itself)
 *    in the JSON.
 *
 *  - **Phase 2 — generation per source set.** One [StormifyGenerateSources]
 *    task is registered for every production source set (commonMain + every
 *    intermediate + every leaf). Each task feeds the same metadata into
 *    [Planner], which decides whether this source set should hold a plain
 *    `object Tables`, an `expect`, an `actual`, or nothing at all.
 */
internal fun wireKmp(project: Project, extension: StormifyExtension, pluginVersion: String) {
    val annprocCoord = "${StormifyPlugin.GROUP}:${StormifyPlugin.ANNPROC_ARTIFACT}:$pluginVersion"
    val stormifyCoord = "${StormifyPlugin.GROUP}:${StormifyPlugin.STORMIFY_ARTIFACT}:$pluginVersion"

    val kotlin = project.extensions.getByType(KotlinMultiplatformExtension::class.java)

    // Eager (not afterEvaluate): KSP snapshots its processor classpath very
    // early; afterEvaluate misses that snapshot and kspKotlin<Target> tasks
    // SKIP silently. The `-Xexpect-actual-classes` flag is added here so the
    // generated expect/actual classes don't surface a Beta warning the user
    // didn't opt into.
    val registeredTargets = mutableListOf<KotlinTarget>()
    kotlin.targets.all { target ->
        if (target.platformType == KotlinPlatformType.common) return@all
        registeredTargets += target

        val kspConfigName = "ksp${target.targetName.cap}"
        if (project.configurations.findByName(kspConfigName) == null) {
            project.configurations.create(kspConfigName)
        }
        project.dependencies.add(kspConfigName, annprocCoord)

        target.compilations.all { compilation ->
            compilation.compileTaskProvider.configure { task ->
                task.compilerOptions.freeCompilerArgs.add("-Xexpect-actual-classes")
            }
        }
    }

    project.afterEvaluate {
        if (registeredTargets.isEmpty()) {
            project.logger.warn(
                "Stormify plugin: no KMP targets registered. Add at least one target " +
                    "(jvm(), linuxX64(), ...) before applying onl.ycode.stormify."
            )
            return@afterEvaluate
        }

        val slots = registeredTargets.map(project::buildSlot)

        // Declare metaDir as a KSP task output so Gradle re-runs KSP after a
        // `clean` — annproc writes JSONs there via plain Java I/O, which
        // Gradle's up-to-date check would otherwise miss.
        slots.forEach { slot ->
            project.tasks.matching { it.name in slot.kspTaskNames }.configureEach { task ->
                setKspProcessorOption(task, "stormify.metaOutputDir", slot.dirAbsPath)
                task.outputs.dir(slot.dirAbsPath)
            }
        }

        val commonMain = kotlin.sourceSets.getByName("commonMain")
        project.dependencies.add(commonMain.implementationConfigurationName, stormifyCoord)

        // Production source sets: commonMain plus everything ending in "Main"
        // (skip *Test and KSP-internal helper sets we don't generate against).
        val productionSourceSets = kotlin.sourceSets.filter {
            it.name == "commonMain" || it.name.endsWith("Main")
        }
        // Each leaf compilation links commonMain via metadata klibs even when
        // no explicit `dependsOn(commonMain)` edge exists, so any production
        // source set without explicit parents (other than commonMain itself)
        // is treated as implicitly depending on commonMain.
        val parents: Map<String, List<String>> = productionSourceSets.associate { ss ->
            val explicit = ss.dependsOn.map { it.name }
            val effective = if (explicit.isEmpty() && ss.name != "commonMain")
                listOf("commonMain") else explicit
            ss.name to effective
        }
        val leafSourceSetNames = slots.map { it.sourceSetName }.toSet()
        val ancestorsByLeaf: Map<String, Set<String>> = leafSourceSetNames
            .associateWith { transitiveAncestors(parents, it) }

        val targetByLeaf: Map<String, KotlinTarget> = slots.associate { it.sourceSetName to it.target }
        val jvmFlavoredSet: Set<String> = productionSourceSets.mapNotNull { ss ->
            val descendantLeafs = leafSourceSetNames.filter { leafSs ->
                leafSs == ss.name || ss.name in ancestorsByLeaf[leafSs]!!
            }
            if (descendantLeafs.isEmpty()) return@mapNotNull null
            val allJvm = descendantLeafs.all { leafSs ->
                val t = targetByLeaf[leafSs]
                t?.platformType == KotlinPlatformType.jvm ||
                    t?.platformType == KotlinPlatformType.androidJvm
            }
            if (allJvm) ss.name else null
        }.toSet()

        val spec = GenSpec(
            pkg = extension.generatedPackage.getOrElse(StormifyPlugin.DEFAULT_GENERATED_PACKAGE),
            pathsCls = extension.pathsClass.getOrElse(StormifyPlugin.DEFAULT_PATHS_CLASS),
            registrarCls = extension.registrarClass.getOrElse(StormifyPlugin.DEFAULT_REGISTRAR_CLASS),
            shimVal = StormifyPlugin.SHIM_VAL,
            generateRegistrar = extension.generateRegistrar.getOrElse(true),
            sourceSetParents = parents,
            leafSourceSets = leafSourceSetNames,
            jvmFlavoredSourceSets = jvmFlavoredSet,
            metaDirs = slots.map { project.file(it.dirAbsPath) },
            dependsOnTaskNames = slots.flatMap { it.kspTaskNames },
        )

        val genBySourceSet: Map<String, TaskProvider<StormifyGenerateSources>> =
            productionSourceSets.associate { ss -> ss.name to registerGenerator(project, ss.name, spec) }

        // Plain `File` srcDir (not `TaskProvider`) avoids Gradle auto-adding
        // a dependsOn from KSP onto the generator — that would cycle:
        //   compileX → generator → kspX → srcDirs → generator.
        // Pre-create at configure time so IntelliJ's first sync sees them.
        productionSourceSets.forEach { ss ->
            val outFile = project.layout.buildDirectory
                .dir("generated/stormify/${ss.name}/kotlin").get().asFile
            outFile.mkdirs()
            ss.kotlin.srcDir(outFile)
        }

        slots.forEach { slot ->
            val deps = (ancestorsByLeaf[slot.sourceSetName]!! + slot.sourceSetName)
                .mapNotNull { genBySourceSet[it] }
            project.tasks.matching { slot.compileTaskMatcher(it.name) }.configureEach { task ->
                task.dependsOn(deps)
            }
        }
        productionSourceSets.filter { it.name !in leafSourceSetNames }.forEach { ss ->
            val metaCompileTask = "compile${ss.name.cap}KotlinMetadata"
            project.tasks.matching { it.name == metaCompileTask }.configureEach { task ->
                genBySourceSet[ss.name]?.let { task.dependsOn(it) }
            }
        }
    }
}

private fun Project.buildSlot(target: KotlinTarget): MetaSlot {
    val cap = target.targetName.cap
    val ssName = "${target.targetName}Main"
    val dir = layout.buildDirectory.dir("intermediates/stormify-meta/$ssName").get().asFile
    val isAndroid = target.platformType == KotlinPlatformType.androidJvm
    val kspTasks = if (isAndroid) setOf("kspDebugKotlin$cap", "kspReleaseKotlin$cap")
    else setOf("kspKotlin$cap")
    // Android compile-task matcher must mirror the production-only KSP set:
    // matching `compileDebugUnitTestKotlinAndroid` (or any *Test* variant)
    // wires the generator to a compilation whose KSP never ran, breaking
    // incremental builds.
    val compileMatcher: (String) -> Boolean = if (isAndroid)
        { n -> n.startsWith("compile") && n.endsWith("Kotlin$cap") && !n.contains("Test") }
    else { n -> n == "compileKotlin$cap" }
    return MetaSlot(target, ssName, dir.absolutePath, kspTasks, compileMatcher)
}

private fun registerGenerator(
    project: Project,
    sourceSetName: String,
    spec: GenSpec,
): TaskProvider<StormifyGenerateSources> {
    val outDir = project.layout.buildDirectory.dir("generated/stormify/$sourceSetName/kotlin")
    val task = project.tasks.register("stormifyGenerate${sourceSetName.cap}", StormifyGenerateSources::class.java) { t ->
        t.metadataDirs.from(spec.metaDirs)
        t.outputDir.set(outDir)
        t.sourceSetName.set(sourceSetName)
        t.generatedPackage.set(spec.pkg)
        t.pathsClass.set(spec.pathsCls)
        t.registrarClass.set(spec.registrarCls)
        t.shimVal.set(spec.shimVal)
        t.generateRegistrar.set(spec.generateRegistrar)
        t.kmpProject.set(true)
        t.sourceSetParents.set(spec.sourceSetParents)
        t.leafSourceSets.set(spec.leafSourceSets)
        t.jvmFlavoredSourceSets.set(spec.jvmFlavoredSourceSets)
    }
    spec.dependsOnTaskNames.forEach { dep -> task.configure { it.dependsOn(dep) } }
    return task
}
