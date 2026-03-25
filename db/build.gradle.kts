

plugins {
    `java-library`
    id("com.vanniktech.maven.publish")
}

group = parent?.group ?: IllegalStateException("Group is not defined")
version = parent?.version ?: IllegalStateException("Version is not defined")
description = "Stormify Database Library"

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    coordinates(group.toString(), "db", version.toString())
    pom {
        name.set("Stormify Database Library")
        description.set(project.description)
        url.set(rootProject.extra["pomUrl"] as String)
        inceptionYear.set(rootProject.extra["pomInceptionYear"] as String)
        licenses { license { name.set(rootProject.extra["pomLicenseName"] as String); url.set(rootProject.extra["pomLicenseUrl"] as String) } }
        developers { developer { id.set(rootProject.extra["pomDeveloperId"] as String); name.set(rootProject.extra["pomDeveloperName"] as String); email.set(rootProject.extra["pomDeveloperEmail"] as String) } }
        scm { url.set(rootProject.extra["pomScmUrl"] as String); connection.set(rootProject.extra["pomScmConnection"] as String); developerConnection.set(rootProject.extra["pomScmDevConnection"] as String) }
    }
}

java.sourceCompatibility = JavaVersion.VERSION_1_8
java.targetCompatibility = JavaVersion.VERSION_1_8

dependencies {
    implementation(project(":logger"))

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.12.2")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:5.12.2")
    testImplementation("org.junit.platform:junit-platform-launcher")
    testImplementation("com.mysql:mysql-connector-j:9.2.0")
    testImplementation("org.slf4j:slf4j-api:2.0.17")
    testImplementation("ch.qos.logback:logback-classic:1.5.18")
    testImplementation("ch.qos.logback:logback-core:1.5.18")
    testImplementation("com.zaxxer:HikariCP:6.3.0")

}

tasks.test {
    useJUnitPlatform() // Required for running JUnit 5 tests
    testLogging.showStandardStreams = true
    reports.html.required.set(false)
}
