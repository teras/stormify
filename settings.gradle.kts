pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        kotlin("jvm") version "2.0.0"
        id("com.google.devtools.ksp") version "2.0.0-1.0.23"
        id("com.vanniktech.maven.publish") version "0.30.0"
        id("org.jetbrains.dokka") version "1.9.20"
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}
rootProject.name = "stormify"

include("annproc")
include("biglist")
include("browse")
include("db")
include("kotlin")
include("logger")
include("tokenizer")
