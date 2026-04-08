plugins {
    kotlin("multiplatform") version "2.2.20"
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
                implementation("onl.ycode:kdbc:2.0.0")
                implementation("onl.ycode:logger:2.0.0")
            }
        }
    }
}
