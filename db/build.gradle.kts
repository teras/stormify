plugins {
    `java-library`
}

group = parent?.group ?: IllegalStateException("Group is not defined")
version = parent?.version ?: IllegalStateException("Version is not defined")
description = "Stormify Database Library"
extra["publishable"] = "true"

java.sourceCompatibility = JavaVersion.VERSION_1_8
java.targetCompatibility = JavaVersion.VERSION_1_8

dependencies {
    implementation(project(":logger"))

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.9.3")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:5.9.3")
    testImplementation("com.mysql:mysql-connector-j:9.0.0")
    testImplementation("org.slf4j:slf4j-api:2.0.13")
    testImplementation("ch.qos.logback:logback-classic:1.5.6")
    testImplementation("ch.qos.logback:logback-core:1.5.6")
    testImplementation("com.zaxxer:HikariCP:5.1.0")

}

tasks.test {
    useJUnitPlatform() // Required for running JUnit 5 tests
    testLogging.showStandardStreams = true
    reports.html.required.set(false)
}
