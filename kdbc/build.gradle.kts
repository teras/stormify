plugins {
    id("maven-publish")
    kotlin("multiplatform")
    id("com.android.library")
    id("org.jetbrains.dokka")
}

group = parent?.group ?: IllegalStateException("Group is not defined")
version = parent?.version ?: IllegalStateException("Version is not defined")
description = "Kotlin Database Connectivity API"

// ---------------------------------------------------------------------------
// Native C library build tasks
//
// Each target produces libkdbc.a in its own BUILDDIR so multiple targets
// can coexist. The Makefile TARGET variable selects the cross-compiler.
// ---------------------------------------------------------------------------

val cSrcInputs: Action<Task> = Action {
    inputs.dir("src/c/drivers")
    inputs.dir("src/c/include")
    inputs.file("src/c/kdbc_core.c")
    inputs.file("src/c/Makefile")
}

val buildNativeKdbc = tasks.register<Exec>("buildNativeKdbc") {
    workingDir = file("src/c")
    commandLine("make", "lib")
    cSrcInputs.execute(this)
    outputs.file("src/c/libkdbc.a")
    outputs.file("src/c/libkdbc.so")
}

val buildNativeKdbcMingw = tasks.register<Exec>("buildNativeKdbcMingw") {
    workingDir = file("src/c")
    commandLine("make", "TARGET=mingw", "BUILDDIR=build-mingw", "lib")
    cSrcInputs.execute(this)
    outputs.file("src/c/build-mingw/libkdbc.a")
    outputs.file("src/c/build-mingw/libkdbc.dll")
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
    mingwX64 {
        compilations.getByName("main") {
            cinterops {
                val kdbc by creating {
                    defFile(project.file("src/nativeInterop/cinterop/kdbc.def"))
                    packageName = "onl.ycode.kdbc.cinterop"
                    includeDirs(project.file("src/c/include"))
                    extraOpts("-libraryPath", project.file("src/c/build-mingw").absolutePath)
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

    // Target Java 8 bytecode for the JVM artifact (matches stormify's Java 8 floor).
    jvm {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
                }
            }
        }
    }
    jvmToolchain(11)

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":logger"))
                compileOnly("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
                compileOnly("com.ionspin.kotlin:bignum:0.3.9")
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        // Common JVM-based source set for both Desktop JVM and Android
        val jvmBasedMain by creating {
            dependsOn(commonMain)
            dependencies {
                compileOnly("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
                compileOnly("com.ionspin.kotlin:bignum:0.3.9")
            }
        }

        val jvmMain by getting {
            dependsOn(jvmBasedMain)
        }

        val androidMain by getting {
            dependsOn(jvmBasedMain)
        }

        val nativeMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
                implementation("com.ionspin.kotlin:bignum:0.3.9")
            }
        }

        val linuxX64Main by getting
    }
}

// Align Java compile tasks with the Kotlin JVM 1.8 target.
tasks.withType<JavaCompile>().configureEach {
    sourceCompatibility = "1.8"
    targetCompatibility = "1.8"
}

// Ensure the correct libkdbc.a is built before each target's cinterop task.
tasks.matching { it.name.startsWith("cinteropKdbcLinuxX64") }.configureEach {
    dependsOn(buildNativeKdbc)
}
tasks.matching { it.name.startsWith("cinteropKdbcMingwX64") }.configureEach {
    dependsOn(buildNativeKdbcMingw)
}

android {
    namespace = "onl.ycode.kdbc"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

publishing {
    repositories {
        mavenLocal()
    }
}
