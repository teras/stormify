// Scenario 2: entities only in jvmBasedMain (custom intermediate).
// Expected (with optimization): plain `object Tables` in jvmBasedMain;
// jvm + android inherit it; linux has no Tables.

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

    sourceSets {
        val commonMain by getting
        val jvmBasedMain by creating { dependsOn(commonMain) }
        val jvmMain by getting { dependsOn(jvmBasedMain) }
        val androidMain by getting { dependsOn(jvmBasedMain) }
    }
}

android {
    namespace = "demo.scenario2"
    compileSdk = 34
    defaultConfig { minSdk = 21 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
