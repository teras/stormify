// Scenario 8: full 3-tier — entities in commonMain + jvmBasedMain + jvmMain leaf.
// EXPECTED: build FAILS with a helpful error from the plugin.
// Reason: jvm leaf has EJvm, android leaf does not → distinct anchors at jvmMain/androidMain.
// LCA of those anchors is commonMain → expect must live at commonMain.
// But the shared entity EJ lives in jvmBasedMain, which is invisible to commonMain.
// No legal Kotlin placement exists; the planner rejects the topology.

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
        val commonMain by getting
        val jvmBasedMain by creating { dependsOn(commonMain) }
        val jvmMain by getting { dependsOn(jvmBasedMain) }
        val androidMain by getting { dependsOn(jvmBasedMain) }
    }
}

android {
    namespace = "demo.scenario8"
    compileSdk = 34
    defaultConfig { minSdk = 21 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
