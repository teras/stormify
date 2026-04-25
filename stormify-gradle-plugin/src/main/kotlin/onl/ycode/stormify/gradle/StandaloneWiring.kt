// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.stormify.gradle

import org.gradle.api.GradleException
import org.gradle.api.Project

/**
 * Shared wiring for single-source-set projects (plain `kotlin("jvm")` and
 * Android). annproc writes JSONs to a per-project intermediates dir, the
 * generator reads them and emits a flat set of Kotlin sources (no
 * `expect`/`actual`) into `build/generated/stormify/main/kotlin`, which is
 * registered as a `srcDir` of the `main` source set.
 *
 * @param platformLabel human-readable label used in diagnostic messages
 *   ("JVM", "Android").
 * @param artifactSuffix suffix on the stormify runtime artifact ("-jvm",
 *   "-android").
 * @param kspTaskNames KSP task names that must receive the
 *   `stormify.metaOutputDir` option and that the generator must depend on.
 * @param extensionName Gradle `project.extensions` name exposing the Kotlin
 *   source-set container (`"kotlin"` for plain JVM, `"android"` for AGP).
 * @param srcDirGetters reflective getters on the `main` source-set to which
 *   the generated dir is attached — `"getKotlin"` for plain JVM;
 *   `"getKotlin"` + `"getJava"` for Android (non-KMP Android consumes .kt
 *   through java srcDirs).
 * @param compileTaskMatcher picks the compile tasks that must depend on
 *   the generator so the srcDir is populated before compile runs.
 */
internal fun wireStandalone(
    project: Project,
    extension: StormifyExtension,
    pluginVersion: String,
    platformLabel: String,
    artifactSuffix: String,
    kspTaskNames: Set<String>,
    extensionName: String,
    srcDirGetters: List<String>,
    compileTaskMatcher: (String) -> Boolean,
) {
    project.dependencies.add(
        "ksp",
        "${StormifyPlugin.GROUP}:${StormifyPlugin.ANNPROC_ARTIFACT}:$pluginVersion"
    )
    project.dependencies.add(
        "implementation",
        "${StormifyPlugin.GROUP}:${StormifyPlugin.STORMIFY_ARTIFACT}$artifactSuffix:$pluginVersion"
    )

    val metaDir = project.layout.buildDirectory
        .dir("intermediates/stormify-meta/main").get().asFile

    // Declare metaDir as a KSP task output so Gradle re-runs KSP after `clean`
    // wipes the dir — annproc writes JSONs there via plain Java I/O, which
    // Gradle's up-to-date check would otherwise miss.
    project.tasks.matching { it.name in kspTaskNames }.configureEach { task ->
        setKspProcessorOption(task, "stormify.metaOutputDir", metaDir.absolutePath)
        task.outputs.dir(metaDir)
    }

    project.afterEvaluate {
        val pkg = extension.generatedPackage.getOrElse(StormifyPlugin.DEFAULT_GENERATED_PACKAGE)
        val pathsCls = extension.pathsClass.getOrElse(StormifyPlugin.DEFAULT_PATHS_CLASS)
        val registrarCls = extension.registrarClass.getOrElse(StormifyPlugin.DEFAULT_REGISTRAR_CLASS)
        val genRegistrar = extension.generateRegistrar.getOrElse(true)

        val outDir = project.layout.buildDirectory.dir("generated/stormify/main/kotlin")
        val gen = project.tasks.register("stormifyGenerate", StormifyGenerateSources::class.java) { t ->
            t.metadataDirs.from(metaDir)
            t.outputDir.set(outDir)
            t.sourceSetName.set("main")
            t.generatedPackage.set(pkg)
            t.pathsClass.set(pathsCls)
            t.registrarClass.set(registrarCls)
            t.shimVal.set(StormifyPlugin.SHIM_VAL)
            t.generateRegistrar.set(genRegistrar)
            t.kmpProject.set(false)
            t.sourceSetParents.set(emptyMap<String, List<String>>())
            t.leafSourceSets.set(emptySet<String>())
            t.jvmFlavoredSourceSets.set(setOf("main"))
            t.dependsOn(project.tasks.matching { it.name in kspTaskNames })
        }

        // Plain File srcDir avoids Gradle auto-dependsOn, which would cycle
        // compile → generator → ksp → srcDir → generator.
        // Pre-created at configure time so IntelliJ's first sync sees it.
        val outFile = outDir.get().asFile
        outFile.mkdirs()
        attachGeneratedSrcDir(project, extensionName, srcDirGetters, outFile, platformLabel)

        project.tasks.matching { compileTaskMatcher(it.name) }
            .configureEach { it.dependsOn(gen) }
    }
}

/**
 * Reflectively attaches [outFile] as a `srcDir` to each requested getter on
 * the `main` source set of [extensionName]. Reflection is required because
 * the plugin avoids compiling against AGP. Failure is fatal — silently
 * skipping leaves the generated sources orphaned and the user sees an
 * "unresolved reference: GeneratedEntities" later, with no link to the cause.
 */
private fun attachGeneratedSrcDir(
    project: Project,
    extensionName: String,
    srcDirGetters: List<String>,
    outFile: java.io.File,
    platformLabel: String,
) {
    val ctx = "$platformLabel '$extensionName'"
    val ext = project.extensions.findByName(extensionName)
        ?: throw GradleException("Stormify: $ctx — extension not found on the project.")
    val sourceSets = ext.callGetterOrThrow("getSourceSets", ctx)
        as? org.gradle.api.NamedDomainObjectContainer<*>
        ?: throw GradleException("Stormify: $ctx.sourceSets is not a NamedDomainObjectContainer.")
    val main = sourceSets.findByName("main")
        ?: throw GradleException("Stormify: $ctx has no 'main' source set.")
    srcDirGetters.forEach { getterName ->
        val srcSet = main.callGetterOrThrow(getterName, "$ctx main")
        srcSet.callMethodOrThrow("srcDir", paramCount = 1, ctx = "$ctx main.$getterName", outFile)
    }
}
