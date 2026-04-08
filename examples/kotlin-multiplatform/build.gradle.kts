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
    compilerOptions {
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }

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

    jvmToolchain(11)

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("onl.ycode:stormify:2.0.0")
                implementation("onl.ycode:kdbc:2.0.0")
                implementation("onl.ycode:logger:2.0.0")
            }
        }

        val jvmMain by getting {
            dependencies {
                implementation("org.xerial:sqlite-jdbc:3.47.2.0")
            }
        }
    }
}

// Annotation processor generates entity metadata (required for native, optional for JVM)
dependencies {
    add("kspCommonMainMetadata", "onl.ycode:annproc:2.0.0")
}

// Make KSP-generated sources visible to platform targets only
// (not commonMain, to avoid KSP seeing its own output as input)
val kspGeneratedDir = "build/generated/ksp/metadata/commonMain/kotlin"
kotlin.sourceSets.named("jvmMain") {
    kotlin.srcDir(kspGeneratedDir)
}
kotlin.sourceSets.named("linuxX64Main") {
    kotlin.srcDir(kspGeneratedDir)
}

tasks.matching { it.name == "compileKotlinJvm" || it.name == "compileKotlinLinuxX64" }.configureEach {
    dependsOn("kspCommonMainKotlinMetadata")
}

// Ensure clean invalidates KSP caches so a full rebuild regenerates entity metadata
tasks.named("clean") {
    doLast {
        delete("build/kspCaches")
    }
}
