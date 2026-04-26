// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.stormify.gradle

import org.gradle.api.Project

internal fun wireAndroid(project: Project, extension: StormifyExtension, pluginVersion: String) =
    wireStandalone(
        project = project,
        extension = extension,
        pluginVersion = pluginVersion,
        platformLabel = "Android",
        artifactSuffix = "-android",
        // Production variants only — chaining through test variants would cycle.
        kspTaskNames = setOf("kspDebugKotlin", "kspReleaseKotlin"),
        extensionName = "android",
        // Android non-KMP projects sometimes consume .kt through java srcDirs.
        srcDirGetters = listOf("getKotlin", "getJava"),
        compileTaskMatcher = { it.startsWith("compile") && it.contains("Kotlin") },
        // Android unit-test variants. Instrumented (`androidTest`) is left
        // alone — it ships in a separate APK and rarely needs annproc.
        testKspTaskNames = setOf("kspDebugUnitTestKotlin", "kspReleaseUnitTestKotlin"),
        testCompileTaskMatcher = { it.startsWith("compile") && it.contains("UnitTestKotlin") },
        testSourceSetName = "test",
    )
