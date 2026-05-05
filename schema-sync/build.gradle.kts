plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.serialization") version "2.2.21"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.googlecode.lanterna:lanterna:3.1.2")
    implementation("org.apache.lucene:lucene-core:9.11.1")
    implementation("org.apache.lucene:lucene-analysis-common:9.11.1")
    implementation("com.akuleshov7:ktoml-core:0.7.0")
    implementation("com.akuleshov7:ktoml-file:0.7.0")
    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.2.21")
    runtimeOnly("org.xerial:sqlite-jdbc:3.46.1.0")
    runtimeOnly("org.postgresql:postgresql:42.7.4")
    runtimeOnly("org.mariadb.jdbc:mariadb-java-client:3.4.1")
    runtimeOnly("com.mysql:mysql-connector-j:9.0.0")
    runtimeOnly("com.oracle.database.jdbc:ojdbc11:23.5.0.24.07")
    runtimeOnly("com.oracle.database.nls:orai18n:23.5.0.24.07")
    runtimeOnly("com.microsoft.sqlserver:mssql-jdbc:12.8.1.jre11")
}

application {
    mainClass = "onl.ycode.stormify.schemasync.MainKt"
}

kotlin {
    jvmToolchain(17)
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

tasks.register<Jar>("fatJar") {
    archiveClassifier.set("all")
    manifest {
        attributes["Main-Class"] = "onl.ycode.stormify.schemasync.MainKt"
        attributes["Multi-Release"] = "true"
    }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith("jar") }
            .map { zipTree(it) }
    })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
