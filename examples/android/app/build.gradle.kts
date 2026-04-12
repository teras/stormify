plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    // The Stormify annotation processor emits a registration object that uses
    // `kotlinx.atomicfu.atomic { ... }` for one-shot init. The atomicfu plugin
    // both supplies the runtime dependency and post-processes JVM/Android
    // bytecode to drop atomicfu types — keeping the APK lean.
    id("org.jetbrains.kotlinx.atomicfu") version "0.30.0-beta"
}

android {
    namespace = "demo.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "demo.android"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlin {
        jvmToolchain(11)
    }

    buildTypes {
        getByName("release") {
            // Don't shrink — keeps the example simple. A real app should enable
            // R8 and add proguard rules for kotlin-reflect (Stormify uses it on
            // JVM/Android paths for the reflective entity discovery fallback).
            isMinifyEnabled = false
        }
    }
}

dependencies {
    // Stormify core (Android variant). The annotation processor `annproc` runs
    // at compile time and generates the TableInfo registration source — required
    // on Android because reflection-based entity discovery isn't fully supported
    // on AOT-compiled paths.
    implementation("onl.ycode:stormify-android:2.0.0")
    ksp("onl.ycode:annproc:2.0.0")

    // Annproc-generated registrar uses kotlinx.atomicfu — declare the runtime
    // dependency explicitly. The atomicfu Gradle plugin (above) post-processes
    // the bytecode after compilation but it does not add the dependency on its
    // own when the Kotlin Android plugin is what's driving the project.
    implementation("org.jetbrains.kotlinx:atomicfu:0.30.0-beta")

    // Compose UI — kept minimal so the example focuses on Stormify, not Compose.
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
