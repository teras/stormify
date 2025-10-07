plugins {
    id("maven-publish")
    kotlin("multiplatform")
}

group = parent?.group ?: IllegalStateException("Group is not defined")
version = parent?.version ?: IllegalStateException("Version is not defined")
description = "Stormify Logger"

kotlin {
    jvm()
    linuxX64()
    jvmToolchain(11)

    sourceSets {
        val commonMain by getting {
            dependencies {
                //put your multiplatform dependencies here
            }
        }
        val commonTest by getting {
            dependencies {
//                implementation(libs.kotlin.test)
            }
        }
        val jvmMain by getting {

            dependencies {
                compileOnly("org.slf4j:slf4j-api:2.0.17")

                // Log4j API
                compileOnly("org.apache.logging.log4j:log4j-api:2.25.2")
                compileOnly("org.apache.logging.log4j:log4j-1.2-api:2.25.2")

                // Commons Logging
                compileOnly("commons-logging:commons-logging:1.3.5")
            }
        }
    }
}

//java.sourceCompatibility = JavaVersion.VERSION_1_8
//java.targetCompatibility = JavaVersion.VERSION_1_8
//
//dependencies {
//}

publishing {
    repositories {
        mavenLocal()
    }
}