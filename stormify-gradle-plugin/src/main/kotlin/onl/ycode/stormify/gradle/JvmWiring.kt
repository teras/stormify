// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.stormify.gradle

import org.gradle.api.Project

internal fun wireJvm(project: Project, extension: StormifyExtension, pluginVersion: String) =
    wireStandalone(
        project = project,
        extension = extension,
        pluginVersion = pluginVersion,
        platformLabel = "JVM",
        artifactSuffix = "-jvm",
        kspTaskNames = setOf("kspKotlin"),
        extensionName = "kotlin",
        srcDirGetters = listOf("getKotlin"),
        compileTaskMatcher = { it == "compileKotlin" },
        testKspTaskNames = setOf("kspTestKotlin"),
        testCompileTaskMatcher = { it == "compileTestKotlin" },
        testSourceSetName = "test",
    )
