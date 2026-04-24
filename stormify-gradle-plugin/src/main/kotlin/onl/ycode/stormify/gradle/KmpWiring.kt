// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.stormify.gradle

import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.KotlinTarget

/**
 * Wires KSP and the `StormifyGenerateSources` task chain for Kotlin
 * Multiplatform projects using the **two-phase architecture**:
 *
 *  - **Phase 1 (KSP per target)**: each `kspKotlin<Target>` task runs
 *    `annproc` in metadata mode (option `stormify.metaOutputDir` set per
 *    target). The processor writes one JSON file per discovered entity to
 *    a per-target intermediates directory using plain Java I/O — no KSP
 *    code generator involvement, hence no `outputBaseDir` wipe risk.
 *
 *  - **Phase 2 (StormifyGenerateSources tasks)**: one generator task per
 *    source set. Each reads the JSONs from all per-target dirs (deduping
 *    by `qualifiedName`) and emits Kotlin sources tailored to its source
 *    set:
 *    - `commonMain` → `expect class …Ref`, `expect object Tables`, and
 *      `expect val stormifyEntities`.
 *    - jvm-flavoured target → `actual` with `@JvmField` / `@get:JvmName`.
 *    - native targets → `actual` with no JVM annotations.
 *
 * Each generator's output dir is registered as a `srcDir` of the matching
 * Kotlin source set via `TaskProvider`-based wiring, so Gradle establishes
 * the compile→generate→KSP dependency chain automatically.
 */
