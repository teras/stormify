plugins {
    kotlin("jvm") version "2.0.0"
    id("com.google.devtools.ksp") version "2.0.0-1.0.23" // Use the latest KSP version
}

group = parent?.group ?: IllegalStateException("Group is not defined")
version = parent?.version ?: IllegalStateException("Version is not defined")
description = "Stormify Processor for Database Connectivity"
extra["publishable"] = "true"

java.sourceCompatibility = JavaVersion.VERSION_1_8
java.targetCompatibility = JavaVersion.VERSION_1_8

dependencies {
    implementation("com.google.devtools.ksp:symbol-processing-api:2.0.0-1.0.23")
}

kotlin {
    jvmToolchain(8)
}


