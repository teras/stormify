// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.stormify.gradle

import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import org.jetbrains.kotlin.gradle.plugin.KotlinTarget

private val ANDROID_TEST_KSP_CONFIGS = listOf("kspAndroidTestDebug", "kspAndroidTestRelease")

private data class PlannerWiringInputs(
    val sourceSets: List<KotlinSourceSet>,
    val parents: Map<String, List<String>>,
    val leafSourceSetNames: Set<String>,
    val ancestorsByLeaf: Map<String, Set<String>>,
    val jvmFlavoredSourceSets: Set<String>,
)

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
    val rootSourceSetName: String,
)

/**
 * Parameter bundle describing one wiring pass — production (`commonMain`
 * graph) or test (`commonTest` graph). Lets [wireKmpScope] handle both
 * without branching on which side it's wiring.
 */
private data class KmpScope(
    val isTest: Boolean,
    val pathsCls: String,
    val registrarCls: String,
    val shimVal: String,
) {
    val rootSourceSetName: String get() = if (isTest) "commonTest" else "commonMain"
    val ssNameFilter: (String) -> Boolean get() = if (isTest)
        { n -> n == "commonTest" || n.endsWith("Test") }
    else { n -> n == "commonMain" || n.endsWith("Main") }
}

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
    val selfHosted = extension.selfHosted.getOrElse(false)
    val registeredTargets = mutableListOf<KotlinTarget>()
    kotlin.targets.all { target ->
        if (target.platformType == KotlinPlatformType.common) return@all
        registeredTargets += target

        val kspConfigName = "ksp${target.targetName.cap}"
        project.configurations.maybeCreate(kspConfigName)
        if (!selfHosted) project.dependencies.add(kspConfigName, annprocCoord)

        // AGP+KMP exposes the unit-test configs as `kspAndroidTestDebug` /
        // `kspAndroidTestRelease`; every other KMP target uses `ksp<Target>Test`.
        val testKspConfigs = if (target.platformType == KotlinPlatformType.androidJvm)
            ANDROID_TEST_KSP_CONFIGS
        else listOf("ksp${target.targetName.cap}Test")
        testKspConfigs.forEach { name ->
            project.configurations.maybeCreate(name)
            if (!selfHosted) project.dependencies.add(name, annprocCoord)
        }

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

        val commonMain = kotlin.sourceSets.getByName("commonMain")
        if (!selfHosted) project.dependencies.add(commonMain.implementationConfigurationName, stormifyCoord)

        wireKmpScope(project, extension, kotlin, registeredTargets, KmpScope(
            isTest = false,
            pathsCls = extension.pathsClass.getOrElse(StormifyPlugin.DEFAULT_PATHS_CLASS),
            registrarCls = extension.registrarClass.getOrElse(StormifyPlugin.DEFAULT_REGISTRAR_CLASS),
            shimVal = StormifyPlugin.SHIM_VAL,
        ))

        wireKmpScope(project, extension, kotlin, registeredTargets, KmpScope(
            isTest = true,
            pathsCls = extension.testPathsClass.getOrElse(StormifyPlugin.DEFAULT_TEST_PATHS_CLASS),
            registrarCls = extension.testRegistrarClass.getOrElse(StormifyPlugin.DEFAULT_TEST_REGISTRAR_CLASS),
            shimVal = StormifyPlugin.TEST_SHIM_VAL,
        ))
    }
}

/**
 * Single wiring pass over one source-set graph (production or test):
 * build slots, declare metaDirs as KSP outputs, compute the planner inputs,
 * and register/chain the per-source-set generators.
 *
 * Each leaf compilation links the root (commonMain/commonTest) via metadata
 * klibs even when no explicit `dependsOn(root)` edge exists, so leafs
 * without explicit parents implicitly depend on the root.
 */
private fun wireKmpScope(
    project: Project,
    extension: StormifyExtension,
    kotlin: KotlinMultiplatformExtension,
    registeredTargets: List<KotlinTarget>,
    scope: KmpScope,
) {
    // Skip the test pass entirely on projects with no test source sets — saves
    // building slots, registering generators, and walking the task graph.
    if (scope.isTest && kotlin.sourceSets.none { scope.ssNameFilter(it.name) }) return

    val slots = registeredTargets.map { project.buildSlot(it, scope.isTest) }

    slots.forEach { slot -> wireKspMetaDir(project, slot.kspTaskNames, slot.dirAbsPath) }

    val inputs = buildPlannerWiringInputs(kotlin, slots, scope.rootSourceSetName, scope.ssNameFilter)

    val spec = GenSpec(
        pkg = extension.generatedPackage.getOrElse(StormifyPlugin.DEFAULT_GENERATED_PACKAGE),
        pathsCls = scope.pathsCls,
        registrarCls = scope.registrarCls,
        shimVal = scope.shimVal,
        generateRegistrar = extension.generateRegistrar.getOrElse(true),
        sourceSetParents = inputs.parents,
        leafSourceSets = inputs.leafSourceSetNames,
        jvmFlavoredSourceSets = inputs.jvmFlavoredSourceSets,
        metaDirs = slots.map { project.file(it.dirAbsPath) },
        dependsOnTaskNames = slots.flatMap { it.kspTaskNames },
        rootSourceSetName = scope.rootSourceSetName,
    )
    wirePlannerOutputs(project, slots, inputs, spec)
}

