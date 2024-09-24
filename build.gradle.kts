allprojects {
    repositories {
        mavenCentral()
        mavenLocal()
    }
}

group = "onl.ycode.stormify"
version = "1.0.0"

plugins {
    (kotlin("multiplatform") version "2.0.20").apply(false)
    (kotlin("jvm") version "2.0.20").apply(false)
}