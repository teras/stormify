allprojects {
    repositories {
        mavenCentral()
        mavenLocal()
    }
}

group = "onl.ycode.stormify"
version = "1.0.0"

plugins {
    (kotlin("multiplatform") version "2.2.20").apply(false)
    (kotlin("jvm") version "2.2.20").apply(false)
}

// Apply native build tasks for Docker-based distribution builds
apply(from = "gradle/native-build.gradle.kts")