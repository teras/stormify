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
include(":stormify-gradle-plugin")

project(":kdbc").projectDir = file("kdbc")