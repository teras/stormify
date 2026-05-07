// Scenario 10: entities in commonMain (production) AND in commonTest (test-only).
// Expected: production `object Tables` in commonMain (with EC), and a separate
// `object TablesTest` (default test paths class) in commonTest (with ECTest).
// Test source sets see both: production Tables via inheritance from main,
// and TablesTest from the test gen.

plugins {
    kotlin("multiplatform")
    id("com.android.library")
    id("onl.ycode.stormify")
}

kotlin {
    jvm()
    androidTarget()
    linuxX64()
    jvmToolchain(11)

    sourceSets {
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

android {
    namespace = "demo.scenario10"
    compileSdk = 34
    defaultConfig { minSdk = 21 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
