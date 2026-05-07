// Scenario 4: entities only in linuxX64Main and androidMain (sibling leafs, no common).
// Expected (with optimization): plain `object Tables` independently at linuxX64Main and androidMain
// (disjoint chains, no shared visible entity → no expect needed).
// jvm: no Tables.

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
}

android {
    namespace = "demo.scenario4"
    compileSdk = 34
    defaultConfig { minSdk = 21 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
