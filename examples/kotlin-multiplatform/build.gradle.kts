plugins {
    kotlin("multiplatform") version "2.2.20"
    id("com.google.devtools.ksp") version "2.2.20-2.0.2"
    id("org.jetbrains.kotlinx.atomicfu") version "0.30.0-beta"
}

repositories {
    mavenLocal()
    mavenCentral()
}

kotlin {
    jvm {
        mainRun {
            mainClass.set("demo.MainKt")
        }
    }

    linuxX64 {
        binaries {
            executable {
                entryPoint = "demo.main"
            }
        }
    }

    mingwX64 {
        binaries {
            executable {
                entryPoint = "demo.main"
            }
        }
    }

    macosArm64 {
        binaries {
            executable {
                entryPoint = "demo.main"
            }
        }
    }

    jvmToolchain(8)

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("onl.ycode:stormify:2.0.0")
            }
        }

        val jvmMain by getting {
            dependencies {
                implementation("org.xerial:sqlite-jdbc:3.47.2.0")
            }
        }
    }
}

// Per-target annproc registration (not kspCommonMainMetadata): the generated Paths
// emit @JvmField / @get:JvmName which are @OptionalExpectation in kotlin.jvm and
// cannot be referenced from non-JVM source sets.
dependencies {
    add("kspJvm", "onl.ycode:annproc:2.0.0")
    add("kspLinuxX64", "onl.ycode:annproc:2.0.0")
    add("kspMingwX64", "onl.ycode:annproc:2.0.0")
    add("kspMacosArm64", "onl.ycode:annproc:2.0.0")
}

// Make KSP-generated sources visible to each target's main source set.
kotlin.sourceSets.named("jvmMain") {
    kotlin.srcDir("build/generated/ksp/jvm/jvmMain/kotlin")
}
kotlin.sourceSets.named("linuxX64Main") {
    kotlin.srcDir("build/generated/ksp/linuxX64/linuxX64Main/kotlin")
}
kotlin.sourceSets.named("mingwX64Main") {
    kotlin.srcDir("build/generated/ksp/mingwX64/mingwX64Main/kotlin")
}
kotlin.sourceSets.named("macosArm64Main") {
    kotlin.srcDir("build/generated/ksp/macosArm64/macosArm64Main/kotlin")
}

tasks.named("clean") {
    doLast {
        delete("build/kspCaches")
    }
}
