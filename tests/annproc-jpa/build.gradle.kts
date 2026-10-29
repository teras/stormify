// Checks that the annotation processor reads JPA annotations exactly as JVM reflection
// does: the same entities are resolved once through reflection and once through the
// registrar annproc generates for them, and the two TableInfo results must agree.
plugins {
    kotlin("jvm")
    id("com.google.devtools.ksp") version "2.2.21-2.0.5"
}

group = parent?.group ?: error("Group is not defined")
version = parent?.version ?: error("Version is not defined")

kotlin {
    jvmToolchain(8)
}

ksp {
    // Keep the generated registrar apart from the conformance suite's.
    arg("stormify.generatedPackage", "test.jpa.generated")
}

dependencies {
    testImplementation(project(":stormify"))
    testImplementation("javax.persistence:javax.persistence-api:2.2")
    testImplementation("org.xerial:sqlite-jdbc:3.47.2.0")
    testImplementation(kotlin("test"))
    add("kspTest", project(":annproc"))
}

// Same KSP quirk as in the conformance module: the task can report UP-TO-DATE with an
// empty output directory, and the test compile then misses GeneratedEntities. It is
// fast, so always run it.
tasks.matching { it.name == "kspTestKotlin" }.configureEach {
    outputs.upToDateWhen { false }
}
