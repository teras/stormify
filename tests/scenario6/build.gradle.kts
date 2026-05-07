// Scenario 6: entities in commonMain + every leaf.
// Expected: expect Tables in commonMain (EC); actuals at jvmMain (EC,EJvm),
// androidMain (EC,EA), linuxX64Main (EC,EL).

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
    namespace = "demo.scenario6"
    compileSdk = 34
    defaultConfig { minSdk = 21 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
