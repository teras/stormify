// Scenario 5: entities in commonMain + linuxX64Main + jvmMain (android has none).
// Expected: expect Tables in commonMain (with EC); actuals at jvmMain (EC,EJvm),
// androidMain (EC), linuxX64Main (EC,EL).

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
    namespace = "demo.scenario5"
    compileSdk = 34
    defaultConfig { minSdk = 21 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
