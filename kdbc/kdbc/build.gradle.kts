plugins {
    id("maven-publish")
    kotlin("multiplatform")
}

group = parent?.group ?: IllegalStateException("Group is not defined")
version = parent?.version ?: IllegalStateException("Version is not defined")
description = "Kotlin Database Connectivity API"

kotlin {
    jvm()
    linuxX64()
    jvmToolchain(11)

    sourceSets {
        val commonMain by getting

        val nativeMain by creating {
            dependsOn(commonMain)
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            }
        }

        val linuxX64Main by getting {
            dependsOn(nativeMain)
        }
    }
}

publishing {
    repositories {
        mavenLocal()
    }
}
