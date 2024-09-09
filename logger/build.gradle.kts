plugins {
    `java-library`
}

group = parent?.group ?: IllegalStateException("Group is not defined")
version = parent?.version ?: IllegalStateException("Version is not defined")
description = "Stormify Logger"
extra["publishable"] = "true"

java.sourceCompatibility = JavaVersion.VERSION_1_8
java.targetCompatibility = JavaVersion.VERSION_1_8

dependencies {
    // SLF4J API
    compileOnly("org.slf4j:slf4j-api:2.0.13")

    // Log4j API
    compileOnly("org.apache.logging.log4j:log4j-api:2.23.1")
    compileOnly("org.apache.logging.log4j:log4j-1.2-api:2.17.0")

    // Commons Logging
    compileOnly("commons-logging:commons-logging:1.2")
}

extra["publishable"] = "true"

