// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.stormify.gradle

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * The `onl.ycode.stormify` Gradle plugin.
 *
 * Applying this plugin auto-wires the Stormify annotation processor (`annproc`)
 * via KSP so consumers do not have to declare per-target KSP dependencies,
 * `srcDir` overrides or `dependsOn` task wiring themselves.
 *
 * Behaviour by Kotlin plugin variant:
 * - `kotlin("multiplatform")`: KSP runs on every leaf target. A
 *   [StormifyGenerateSources] task is registered for every production source
 *   set; [Planner] decides per source set whether to emit a plain
 *   `object Tables`, an `expect`, an `actual`, or nothing — collapsing
 *   single-tier projects to a plain object and promoting shared `actual`s up
 *   to a common intermediate when possible.
 * - `kotlin("jvm")` (plain): `ksp("onl.ycode:annproc:<ver>")` on `main`,
 *   plain `object Tables` emission.
 * - `com.android.library` / `com.android.application`: same as plain JVM
 *   for the non-KMP case (the multiplatform path takes over when both are
 *   present).
 *
 * If none of those plugins is found, the build fails with an explanatory
 * message — Stormify cannot be wired without a Kotlin plugin.
 */
class StormifyPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = project.extensions.create("stormify", StormifyExtension::class.java)

        // Plugin ships its own KSP version so users don't declare it themselves.
        project.pluginManager.apply("com.google.devtools.ksp")

        val pluginVersion = resolvePluginVersion()

        // Eager (not afterEvaluate): KSP snapshots its processor classpath very
        // early, and afterEvaluate misses that snapshot — kspKotlin tasks would
        // SKIP with an empty classpath. Multiplatform wins when present; the
        // JVM/Android wirings cover plain single-target projects only.
        project.pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
            wireKmp(project, extension, pluginVersion)
        }
        project.pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
            if (!project.isKmp()) wireJvm(project, extension, pluginVersion)
        }
        project.pluginManager.withPlugin("com.android.library") {
            if (!project.isKmp()) wireAndroid(project, extension, pluginVersion)
        }
        project.pluginManager.withPlugin("com.android.application") {
            if (!project.isKmp()) wireAndroid(project, extension, pluginVersion)
        }

        project.afterEvaluate { requireKotlinPluginApplied(project) }
    }

    private fun Project.isKmp() = plugins.hasPlugin("org.jetbrains.kotlin.multiplatform")

    private fun requireKotlinPluginApplied(project: Project) {
        val variant = when {
            project.plugins.hasPlugin("org.jetbrains.kotlin.multiplatform") -> "multiplatform"
            project.plugins.hasPlugin("com.android.library") ||
                project.plugins.hasPlugin("com.android.application") -> "android"
            project.plugins.hasPlugin("org.jetbrains.kotlin.jvm") -> "jvm"
            else -> null
        }
        if (variant == null) {
            throw GradleException(
                "Stormify plugin requires a Kotlin plugin to be applied. " +
                    "Apply one of: kotlin(\"multiplatform\"), kotlin(\"jvm\"), " +
                    "com.android.library, com.android.application."
            )
        }
    }

    /**
     * Resolves the plugin's own version. Tries the JAR manifest first (standard
     * case for a published plugin), then falls back to a baked-in resource
     * generated at build time (covers uberjar repackaging and inclusion as a
     * project dependency where the manifest is absent).
     */
    private fun resolvePluginVersion(): String =
        StormifyPlugin::class.java.`package`.implementationVersion
            ?: StormifyPlugin::class.java.getResourceAsStream("plugin-version.txt")
                ?.bufferedReader()?.use { it.readText().trim().ifEmpty { null } }
            ?: throw GradleException(
                "Stormify plugin version not found in JAR manifest or baked resource. " +
                    "This indicates a broken plugin build."
            )

    companion object {
        internal const val DEFAULT_GENERATED_PACKAGE = "onl.ycode.stormify.generated"
        internal const val DEFAULT_REGISTRAR_CLASS = "GeneratedEntities"
        internal const val DEFAULT_PATHS_CLASS = "Tables"
        internal const val DEFAULT_TEST_PATHS_CLASS = "TablesTest"
        internal const val DEFAULT_TEST_REGISTRAR_CLASS = "GeneratedTestEntities"
        internal const val SHIM_VAL = "stormifyEntities"
        internal const val TEST_SHIM_VAL = "stormifyTestEntities"
        internal const val GROUP = "onl.ycode"
        internal const val ANNPROC_ARTIFACT = "annproc"
        internal const val STORMIFY_ARTIFACT = "stormify"
    }
}
