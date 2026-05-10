pluginManagement {
    repositories {
        mavenLocal()
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
        mavenLocal()
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
