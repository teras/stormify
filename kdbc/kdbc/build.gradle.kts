plugins {
    id("maven-publish")
    kotlin("multiplatform")
}

group = parent?.group ?: IllegalStateException("Group is not defined")
version = parent?.version ?: IllegalStateException("Version is not defined")
description = "Kotlin Database Connectivity API for Native"

kotlin {
    linuxX64()
    jvmToolchain(11)
}

publishing {
    repositories {
        mavenLocal()
    }
}
