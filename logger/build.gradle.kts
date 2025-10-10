plugins {
    id("maven-publish")
    kotlin("multiplatform")
    id("com.android.library")
}

group = parent?.group ?: IllegalStateException("Group is not defined")
version = parent?.version ?: IllegalStateException("Version is not defined")
description = "Stormify Logger"

kotlin {
    applyDefaultHierarchyTemplate()
    jvm()
    androidTarget {
        publishLibraryVariants("release", "debug")
    }
    linuxX64()
    
    // Apple targets - build enabled on macOS only
    if (org.gradle.internal.os.OperatingSystem.current().isMacOsX) {
        // iOS
        iosArm64()
        iosX64()
        iosSimulatorArm64()
        
        // macOS
        macosArm64()
        macosX64()
    }
    
    jvmToolchain(11)

    sourceSets {

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

android {
    namespace = "onl.ycode.logger"
    compileSdk = 34
    
    defaultConfig {
        minSdk = 21
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

publishing {
    repositories {
        mavenLocal()
    }
}