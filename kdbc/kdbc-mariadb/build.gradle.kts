plugins {
    id("maven-publish")
    kotlin("multiplatform")
}

group = parent?.group ?: IllegalStateException("Group is not defined")
version = parent?.version ?: IllegalStateException("Version is not defined")
description = "MariaDB/MySQL Driver for Kotlin Database Connectivity"

kotlin {
    linuxX64 {
        compilations.getByName("main") {
            cinterops {
                val mariadb by creating {
                    defFile(project.file("src/nativeInterop/cinterop/mariadb.def"))
                }
            }
        }
    }
    jvmToolchain(11)

    sourceSets {
        commonMain.dependencies {
            implementation(project(":kdbc"))
        }
    }
}

publishing {
    repositories {
        mavenLocal()
    }
}
