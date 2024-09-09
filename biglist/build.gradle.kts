plugins {
    `java-library`
}

group = parent?.group ?: IllegalStateException("Group is not defined")
version = parent?.version ?: IllegalStateException("Version is not defined")
description = "Stormify Big List"
extra["publishable"] = "true"

java.sourceCompatibility = JavaVersion.VERSION_1_8
java.targetCompatibility = JavaVersion.VERSION_1_8

dependencies {
    implementation(project(":db"))
    implementation(project(":logger"))
    compileOnly(project(":annproc"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
}

tasks.withType<JavaCompile> {
//    options.annotationProcessorGeneratedSourcesDirectory = file("src/main/generated")
    options.annotationProcessorPath = configurations.compileClasspath.get()
// Add compiler arguments if needed
//    options.compilerArgs.add("-Astormify.meta.class=db.F")
}

