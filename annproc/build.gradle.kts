plugins {
    id("maven-publish")
    kotlin("jvm")
    id("com.google.devtools.ksp") version "2.0.20-1.0.25" // Use the latest KSP version
}

group = parent?.group ?: IllegalStateException("Group is not defined")
version = parent?.version ?: IllegalStateException("Version is not defined")
description = "Stormify Processor for Database Connectivity"

java.sourceCompatibility = JavaVersion.VERSION_1_8
java.targetCompatibility = JavaVersion.VERSION_1_8

dependencies {
    implementation("com.google.devtools.ksp:symbol-processing-api:2.0.20-1.0.25")
}

kotlin {
    jvmToolchain(8)
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            groupId = project.group.toString()
            artifactId = project.name
            version = project.version.toString()
        }
    }
    repositories {
        mavenLocal()
    }
}
