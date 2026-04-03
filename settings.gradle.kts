pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "stormify"

include(":stormify")
include(":logger")
include(":annproc")
include(":kdbc")

project(":kdbc").projectDir = file("kdbc")