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
include(":schema-sync")
include(":conformance")
include(":annproc-jpa")
include(":annproc-large")

project(":kdbc").projectDir = file("kdbc")
project(":conformance").projectDir = file("tests/conformance")
project(":annproc-jpa").projectDir = file("tests/annproc-jpa")
project(":annproc-large").projectDir = file("tests/annproc-large")