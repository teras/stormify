// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.stormify.gradle

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
        "${StormifyPlugin.ANNPROC_GROUP}:${StormifyPlugin.ANNPROC_ARTIFACT}:$pluginVersion"
    )
    project.dependencies.add(
        "implementation",
        "${StormifyPlugin.STORMIFY_GROUP}:${StormifyPlugin.STORMIFY_ARTIFACT}$artifactSuffix:$pluginVersion"
    )

    val metaDir = project.layout.buildDirectory
        .dir("intermediates/stormify-meta/main").get().asFile

    project.tasks.matching { it.name in kspTaskNames }.configureEach { task ->
        setKspProcessorOption(task, "stormify.metaOutputDir", metaDir.absolutePath)
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
            t.targetKind.set(TargetKind.STANDALONE_JVM)
            t.sourceSetName.set("main")
            t.generatedPackage.set(pkg)
            t.pathsClass.set(pathsCls)
            t.registrarClass.set(registrarCls)
            t.shimVal.set("stormifyEntities")
            t.generateRegistrar.set(genRegistrar)
            t.dependsOn(project.tasks.matching { it.name in kspTaskNames })
        }

        // Plain File srcDir avoids Gradle auto-dependsOn, which would cycle
        // compile → generator → ksp → srcDir → generator.
        // Pre-created at configure time so IntelliJ's first sync sees it.
        val outFile = outDir.get().asFile
        outFile.mkdirs()
        val ext = project.extensions.findByName(extensionName)
        if (ext != null) {
            try {
                val getSourceSets = ext.javaClass.methods.firstOrNull { it.name == "getSourceSets" }
                val sourceSets = getSourceSets?.invoke(ext) as?
                    org.gradle.api.NamedDomainObjectContainer<*>
                val main = sourceSets?.findByName("main")
                if (main != null) {
                    srcDirGetters.forEach { getterName ->
                        val getter = main.javaClass.methods.firstOrNull { it.name == getterName }
                        val srcSet = getter?.invoke(main)
                        val srcDirMethod = srcSet?.javaClass?.methods?.firstOrNull {
                            it.name == "srcDir" && it.parameterCount == 1
                        }
                        srcDirMethod?.invoke(srcSet, outFile)
                    }
                }
            } catch (e: Throwable) {
                project.logger.error("Stormify: $platformLabel setup failed — generated sources not visible to compile. (${e.message})")
            }
        }

        project.tasks.matching { compileTaskMatcher(it.name) }
            .configureEach { it.dependsOn(gen) }
    }
}
