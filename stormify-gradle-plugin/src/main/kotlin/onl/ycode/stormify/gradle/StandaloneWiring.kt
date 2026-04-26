// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.stormify.gradle

import org.gradle.api.GradleException
import org.gradle.api.Project

private val STANDALONE_TEST_KSP_CONFIGS = listOf("kspTest", "kspTestDebug", "kspTestRelease")

/**
 * Shared wiring for single-source-set projects (plain `kotlin("jvm")` and
 * Android). annproc writes JSONs to a per-project intermediates dir, the
 * generator reads them and emits a flat set of Kotlin sources (no
 * `expect`/`actual`) into `build/generated/stormify/main/kotlin`, which is
 * registered as a `srcDir` of the `main` source set.
 *
 * If [testKspTaskNames] is non-empty, the same wiring is duplicated for the
 * test source set: KSP runs on test sources, a separate generator emits
 * `TablesTest` / `GeneratedTestEntities` (configurable via the extension),
 * and the result is attached to the test source set so that test entities
 * declared in `src/test/...` produce a parallel Tables holder without
 * colliding with the production `Tables` on the test classpath.
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
 * @param testKspTaskNames KSP task names processing test sources. Empty to
 *   skip test wiring entirely.
 * @param testCompileTaskMatcher picks the test compile tasks that must
 *   depend on the test generator. Ignored when [testKspTaskNames] is empty.
 * @param testSourceSetName name of the test source set on the platform's
 *   container (`"test"` for both plain JVM and Android unit tests).
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
    testKspTaskNames: Set<String> = emptySet(),
    testCompileTaskMatcher: (String) -> Boolean = { false },
    testSourceSetName: String = "test",
) {
    val selfHosted = extension.selfHosted.getOrElse(false)
    val annprocCoord = "${StormifyPlugin.GROUP}:${StormifyPlugin.ANNPROC_ARTIFACT}:$pluginVersion"
    if (!selfHosted) {
        project.dependencies.add("ksp", annprocCoord)
        project.dependencies.add(
            "implementation",
            "${StormifyPlugin.GROUP}:${StormifyPlugin.STORMIFY_ARTIFACT}$artifactSuffix:$pluginVersion"
        )
    }

    val metaDir = project.layout.buildDirectory
        .dir("intermediates/stormify-meta/main").get().asFile

    // Declare metaDir as a KSP task output so Gradle re-runs KSP after `clean`
    // wipes the dir — annproc writes JSONs there via plain Java I/O, which
    // Gradle's up-to-date check would otherwise miss.
    wireKspMetaDir(project, kspTaskNames, metaDir.absolutePath)

    val testEnabled = testKspTaskNames.isNotEmpty()
    val testMetaDir = if (!testEnabled) null else project.layout.buildDirectory
        .dir("intermediates/stormify-meta/test").get().asFile

    if (testEnabled) {
        // Test KSP needs annproc on its own configuration; AGP/JVM expose this
        // as `kspTest` for plain JVM and `kspTestDebug`/`kspTestRelease` for
        // Android unit tests. We add to whichever exists.
        if (!selfHosted) STANDALONE_TEST_KSP_CONFIGS.forEach { conf ->
            project.configurations.findByName(conf)?.let { project.dependencies.add(conf, annprocCoord) }
        }
        wireKspMetaDir(project, testKspTaskNames, testMetaDir!!.absolutePath)
    }

    project.afterEvaluate {
        val pkg = extension.generatedPackage.getOrElse(StormifyPlugin.DEFAULT_GENERATED_PACKAGE)
        val genRegistrar = extension.generateRegistrar.getOrElse(true)

        registerStandaloneScope(
            project = project,
            scopeName = "main",
            metaDir = metaDir,
            kspTaskNames = kspTaskNames,
            extensionName = extensionName,
            sourceSetName = "main",
            srcDirGetters = srcDirGetters,
            compileTaskMatcher = compileTaskMatcher,
            platformLabel = platformLabel,
            pkg = pkg,
            pathsCls = extension.pathsClass.getOrElse(StormifyPlugin.DEFAULT_PATHS_CLASS),
            registrarCls = extension.registrarClass.getOrElse(StormifyPlugin.DEFAULT_REGISTRAR_CLASS),
            shimVal = StormifyPlugin.SHIM_VAL,
            generateRegistrar = genRegistrar,
        )

        if (!testEnabled) return@afterEvaluate

        registerStandaloneScope(
            project = project,
            scopeName = "test",
            metaDir = testMetaDir!!,
            kspTaskNames = testKspTaskNames,
            extensionName = extensionName,
            sourceSetName = testSourceSetName,
            srcDirGetters = srcDirGetters,
            compileTaskMatcher = testCompileTaskMatcher,
            platformLabel = "$platformLabel test",
            pkg = pkg,
            pathsCls = extension.testPathsClass.getOrElse(StormifyPlugin.DEFAULT_TEST_PATHS_CLASS),
            registrarCls = extension.testRegistrarClass.getOrElse(StormifyPlugin.DEFAULT_TEST_REGISTRAR_CLASS),
            shimVal = StormifyPlugin.TEST_SHIM_VAL,
            generateRegistrar = genRegistrar,
        )
    }
}

/**
 * Register one standalone (non-KMP) generator task and chain it: KSP feeds
 * metadata into [metaDir], the generator emits Kotlin sources into a per-
 * scope build dir, the dir is attached to the source set named
 * [sourceSetName], and matching compile tasks gain a `dependsOn` on the
 * generator. Used twice from `wireStandalone` — once for `main`, once for
 * `test` when test wiring is enabled.
 */
