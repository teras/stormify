pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        kotlin("jvm") version "2.2.21"
        id("com.google.devtools.ksp") version "2.2.21-2.0.5"
        id("com.vanniktech.maven.publish") version "0.36.0"
        id("org.jetbrains.dokka") version "2.1.0"
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}
rootProject.name = "stormify"

include("annproc")
include("biglist")
include("browse")
include("db")
include("kotlin")
include("logger")
include("tokenizer")
