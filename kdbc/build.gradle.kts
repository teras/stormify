import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    id("maven-publish")
    kotlin("multiplatform")
    id("com.android.library")
}

group = parent?.group ?: IllegalStateException("Group is not defined")
version = parent?.version ?: IllegalStateException("Version is not defined")
description = "Kotlin Database Connectivity API"

// Task that builds libkdbc.a / libkdbc.so from the C sources in src/c.
// This is a prerequisite for the linuxX64 cinterop step.
val buildNativeKdbc = tasks.register<Exec>("buildNativeKdbc") {
    workingDir = file("src/c")
    commandLine("make")
    inputs.dir("src/c/drivers")
    inputs.dir("src/c/include")
    inputs.file("src/c/kdbc_core.c")
    inputs.file("src/c/Makefile")
    outputs.file("src/c/libkdbc.a")
    outputs.file("src/c/libkdbc.so")
}

val cleanNativeKdbc = tasks.register<Exec>("cleanNativeKdbc") {
    workingDir = file("src/c")
    commandLine("make", "clean")
}

tasks.named("clean") {
    dependsOn(cleanNativeKdbc)
}

kotlin {
    applyDefaultHierarchyTemplate()
    jvm()
    androidTarget {
        publishLibraryVariants("release", "debug")
    }
    linuxX64 {
        compilations.getByName("main") {
            cinterops {
                val kdbc by creating {
                    defFile(project.file("src/nativeInterop/cinterop/kdbc.def"))
                    packageName = "onl.ycode.kdbc.cinterop"
                    includeDirs(project.file("src/c/include"))
                    extraOpts("-libraryPath", project.file("src/c").absolutePath)
                }
            }
        }
    }

    // Apple targets - build enabled on macOS only
    if (org.gradle.internal.os.OperatingSystem.current().isMacOsX) {
        // iOS
        iosArm64()
        iosX64()
        iosSimulatorArm64()

        // macOS
        macosArm64()
        macosX64()
    }

    jvmToolchain(11)

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":logger"))
            }
        }

        val nativeMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
                implementation("com.ionspin.kotlin:bignum:0.3.10")
            }
        }

        val linuxX64Main by getting
    }
}

// Ensure libkdbc.a is built before any linuxX64 cinterop task runs.
tasks.matching { it.name.startsWith("cinteropKdbcLinuxX64") }.configureEach {
    dependsOn(buildNativeKdbc)
}

android {
    namespace = "onl.ycode.kdbc"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

publishing {
    repositories {
        mavenLocal()
    }
}
