// Scenario 3: entities only in jvmMain leaf.
// Expected (with optimization): plain `object Tables` in jvmMain.
// android + linux: no Tables.

plugins {
    kotlin("multiplatform")
    id("com.android.library")
    id("onl.ycode.stormify")
}

kotlin {
    jvm()
    androidTarget()
    linuxX64()
    jvmToolchain(17)
}

android {
    namespace = "demo.scenario3"
    compileSdk = 34
    defaultConfig { minSdk = 21 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
