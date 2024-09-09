import org.jetbrains.dokka.gradle.DokkaTask

plugins {
    kotlin("jvm") version "2.0.0"
    id("org.jetbrains.dokka") version "1.9.20"
}

group = parent?.group ?: IllegalStateException("Group is not defined")
version = parent?.version ?: IllegalStateException("Version is not defined")
description = "Kotlin API for Stormify Framework"
extra["publishable"] = "true"

dependencies {
    implementation(project(":db"))
    implementation(project(":logger"))

    testImplementation(kotlin("test-junit5"))

    testImplementation("com.mysql:mysql-connector-j:9.0.0")

    testImplementation("org.slf4j:slf4j-api:2.0.13")
    testImplementation("ch.qos.logback:logback-classic:1.5.6")
    testImplementation("ch.qos.logback:logback-core:1.5.6")

    implementation("com.zaxxer:HikariCP:5.1.0")

    testImplementation("org.junit.jupiter:junit-jupiter-engine:5.9.3")

}

kotlin {
    jvmToolchain(8)
}

tasks.test {
    // Use JDK 11 for running tests
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(11))
    })
}

tasks.withType<DokkaTask>().configureEach {
    moduleName.set(project.name)
    dokkaSourceSets {
        configureEach {
            includes.from(project.files(), "package.md")
        }
    }
}