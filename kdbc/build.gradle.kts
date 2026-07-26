import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform

plugins {
    kotlin("multiplatform")
    id("com.android.library")
    id("org.jetbrains.dokka")
    id("com.vanniktech.maven.publish")
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

// All native C outputs land under $buildDir/c/<target>/ so the standard
// gradle `clean` wipes them along with every other generated artifact.
fun nativeBuildDir(subdir: String): File =
    layout.buildDirectory.dir("c/$subdir").get().asFile

fun registerNativeKdbcBuild(
    taskName: String,
    target: String,
    subdir: String,
): TaskProvider<Exec> = tasks.register<Exec>(taskName) {
    val out = nativeBuildDir(subdir)
    workingDir = file("src/c")
    commandLine("make", "TARGET=$target", "BUILDDIR=${out.absolutePath}", "${out.absolutePath}/libkdbc.a")
    cSrcInputs.execute(this)
    outputs.file("${out.absolutePath}/libkdbc.a")
}

val buildNativeKdbc      = registerNativeKdbcBuild("buildNativeKdbc",      "native", "host")
val buildNativeKdbcMingw = registerNativeKdbcBuild("buildNativeKdbcMingw", "mingw",  "mingw")
val buildNativeKdbcArm64 = registerNativeKdbcBuild("buildNativeKdbcArm64", "arm64",  "linux-arm64")

kotlin {
    applyDefaultHierarchyTemplate()
    jvm()
    androidTarget {
        publishLibraryVariants("release")
    }
    // Native targets are registered conditionally based on the build host.
    // Linux/mingw require GCC cross-compilers; Apple targets require macOS SDK.
    // Splitting avoids triggering cinterop commonization / native builds on
    // targets whose toolchain isn't available on the current host.
    val isMac = System.getProperty("os.name").startsWith("Mac")

    fun org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget.kdbcCinterop(libDir: String) {
        compilations.getByName("main") {
            cinterops {
                val kdbc by creating {
                    defFile(project.file("src/nativeInterop/cinterop/kdbc.def"))
                    packageName = "onl.ycode.kdbc.cinterop"
                    includeDirs(project.file("src/c/include"))
                    extraOpts("-libraryPath", project.file(libDir).absolutePath)
                }
            }
        }
    }

    if (isMac) {
        macosArm64        { kdbcCinterop(nativeBuildDir("macos-arm64").absolutePath) }
        macosX64          { kdbcCinterop(nativeBuildDir("macos-x64").absolutePath) }
        iosSimulatorArm64 { kdbcCinterop(nativeBuildDir("ios-sim").absolutePath) }
        iosArm64          { kdbcCinterop(nativeBuildDir("ios").absolutePath) }
        iosX64            { kdbcCinterop(nativeBuildDir("ios-sim-x64").absolutePath) }
    } else {
        linuxX64    { kdbcCinterop(nativeBuildDir("host").absolutePath) }
        linuxArm64  { kdbcCinterop(nativeBuildDir("linux-arm64").absolutePath) }
        mingwX64    { kdbcCinterop(nativeBuildDir("mingw").absolutePath) }
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
    jvmToolchain(8)

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

        if (System.getProperty("os.name").startsWith("Linux")) {
            val linuxX64Main by getting
        }
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
tasks.matching { it.name.startsWith("cinteropKdbcLinuxArm64") }.configureEach {
    dependsOn(buildNativeKdbcArm64)
}

// Apple targets — each target gets its own architecture-specific libkdbc.a.
// Sharing a single host-built archive would bake the host arch into every klib.
if (System.getProperty("os.name").startsWith("Mac")) {
    val buildNativeKdbcMacosArm64  = registerNativeKdbcBuild("buildNativeKdbcMacosArm64",  "macos-arm64", "macos-arm64")
    val buildNativeKdbcMacosX64    = registerNativeKdbcBuild("buildNativeKdbcMacosX64",    "macos-x64",   "macos-x64")
    val buildNativeKdbcIosSim      = registerNativeKdbcBuild("buildNativeKdbcIosSim",      "ios-sim",     "ios-sim")
    val buildNativeKdbcIosSimX64   = registerNativeKdbcBuild("buildNativeKdbcIosSimX64",   "ios-sim-x64", "ios-sim-x64")
    val buildNativeKdbcIos         = registerNativeKdbcBuild("buildNativeKdbcIos",         "ios",         "ios")

    tasks.matching { it.name.startsWith("cinteropKdbcMacosArm64") }.configureEach {
        dependsOn(buildNativeKdbcMacosArm64)
    }
    tasks.matching { it.name.startsWith("cinteropKdbcMacosX64") }.configureEach {
        dependsOn(buildNativeKdbcMacosX64)
    }
    tasks.matching { it.name.startsWith("cinteropKdbcIosSimulatorArm64") }.configureEach {
        dependsOn(buildNativeKdbcIosSim)
    }
    tasks.matching { it.name.startsWith("cinteropKdbcIosX64") }.configureEach {
        dependsOn(buildNativeKdbcIosSimX64)
    }
    tasks.matching { it.name.startsWith("cinteropKdbcIosArm64") }.configureEach {
        dependsOn(buildNativeKdbcIos)
    }
}

android {
    namespace = "onl.ycode.kdbc"
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
    configure(KotlinMultiplatform(javadocJar = JavadocJar.Empty()))
    coordinates(group.toString(), "kdbc", version.toString())
    pom {
        name.set("KDBC")
        description.set(project.description)
        url.set(rootProject.extra["pomUrl"] as String)
        inceptionYear.set(rootProject.extra["pomInceptionYear"] as String)
        licenses { license { name.set(rootProject.extra["pomLicenseName"] as String); url.set(rootProject.extra["pomLicenseUrl"] as String) } }
        developers { developer { id.set(rootProject.extra["pomDeveloperId"] as String); name.set(rootProject.extra["pomDeveloperName"] as String); email.set(rootProject.extra["pomDeveloperEmail"] as String) } }
        scm { url.set(rootProject.extra["pomScmUrl"] as String); connection.set(rootProject.extra["pomScmConnection"] as String); developerConnection.set(rootProject.extra["pomScmDevConnection"] as String) }
    }
}
