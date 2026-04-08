plugins {
    kotlin("jvm") version "2.2.20"
    application
}

repositories {
    mavenLocal()
    mavenCentral()
}

kotlin {
    jvmToolchain(8)
    compilerOptions {
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }
}

dependencies {
    implementation("onl.ycode:stormify-jvm:2.0.0")
    implementation("org.xerial:sqlite-jdbc:3.47.2.0")
}

application {
    mainClass.set("demo.MainKt")
}
