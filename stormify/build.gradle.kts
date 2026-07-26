import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform

plugins {
    kotlin("multiplatform")
    id("com.android.library")
    id("org.jetbrains.kotlinx.atomicfu") version "0.32.1"
    id("com.google.devtools.ksp") version "2.2.21-2.0.5"
    id("org.jetbrains.dokka")
    id("com.vanniktech.maven.publish")
}

group = parent?.group ?: IllegalStateException("Group is not defined")
version = parent?.version ?: IllegalStateException("Version is not defined")
description = "Stormify Database Library"

kotlin {
    applyDefaultHierarchyTemplate()
    jvm()
    androidTarget {
        publishLibraryVariants("release")
    }
    // Host-gated target declarations. Kotlin/Native itself cross-compiles
    // between hosts via bundled LLVM, but the kdbc C library needs host
    // system cross-compilers (mingw-w64, gcc-aarch64-linux-gnu). Rather than
    // install those on macOS runners, we split: Linux hosts declare
    // linux/mingw targets; macOS hosts declare apple targets. The two sets
    // are union-merged into a single root KMP module at publish time.
    val isMac = System.getProperty("os.name").startsWith("Mac")
    if (isMac) {
        macosArm64()
        macosX64()
        iosSimulatorArm64()
        iosArm64()
        iosX64()
    } else {
        linuxX64()
        linuxArm64()
        mingwX64()
    }
    
    targets.all {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    freeCompilerArgs.add("-Xannotation-default-target=param-property")
                    freeCompilerArgs.add("-Xexpect-actual-classes")
                }
            }
        }
    }
    // Target Java 8 bytecode for the published JVM artifact so consumers on older
    // JDKs can still load the library. All runtime deps (bignum 0.3.9, kotlinx-datetime
    // 0.7.1, kotlinx-coroutines 1.10.2, HikariCP 4.0.3) ship Java 8 bytecode.
    // Tests run on the toolchain JDK (11) which can load Java 8 class files fine.
    jvm {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
                }
            }
        }
    }
    jvmToolchain(8)

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":logger"))
                api(project(":kdbc"))
                // kotlinx.coroutines is used only by the optional suspend API in the
                // `onl.ycode.stormify.coroutines` subpackage. Marked compileOnly so
                // that consumers who only use the blocking API never pull it as a
                // transitive dependency. Consumers who use the suspend API must add
                // kotlinx.coroutines-core themselves to their build.
                compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                // ScalarTypesTest references kotlinx.datetime types when verifying type buckets.
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
            }
        }

        // Common JVM-based source set for both Desktop JVM and Android
        val jvmBasedMain by creating {
            dependsOn(commonMain)
            dependencies {
                compileOnly("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
                compileOnly("com.ionspin.kotlin:bignum:0.3.9")
                // kotlin-reflect powers the reflection-based entity discovery path
                // (tryReflection). Consumers using only annproc can exclude it.
                implementation(kotlin("reflect"))
            }
        }

        val jvmMain by getting {
            dependsOn(jvmBasedMain)
        }

        val androidMain by getting {
            dependsOn(jvmBasedMain)
            // atomicfu Gradle plugin transforms the JVM jar but not the AAR;
            // Android consumers need the runtime jar on classpath.
            dependencies {
                api("org.jetbrains.kotlinx:atomicfu:0.32.1")
            }
        }

        val nativeMain by getting {
            dependencies {
                api("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
                api("com.ionspin.kotlin:bignum:0.3.9")
                // Kotlin/Native does not support compileOnly dependencies — the klib
                // compilation pipeline requires every referenced symbol to be present.
                // commonMain keeps `compileOnly` so JVM/Android consumers who only use
                // the blocking API don't pull coroutines; native consumers get it via api.
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            }
        }

        // Apple targets - build enabled on macOS only
        if (System.getProperty("os.name").startsWith("Mac")) {
            val appleMain by getting {
                dependencies {
                    implementation(project(":kdbc"))
                }
            }
        }
    }
}

// Align Java compile tasks with the Kotlin JVM 1.8 target.
tasks.withType<JavaCompile>().configureEach {
    sourceCompatibility = "1.8"
    targetCompatibility = "1.8"
}

android {
    namespace = "onl.ycode.stormify"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    // Gradle 9 forks unit-test workers with `--add-opens=java.base/java.io=ALL-UNNAMED`,
    // a Java 9+ flag rejected by the Java 8 toolchain JVM. Override the test launcher
    // so the Android unit-test task runs on Java 17; compilation (and the published
    // bytecode) remains at Java 8.
    testOptions {
        unitTests.all {
            it.javaLauncher.set(javaToolchains.launcherFor {
                languageVersion.set(JavaLanguageVersion.of(11))
            })
        }
    }
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    // Empty javadoc jar — Dokka would require compiling ALL target klibs (including
    // linuxArm64) even when publishing only apple targets, which fails on macOS
    // runners where aarch64-linux-gnu-gcc is unavailable. KDoc lives in the
    // sources jar anyway; Maven Central only requires that *some* javadoc jar exists.
    configure(KotlinMultiplatform(javadocJar = JavadocJar.Empty()))
    coordinates(group.toString(), "stormify", version.toString())
    pom {
        name.set("Stormify")
        description.set(project.description)
        url.set(rootProject.extra["pomUrl"] as String)
        inceptionYear.set(rootProject.extra["pomInceptionYear"] as String)
        licenses { license { name.set(rootProject.extra["pomLicenseName"] as String); url.set(rootProject.extra["pomLicenseUrl"] as String) } }
        developers { developer { id.set(rootProject.extra["pomDeveloperId"] as String); name.set(rootProject.extra["pomDeveloperName"] as String); email.set(rootProject.extra["pomDeveloperEmail"] as String) } }
        scm { url.set(rootProject.extra["pomScmUrl"] as String); connection.set(rootProject.extra["pomScmConnection"] as String); developerConnection.set(rootProject.extra["pomScmDevConnection"] as String) }
    }
}

tasks.withType<Test> {
    val testDb = System.getProperty("stormify.test.db") ?: "sqlite"
    systemProperty("stormify.test.db", testDb)
    systemProperty("stormify.test.config", System.getProperty("stormify.test.config") ?: "")
}

// No Android-specific tests live in this module — the Android conformance suite
// is in :conformance. AGP still creates testDebugUnitTest / testReleaseUnitTest
// tasks for the android target; disable them so `gradle :stormify:build` doesn't
// try to launch an empty test runner.
tasks.matching { it.name == "testDebugUnitTest" || it.name == "testReleaseUnitTest" }.configureEach {
    enabled = false
}

