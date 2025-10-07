plugins {
    id("maven-publish")
    `java-library`
}

group = parent?.group ?: IllegalStateException("Group is not defined")
version = parent?.version ?: IllegalStateException("Version is not defined")
description = "Stormify Fuse"
extra["publishable"] = "true"

java.sourceCompatibility = JavaVersion.VERSION_11
java.targetCompatibility = JavaVersion.VERSION_11

dependencies {
    implementation(project(":db"))
    implementation(project(":logger"))

    implementation("com.panayotis:arjs:0.3.1")

    testImplementation("org.junit.jupiter:junit-jupiter:6.0.0")

    implementation("com.zaxxer:HikariCP:7.0.2")
    implementation("ch.qos.logback:logback-classic:1.5.19")
//    implementation("com.mysql:mysql-connector-j:9.0.0")

    implementation("com.github.serceman:jnr-fuse:0.5.7")
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