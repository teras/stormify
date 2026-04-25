// Scenario 7: entities in commonMain + jvmBasedMain (custom intermediate).
// Expected: expect Tables in commonMain (EC); actual at jvmBasedMain (EC,EJ)
// — covers jvm + android via inheritance — and actual at linuxX64Main (EC).

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
    namespace = "demo.scenario7"
    compileSdk = 34
    defaultConfig { minSdk = 21 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
