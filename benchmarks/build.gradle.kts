plugins {
    kotlin("multiplatform") version "2.2.21" apply false
    kotlin("jvm") version "2.2.21" apply false
    id("com.gradleup.shadow") version "8.3.5" apply false
    id("onl.ycode.stormify") version "3.0.1" apply false
}

allprojects {
    repositories {
        mavenLocal()
        mavenCentral()
    }
}
