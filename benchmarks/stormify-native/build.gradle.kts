plugins {
    kotlin("multiplatform")
    id("onl.ycode.stormify")
}

kotlin {
    linuxX64 {
        binaries {
            executable {
                entryPoint = "bench.main"
                baseName = "stormify-native-bench"
            }
        }
    }
    sourceSets {
        getByName("commonMain") {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            }
        }
    }
}
