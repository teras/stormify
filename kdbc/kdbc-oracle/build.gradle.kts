plugins {
    id("maven-publish")
    kotlin("multiplatform")
}

group = parent?.group ?: IllegalStateException("Group is not defined")
version = parent?.version ?: IllegalStateException("Version is not defined")
description = "Oracle Database driver for KDBC (Native)"

kotlin {
    linuxX64 {
        compilations.getByName("main") {
            cinterops {
                val odpi by creating {
                    definitionFile.set(project.file("src/nativeInterop/cinterop/odpi.def"))
                }
            }
        }
    }
    jvmToolchain(11)

    sourceSets {
        val linuxX64Main by getting {
            dependencies {
                implementation(project(":kdbc"))
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
                implementation("com.ionspin.kotlin:bignum:0.3.10")
            }
        }
    }
}

publishing {
    repositories {
        mavenLocal()
    }
}
