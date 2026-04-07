allprojects {
    repositories {
        google()
        mavenCentral()
        mavenLocal()
    }
}

group = "onl.ycode"
version = "2.0.0"

plugins {
    (kotlin("multiplatform") version "2.2.20").apply(false)
    (kotlin("jvm") version "2.2.20").apply(false)
    (id("com.android.library") version "8.7.3").apply(false)
}

// Apply native build tasks for Docker-based distribution builds
apply(from = "native-build.gradle.kts")