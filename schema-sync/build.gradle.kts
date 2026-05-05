plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.serialization") version "2.2.21"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":stormify"))
    implementation("com.googlecode.lanterna:lanterna:3.1.2")
    implementation("org.apache.lucene:lucene-core:9.11.1")
    implementation("org.apache.lucene:lucene-analysis-common:9.11.1")
    // SLF4J no-op binding: silences the "No SLF4J providers were found" warning
    // emitted at startup by transitive libraries that depend on slf4j-api.
    runtimeOnly("org.slf4j:slf4j-nop:2.0.16")
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

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
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

// Concatenates every JDBC driver's `META-INF/services/java.sql.Driver`
// entries into a single file so SPI discovery finds all six drivers
// when the fatJar runs. The default `DuplicatesStrategy.EXCLUDE` keeps
// only the first such file and silently drops the rest — that is why
// schema-sync historically had to call `Class.forName` for every driver.
val mergedServicesDir = layout.buildDirectory.dir("fatjar-services")
val mergeJdbcDriverServices = tasks.register("mergeJdbcDriverServices") {
    val outDir = mergedServicesDir
    val classpath = configurations.runtimeClasspath
    inputs.files(classpath)
    outputs.dir(outDir)
    doLast {
        val target = outDir.get().asFile.resolve("META-INF/services/java.sql.Driver")
        target.parentFile.mkdirs()
        val entries = LinkedHashSet<String>()
        classpath.get().filter { it.name.endsWith("jar") }.forEach { jar ->
            zipTree(jar).matching { include("META-INF/services/java.sql.Driver") }.forEach { f ->
                f.readLines().forEach { line ->
                    val t = line.trim()
                    if (t.isNotEmpty() && !t.startsWith("#")) entries += t
                }
            }
        }
        target.writeText(entries.joinToString("\n", postfix = "\n"))
    }
}

tasks.register<Jar>("fatJar") {
    archiveClassifier.set("all")
    manifest {
        attributes["Main-Class"] = "onl.ycode.stormify.schemasync.MainKt"
        attributes["Multi-Release"] = "true"
    }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath, mergeJdbcDriverServices)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith("jar") }
            .map { zipTree(it) }
    }) {
        // Strip per-driver `java.sql.Driver` entries; the merged copy below
        // replaces them.
        exclude("META-INF/services/java.sql.Driver")
    }
    from(mergedServicesDir)
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
