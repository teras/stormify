pluginManagement {
    repositories {
        // The locally-published stormify plugin and its runtime artifacts must win over
        // Central, otherwise a released build of the same version number shadows the one
        // under test. The content filter keeps everything else remote, so stale local
        // artifacts without Gradle module metadata (e.g. kotlin-test) cannot break
        // capability resolution.
        mavenLocal { content { includeGroupByRegex("onl\\.ycode(\\..*)?") } }
        gradlePluginPortal()
        google()
        mavenCentral()
    }
    plugins {
        kotlin("multiplatform") version "2.2.21"
        id("com.android.library") version "8.7.3"
        id("onl.ycode.stormify") version "2.6.0"
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal { content { includeGroupByRegex("onl\\.ycode(\\..*)?") } }
        google()
        mavenCentral()
    }
}

rootProject.name = "stormify-scenarios"

include(
    ":scenario1",
    ":scenario2",
    ":scenario3",
    ":scenario4",
    ":scenario5",
    ":scenario6",
    ":scenario7",
    ":scenario8",
    ":scenario9",
    ":scenario10",
)
