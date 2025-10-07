plugins {
    id("maven-publish")
    kotlin("jvm")
    id("com.google.devtools.ksp") version "2.2.20-2.0.2" // Use the latest KSP version
}

group = parent?.group ?: IllegalStateException("Group is not defined")
version = parent?.version ?: IllegalStateException("Version is not defined")
description = "Stormify Processor for Database Connectivity"

java.sourceCompatibility = JavaVersion.VERSION_11
java.targetCompatibility = JavaVersion.VERSION_11

dependencies {
    implementation("com.google.devtools.ksp:symbol-processing-api:2.2.20-2.0.2")
}

kotlin {
    jvmToolchain(11)
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
