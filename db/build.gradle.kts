

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
    testImplementation("org.slf4j:slf4j-api:2.0.17")
    testImplementation("ch.qos.logback:logback-classic:1.3.14")
    testImplementation("ch.qos.logback:logback-core:1.3.14")
    testImplementation("com.zaxxer:HikariCP:4.0.3")

    // Load only the JDBC driver for the target database (default: sqlite)
    val testDb = System.getProperty("stormify.test.db") ?: "sqlite"
    when {
        testDb.startsWith("mysql") || testDb == "mariadb-mysql-driver" ->
            testImplementation("com.mysql:mysql-connector-j:9.2.0")
        testDb.startsWith("mariadb") -> testImplementation("org.mariadb.jdbc:mariadb-java-client:3.5.3")
        testDb.startsWith("postgresql") -> testImplementation("org.postgresql:postgresql:42.7.5")
        testDb.startsWith("oracle") -> testImplementation("com.oracle.database.jdbc:ojdbc8:21.9.0.0")
        testDb.startsWith("mssql") -> testImplementation("com.microsoft.sqlserver:mssql-jdbc:12.8.1.jre8")
        testDb == "spring-jdbc" -> {
            testImplementation("org.springframework:spring-jdbc:5.3.39")
            testImplementation("org.xerial:sqlite-jdbc:3.47.2.0")
        }
        else -> testImplementation("org.xerial:sqlite-jdbc:3.47.2.0")
    }

}

tasks.test {
    useJUnitPlatform()
    testLogging.showStandardStreams = true
    reports.html.required.set(false)
    val testDb = System.getProperty("stormify.test.db") ?: "sqlite"
    systemProperty("stormify.test.db", testDb)
    systemProperty("stormify.test.config", System.getProperty("stormify.test.config")
        ?: if (testDb == "sqlite") "" else "")
}
