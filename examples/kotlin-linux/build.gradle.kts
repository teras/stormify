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

    linuxX64 {
        binaries {
            executable {
                entryPoint = "demo.main"
            }
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("onl.ycode:stormify:2.0.0")
            }
        }
    }
}

// Annotation processor generates entity metadata (required on native)
dependencies {
    add("kspLinuxX64", "onl.ycode:annproc:2.0.0")
}

tasks.named("clean") {
    doLast {
        delete("build/kspCaches")
    }
}
