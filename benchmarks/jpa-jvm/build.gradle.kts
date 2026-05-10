plugins {
    kotlin("jvm")
    id("com.gradleup.shadow")
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Hibernate 6 — latest stable
    implementation("org.hibernate.orm:hibernate-core:6.6.4.Final")
    implementation("org.hibernate.orm:hibernate-hikaricp:6.6.4.Final")

    // JDBC drivers — latest of each
    implementation("org.xerial:sqlite-jdbc:3.47.2.0")
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("com.mysql:mysql-connector-j:9.1.0")
    implementation("com.oracle.database.jdbc:ojdbc11:23.6.0.24.10")
    implementation("com.microsoft.sqlserver:mssql-jdbc:12.8.1.jre11")

    // Hibernate community dialects (provides SQLite, etc.)
    implementation("org.hibernate.orm:hibernate-community-dialects:6.6.4.Final")

    // Logging — keep minimal
    implementation("org.slf4j:slf4j-simple:2.0.16")
}

application {
    mainClass.set("bench.MainKt")
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveBaseName.set("jpa-jvm-bench")
    archiveClassifier.set("")
    archiveVersion.set("")
    mergeServiceFiles()
    manifest { attributes["Main-Class"] = "bench.MainKt" }
}

tasks.named("build") { dependsOn("shadowJar") }