internal fun wireKmp(project: Project, extension: StormifyExtension, pluginVersion: String) {
    val annprocCoord =
        "${StormifyPlugin.ANNPROC_GROUP}:${StormifyPlugin.ANNPROC_ARTIFACT}:$pluginVersion"
    val stormifyCoord =
        "${StormifyPlugin.STORMIFY_GROUP}:${StormifyPlugin.STORMIFY_ARTIFACT}:$pluginVersion"

    val kotlin = project.extensions.getByType(KotlinMultiplatformExtension::class.java)

    // Eagerly register annproc against every per-target ksp configuration.
    // KSP snapshots its processor classpath very early; afterEvaluate is
    // too late and the kspKotlin<Target> task would silently SKIPPED.
    val registeredTargets = mutableListOf<KotlinTarget>()
    kotlin.targets.all { target ->
        if (target.platformType == KotlinPlatformType.common) return@all
        registeredTargets += target

        val capitalized = target.targetName.replaceFirstChar { it.uppercase() }
        val kspConfigName = "ksp$capitalized"
        if (project.configurations.findByName(kspConfigName) == null) {
            project.configurations.create(kspConfigName)
        }
        project.dependencies.add(kspConfigName, annprocCoord)
    }

    // Suppress Kotlin 2.x "Beta" warning for the expect/actual classes the
    // plugin generates — the user didn't opt in themselves.
    kotlin.targets.all { target ->
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

        val pkg = extension.generatedPackage.getOrElse(StormifyPlugin.DEFAULT_GENERATED_PACKAGE)
        val pathsCls = extension.pathsClass.getOrElse(StormifyPlugin.DEFAULT_PATHS_CLASS)
        val registrarCls = extension.registrarClass.getOrElse(StormifyPlugin.DEFAULT_REGISTRAR_CLASS)
        val genRegistrar = extension.generateRegistrar.getOrElse(true)
        val shimVal = "stormifyEntities"


        // Located under `build/intermediates/stormify-meta/` — NOT inside KSP's
        // `outputBaseDir`, so KSP's per-run wipe never touches them.
        data class MetaSlot(
            val target: KotlinTarget,
            val sourceSetName: String,
            val dirAbsPath: String,
            val kspTaskName: String,
        )

        val slots = registeredTargets.map { target ->
            val capitalized = target.targetName.replaceFirstChar { it.uppercase() }
            val kspTaskName = "kspKotlin$capitalized"
            val ssName = "${target.targetName}Main"
            val dir = project.layout.buildDirectory
                .dir("intermediates/stormify-meta/$ssName").get().asFile
            MetaSlot(target, ssName, dir.absolutePath, kspTaskName)
        }

        slots.forEach { slot ->
            project.tasks.matching { it.name == slot.kspTaskName }.configureEach { task ->
                setKspProcessorOption(task, "stormify.metaOutputDir", slot.dirAbsPath)
            }
        }

        val commonMain = kotlin.sourceSets.getByName("commonMain")
        project.dependencies.add(commonMain.implementationConfigurationName, stormifyCoord)

        val allMetaDirs = slots.map { project.file(it.dirAbsPath) }

        val commonGen = registerGenerator(
            project, "stormifyGenerateCommon",
            allMetaDirs, TargetKind.COMMON, "commonMain",
            pkg, pathsCls, registrarCls, shimVal, genRegistrar,
            slots.map { it.kspTaskName },
        )

        val perTargetGen = slots.associateWith { slot ->
            val isJvm = slot.target.platformType == KotlinPlatformType.jvm
            val targetKind = if (isJvm) TargetKind.JVM else TargetKind.NATIVE
            val capitalized = slot.target.targetName.replaceFirstChar { it.uppercase() }
            val taskName = "stormifyGenerate$capitalized"
            registerGenerator(
                project, taskName,
                allMetaDirs, targetKind, slot.sourceSetName,
                pkg, pathsCls, registrarCls, shimVal, genRegistrar,
                listOf(slot.kspTaskName),
            )
        }

        // Pass srcDirs as plain `File` (not `TaskProvider`) so Gradle doesn't
        // auto-add a dependsOn from KSP onto the generator — that would cycle:
        //   compileX → generator → kspX → srcDirs → generator.
        // We wire `compileX dependsOn generator` explicitly below instead.
        // Pre-created at configure time so IntelliJ's first sync registers
        // the dir as a source root.
        val commonOutFile = project.layout.buildDirectory
            .dir("generated/stormify/commonMain/kotlin").get().asFile
        commonOutFile.mkdirs()
        commonMain.kotlin.srcDir(commonOutFile)

        slots.forEach { slot ->
            val sourceSet = kotlin.sourceSets.getByName(slot.sourceSetName)
            val outFile = project.layout.buildDirectory
                .dir("generated/stormify/${slot.sourceSetName}/kotlin").get().asFile
            outFile.mkdirs()
            sourceSet.kotlin.srcDir(outFile)
        }

        project.tasks.matching { it.name == "compileCommonMainKotlinMetadata" }
            .configureEach { it.dependsOn(commonGen) }
        slots.forEach { slot ->
            val capitalized = slot.target.targetName.replaceFirstChar { it.uppercase() }
            val compileTaskName = "compileKotlin$capitalized"
            project.tasks.matching { it.name == compileTaskName }.configureEach { task ->
                task.dependsOn(commonGen, perTargetGen[slot]!!)
            }
        }
    }
}

private fun registerGenerator(
    project: Project,
    name: String,
    metaDirs: List<java.io.File>,
    targetKind: TargetKind,
    sourceSetName: String,
    pkg: String,
    pathsCls: String,
    registrarCls: String,
    shimVal: String,
    generateRegistrar: Boolean,
    dependsOnTaskNames: List<String>,
): TaskProvider<StormifyGenerateSources> {
    val outDir = project.layout.buildDirectory.dir("generated/stormify/$sourceSetName/kotlin")
    val task = project.tasks.register(name, StormifyGenerateSources::class.java) { t ->
        t.metadataDirs.from(metaDirs)
        t.outputDir.set(outDir)
        t.targetKind.set(targetKind)
        t.sourceSetName.set(sourceSetName)
        t.generatedPackage.set(pkg)
        t.pathsClass.set(pathsCls)
        t.registrarClass.set(registrarCls)
        t.shimVal.set(shimVal)
        t.generateRegistrar.set(generateRegistrar)
    }
    dependsOnTaskNames.forEach { dep ->
        task.configure { it.dependsOn(dep) }
    }
    return task
}


