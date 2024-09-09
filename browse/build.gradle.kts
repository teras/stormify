plugins {
    `java-library`
    id("com.github.johnrengelman.shadow") version "7.1.2"
}

group = parent?.group ?: IllegalStateException("Group is not defined")
version = parent?.version ?: IllegalStateException("Version is not defined")
description = "Stormify Fuse"
extra["publishable"] = "true"

java.sourceCompatibility = JavaVersion.VERSION_1_8
java.targetCompatibility = JavaVersion.VERSION_1_8

dependencies {
    implementation(project(":db"))
    implementation(project(":logger"))

    implementation("com.panayotis:arjs:0.3.1")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")

    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("ch.qos.logback:logback-classic:1.2.3")
//    implementation("com.mysql:mysql-connector-j:9.0.0")

    implementation("com.github.serceman:jnr-fuse:0.5.7")
}

tasks {
    shadowJar {
        archiveClassifier.set("") // Removes the `-all` suffix from the filename
        manifest { attributes["Main-Class"] = "onl.ycode.fuse.Main" }
//        dependsOn(distTar, distZip)
    }
}