/**
 * Filter [kotlin]'s source sets via [filter], build the dependsOn graph for
 * the filtered subset (rooting orphans at [rootName]), and compute the
 * derived sets the planner and gen-task wiring need.
 */
private fun buildPlannerWiringInputs(
    kotlin: KotlinMultiplatformExtension,
    slots: List<MetaSlot>,
    rootName: String,
    filter: (String) -> Boolean,
): PlannerWiringInputs {
    val sourceSets = kotlin.sourceSets.filter { filter(it.name) }
    val parents: Map<String, List<String>> = sourceSets.associate { ss ->
        val explicit = ss.dependsOn.map { it.name }.filter(filter)
        val effective = if (explicit.isEmpty() && ss.name != rootName) listOf(rootName) else explicit
        ss.name to effective
    }
    val leafs = slots.map { it.sourceSetName }.toSet()
    val ancestorsByLeaf = leafs.associateWith { transitiveAncestors(parents, it) }
    val targetByLeaf = slots.associate { it.sourceSetName to it.target }
    val jvmFlavored = sourceSets.mapNotNull { ss ->
        val descendantLeafs = leafs.filter { it == ss.name || ss.name in ancestorsByLeaf.getValue(it) }
        if (descendantLeafs.isEmpty()) return@mapNotNull null
        val allJvm = descendantLeafs.all { leafSs ->
            val t = targetByLeaf[leafSs]
            t?.platformType == KotlinPlatformType.jvm ||
                t?.platformType == KotlinPlatformType.androidJvm
        }
        if (allJvm) ss.name else null
    }.toSet()
    return PlannerWiringInputs(sourceSets, parents, leafs, ancestorsByLeaf, jvmFlavored)
}

/**
 * Plain `File` srcDir (not `TaskProvider`) avoids Gradle auto-adding a
 * dependsOn from KSP onto the generator — that would cycle:
 * `compileX → generator → kspX → srcDirs → generator`. Pre-create at
 * configure time so IntelliJ's first sync sees the dirs.
 */
private fun wirePlannerOutputs(
    project: Project,
    slots: List<MetaSlot>,
    inputs: PlannerWiringInputs,
    spec: GenSpec,
) {
    val genBySourceSet: Map<String, TaskProvider<StormifyGenerateSources>> =
        inputs.sourceSets.associate { ss -> ss.name to registerGenerator(project, ss.name, spec) }

    inputs.sourceSets.forEach { ss ->
        val outFile = project.layout.buildDirectory
            .dir("generated/stormify/${ss.name}/kotlin").get().asFile
        outFile.mkdirs()
        ss.kotlin.srcDir(outFile)
    }

    slots.forEach { slot ->
        val deps = (inputs.ancestorsByLeaf.getValue(slot.sourceSetName) + slot.sourceSetName)
            .mapNotNull { genBySourceSet[it] }
        project.tasks.matching { slot.compileTaskMatcher(it.name) }.configureEach { task ->
            task.dependsOn(deps)
        }
    }
    inputs.sourceSets.filter { it.name !in inputs.leafSourceSetNames }.forEach { ss ->
        val metaCompileTask = "compile${ss.name.cap}KotlinMetadata"
        project.tasks.matching { it.name == metaCompileTask }.configureEach { task ->
            genBySourceSet[ss.name]?.let { task.dependsOn(it) }
        }
    }
}

private fun Project.buildSlot(target: KotlinTarget, isTest: Boolean): MetaSlot {
    val cap = target.targetName.cap
    val isAndroid = target.platformType == KotlinPlatformType.androidJvm
    val ssName = when {
        !isTest -> "${target.targetName}Main"
        isAndroid -> "androidUnitTest"
        else -> "${target.targetName}Test"
    }
    val dir = layout.buildDirectory.dir("intermediates/stormify-meta/$ssName").get().asFile
    val kspTasks = when {
        isAndroid && isTest -> setOf("kspDebugUnitTestKotlin$cap", "kspReleaseUnitTestKotlin$cap")
        isAndroid -> setOf("kspDebugKotlin$cap", "kspReleaseKotlin$cap")
        isTest -> setOf("kspTestKotlin$cap")
        else -> setOf("kspKotlin$cap")
    }
    // Android production compile-task matcher must mirror the production-only
    // KSP set: matching `compileDebugUnitTestKotlinAndroid` would wire the
    // generator to a compilation whose KSP never ran, breaking incremental builds.
    val compileMatcher: (String) -> Boolean = when {
        isAndroid && isTest -> { n -> n.startsWith("compile") && n.contains("UnitTestKotlin$cap") }
        isAndroid -> { n -> n.startsWith("compile") && n.endsWith("Kotlin$cap") && !n.contains("Test") }
        isTest -> { n -> n == "compileTestKotlin$cap" }
        else -> { n -> n == "compileKotlin$cap" }
    }
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
        t.rootSourceSetName.set(spec.rootSourceSetName)
        spec.dependsOnTaskNames.forEach(t::dependsOn)
    }
    return task
}
