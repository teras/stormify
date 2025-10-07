plugins {
    id("maven-publish")
    kotlin("multiplatform")
}

group = parent?.group ?: IllegalStateException("Group is not defined")
version = parent?.version ?: IllegalStateException("Version is not defined")
description = "PostgreSQL Driver for Kotlin Database Connectivity"

kotlin {
    linuxX64 {
        compilations.getByName("main") {
            cinterops {
                val libpq by creating {
                    defFile(project.file("src/nativeInterop/cinterop/libpq.def"))
                }
            }
        }
    }
    jvmToolchain(11)

    sourceSets {
        commonMain.dependencies {
            implementation(project(":kdbc"))
        }

        linuxX64Main.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
            implementation("com.ionspin.kotlin:bignum:0.3.10")
        }
    }
}

publishing {
    repositories {
        mavenLocal()
    }
}
