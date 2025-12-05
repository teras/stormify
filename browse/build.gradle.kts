import com.vanniktech.maven.publish.SonatypeHost

plugins {
    `java-library`
    id("com.github.johnrengelman.shadow") version "7.1.2"
    id("com.vanniktech.maven.publish")
}

group = parent?.group ?: IllegalStateException("Group is not defined")
version = parent?.version ?: IllegalStateException("Version is not defined")
description = "Stormify Fuse - Database Browser"

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()
    coordinates(group.toString(), "stormify-browse", version.toString())
    pom {
        name.set("Stormify Browse")
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