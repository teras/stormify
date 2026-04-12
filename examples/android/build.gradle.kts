// Top-level project file. Plugins are declared but not applied here — each
// subproject (just :app for now) opts in to the ones it needs.
plugins {
    id("com.android.application") version "8.7.3" apply false
    kotlin("android") version "2.2.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false
    id("com.google.devtools.ksp") version "2.2.20-2.0.2" apply false
    id("org.jetbrains.kotlinx.atomicfu") version "0.30.0-beta" apply false
}
