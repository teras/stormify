plugins {
    id("maven-publish")
    kotlin("multiplatform")
    id("org.jetbrains.kotlinx.atomicfu") version "0.30.0-beta"
}

group = parent?.group ?: IllegalStateException("Group is not defined")
version = parent?.version ?: IllegalStateException("Version is not defined")
description = "Stormify Database Library"

kotlin {
    jvm()
    linuxX64 {
//        binaries {
//            executable {
//                entryPoint = "onl.ycode.demo.main"
//            }
//        }
    }
    jvmToolchain(11)

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":logger"))
            }
        }
        val commonTest by getting {
            dependencies {
//                implementation(kotlin("test"))
//                implementation(kotlin("test-junit"))
            }
        }

        val jvmMain by getting {
            dependencies {
                compileOnly("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
                compileOnly("com.ionspin.kotlin:bignum:0.3.10")
            }
        }

        val nativeMain by creating {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
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