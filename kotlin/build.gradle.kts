import org.jetbrains.dokka.gradle.DokkaTask
import com.vanniktech.maven.publish.SonatypeHost

plugins {
    kotlin("jvm")
    id("org.jetbrains.dokka")
    id("com.vanniktech.maven.publish")
}

group = parent?.group ?: IllegalStateException("Group is not defined")
version = parent?.version ?: IllegalStateException("Version is not defined")
description = "Kotlin API for Stormify Framework"

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()
    coordinates(group.toString(), "kotlin", version.toString())
    pom {
        name.set("Stormify Kotlin API")
        description.set(project.description)
        url.set(rootProject.extra["pomUrl"] as String)
        inceptionYear.set(rootProject.extra["pomInceptionYear"] as String)
        licenses { license { name.set(rootProject.extra["pomLicenseName"] as String); url.set(rootProject.extra["pomLicenseUrl"] as String) } }
        developers { developer { id.set(rootProject.extra["pomDeveloperId"] as String); name.set(rootProject.extra["pomDeveloperName"] as String); email.set(rootProject.extra["pomDeveloperEmail"] as String) } }
        scm { url.set(rootProject.extra["pomScmUrl"] as String); connection.set(rootProject.extra["pomScmConnection"] as String); developerConnection.set(rootProject.extra["pomScmDevConnection"] as String) }
    }
}

dependencies {
    implementation(project(":db"))
    implementation(project(":logger"))

    testImplementation(kotlin("test-junit5"))

    testImplementation("com.mysql:mysql-connector-j:9.0.0")

    testImplementation("org.slf4j:slf4j-api:2.0.13")
    testImplementation("ch.qos.logback:logback-classic:1.5.6")
    testImplementation("ch.qos.logback:logback-core:1.5.6")

    testImplementation("com.zaxxer:HikariCP:5.1.0")

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