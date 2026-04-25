// Scenario 9: jvmBased intermediate + linuxX64 leaf, no common entities (disjoint).
// Expected: plain `object Tables` at jvmBasedMain (EJ; covers jvm + android) and
// a separate plain `object Tables` at linuxX64Main (EL). No `expect` anywhere —
// the two anchor sub-trees share no visible entity.

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
    namespace = "demo.scenario9"
    compileSdk = 34
    defaultConfig { minSdk = 21 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