private fun registerStandaloneScope(
    project: Project,
    scopeName: String,
    metaDir: java.io.File,
    kspTaskNames: Set<String>,
    extensionName: String,
    sourceSetName: String,
    srcDirGetters: List<String>,
    compileTaskMatcher: (String) -> Boolean,
    platformLabel: String,
    pkg: String,
    pathsCls: String,
    registrarCls: String,
    shimVal: String,
    generateRegistrar: Boolean,
) {
    val outDir = project.layout.buildDirectory.dir("generated/stormify/$scopeName/kotlin")
    // Keep the historical "stormifyGenerate" task name for main; suffix only on test.
    val taskName = if (scopeName == "main") "stormifyGenerate" else "stormifyGenerate${scopeName.cap}"
    val gen = project.tasks.register(taskName, StormifyGenerateSources::class.java) { t ->
        t.metadataDirs.from(metaDir)
        t.outputDir.set(outDir)
        t.sourceSetName.set(scopeName)
        t.generatedPackage.set(pkg)
        t.pathsClass.set(pathsCls)
        t.registrarClass.set(registrarCls)
        t.shimVal.set(shimVal)
        t.generateRegistrar.set(generateRegistrar)
        t.kmpProject.set(false)
        t.sourceSetParents.set(emptyMap<String, List<String>>())
        t.leafSourceSets.set(emptySet<String>())
        t.jvmFlavoredSourceSets.set(setOf(scopeName))
        t.rootSourceSetName.set(scopeName)
        t.dependsOn(project.tasks.matching { it.name in kspTaskNames })
    }

    val outFile = outDir.get().asFile
    outFile.mkdirs()
    attachGeneratedSrcDir(project, extensionName, sourceSetName, srcDirGetters, outFile, platformLabel)

    project.tasks.matching { compileTaskMatcher(it.name) }
        .configureEach { it.dependsOn(gen) }
}

/**
 * Reflectively attaches [outFile] as a `srcDir` to each requested getter on
 * the [sourceSetName] source set of [extensionName]. Reflection is required
 * because the plugin avoids compiling against AGP. Failure is fatal —
 * silently skipping leaves the generated sources orphaned and the user sees
 * an "unresolved reference: GeneratedEntities" later, with no link to the
 * cause.
 */
private fun attachGeneratedSrcDir(
    project: Project,
    extensionName: String,
    sourceSetName: String,
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
    val ss = sourceSets.findByName(sourceSetName)
        ?: throw GradleException("Stormify: $ctx has no '$sourceSetName' source set.")
    srcDirGetters.forEach { getterName ->
        val srcSet = ss.callGetterOrThrow(getterName, "$ctx $sourceSetName")
        srcSet.callMethodOrThrow("srcDir", paramCount = 1, ctx = "$ctx $sourceSetName.$getterName", outFile)
    }
}
