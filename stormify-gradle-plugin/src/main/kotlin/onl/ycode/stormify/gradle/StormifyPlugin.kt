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
 * via KSP so that consumers do not have to declare per-target KSP dependencies,
 * `srcDir` overrides or `dependsOn` task wiring themselves.
 *
 * Behaviour by Kotlin plugin variant:
 * - `kotlin("multiplatform")`: a single canonical target is selected (`jvm` if
 *   present, otherwise the first native target, otherwise the first target).
 *   KSP is wired only on that target; its generated source folder is added to
 *   `commonMain`, and every other target's compile task `dependsOn` the
 *   canonical KSP task. This makes generated symbols (`GeneratedEntities`,
 *   `Tables`) visible from `commonMain` without `expect`/`actual` shims.
 * - `kotlin("jvm")` (plain): `ksp("onl.ycode:annproc:<ver>")` on `main`.
 * - `com.android.library` / `com.android.application`: `ksp("onl.ycode:annproc:<ver>")`
 *   on `main`.
 *
 * If none of those plugins is found, the build fails with an explanatory
 * message — Stormify cannot be wired without a Kotlin plugin.
 */
class StormifyPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = project.extensions.create("stormify", StormifyExtension::class.java)

        // Apply KSP unconditionally. The plugin ships its own KSP gradle plugin
        // version so users do not need to declare it themselves.
        project.pluginManager.apply("com.google.devtools.ksp")

        val pluginVersion = resolvePluginVersion()

        // Eagerly hook into each Kotlin variant. Why eager and not afterEvaluate:
        // KSP snapshots its `kspKotlinProcessorClasspath` (or per-target equivalents)
        // early enough that adding deps inside afterEvaluate misses the snapshot,
        // leaving the processor classpath empty and the kspKotlin task SKIPPED.
        project.pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
            wireJvm(project, extension, pluginVersion)
        }
        project.pluginManager.withPlugin("com.android.library") {
            wireAndroid(project, extension, pluginVersion)
        }
        project.pluginManager.withPlugin("com.android.application") {
            wireAndroid(project, extension, pluginVersion)
        }
        project.pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
            wireKmp(project, extension, pluginVersion)
        }

        // Late-bound checks and KSP option forwarding. Both can wait for afterEvaluate
        // since they do not affect dep resolution.
        project.afterEvaluate {
            requireKotlinPluginApplied(project)
        }
    }

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
        internal const val ANNPROC_GROUP = "onl.ycode"
        internal const val ANNPROC_ARTIFACT = "annproc"
        internal const val STORMIFY_GROUP = "onl.ycode"
        internal const val STORMIFY_ARTIFACT = "stormify"
    }
}
