plugins {
    kotlin("jvm") version "2.2.20"
    application
}

repositories {
    mavenLocal()
    mavenCentral()
}

kotlin {
    jvmToolchain(11)
    compilerOptions {
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }
}

dependencies {
    implementation("onl.ycode:stormify-jvm:2.0.0")
    implementation("onl.ycode:kdbc-jvm:2.0.0")
    implementation("onl.ycode:logger-jvm:2.0.0")
    implementation(kotlin("reflect"))
    implementation("org.xerial:sqlite-jdbc:3.47.2.0")
    // JPA annotations for the User entity
    implementation("javax.persistence:javax.persistence-api:2.2")
}

application {
    mainClass.set("demo.MainKt")
}
