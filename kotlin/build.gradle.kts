plugins {
    kotlin("jvm")
    id("org.jetbrains.dokka")
    id("com.vanniktech.maven.publish")
}

group = parent?.group ?: IllegalStateException("Group is not defined")
version = parent?.version ?: IllegalStateException("Version is not defined")
description = "Kotlin API for Stormify Framework"

mavenPublishing {
    publishToMavenCentral()
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

    // Load only the JDBC driver for the target database (default: sqlite)
    val testDb = System.getProperty("stormify.test.db") ?: "sqlite"
    when {
        testDb.startsWith("mysql") -> testImplementation("com.mysql:mysql-connector-j:9.2.0")
        testDb.startsWith("mariadb") -> testImplementation("org.mariadb.jdbc:mariadb-java-client:3.5.3")
        testDb.startsWith("postgresql") -> testImplementation("org.postgresql:postgresql:42.7.5")
        testDb.startsWith("oracle") -> testImplementation("com.oracle.database.jdbc:ojdbc8:21.9.0.0")
        testDb.startsWith("mssql") -> testImplementation("com.microsoft.sqlserver:mssql-jdbc:12.8.1.jre8")
        else -> testImplementation("org.xerial:sqlite-jdbc:3.47.2.0")
    }

    testImplementation("org.slf4j:slf4j-api:2.0.17")
    testImplementation("ch.qos.logback:logback-classic:1.3.14")
    testImplementation("ch.qos.logback:logback-core:1.3.14")

    testImplementation("com.zaxxer:HikariCP:4.0.3")

    testImplementation("org.junit.jupiter:junit-jupiter-engine:5.12.2")
}

kotlin {
    jvmToolchain(8)
}

tasks.test {
    useJUnitPlatform()
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(8))
    })
    val testDb = System.getProperty("stormify.test.db") ?: "sqlite"
    systemProperty("stormify.test.db", testDb)
    systemProperty("stormify.test.config", System.getProperty("stormify.test.config") ?: "")
}
