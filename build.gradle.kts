allprojects {
    repositories {
        google()
        mavenCentral()
        mavenLocal()
    }
}

group = "onl.ycode"
version = "2.6.0"

plugins {
    base   // provides a root-level `clean` task so docs.gradle.kts can hook into it
    (kotlin("multiplatform") version "2.2.21").apply(false)
    (kotlin("jvm") version "2.2.21").apply(false)
    (id("com.android.library") version "8.7.3").apply(false)
    id("org.jetbrains.dokka") version "2.2.0" apply false
    id("com.vanniktech.maven.publish") version "0.35.0" apply false
}

// Common POM metadata for all publishable subprojects
extra["pomUrl"] = "https://github.com/teras/stormify"
extra["pomInceptionYear"] = "2024"
extra["pomLicenseName"] = "Apache License, Version 2.0"
extra["pomLicenseUrl"] = "https://www.apache.org/licenses/LICENSE-2.0.txt"
extra["pomDeveloperId"] = "teras"
extra["pomDeveloperName"] = "Panayotis Katsaloulis"
extra["pomDeveloperEmail"] = "panayotis@panayotis.com"
extra["pomScmUrl"] = "https://github.com/teras/stormify"
extra["pomScmConnection"] = "scm:git:git://github.com/teras/stormify.git"
extra["pomScmDevConnection"] = "scm:git:ssh://github.com/teras/stormify.git"

// Dokka configuration for all subprojects
subprojects {
    pluginManager.withPlugin("org.jetbrains.dokka") {
        extensions.configure<org.jetbrains.dokka.gradle.DokkaExtension> {
            dokkaPublications.configureEach {
                suppressInheritedMembers.set(true)
            }
            dokkaSourceSets.configureEach {
                reportUndocumented.set(true)
            }
        }
    }

    // Skip signing when publishing to Maven Local. The vanniktech plugin
    // picks up ORG_GRADLE_PROJECT_signingInMemoryKey / ...KeyId / ...KeyPassword
    // automatically from env when signAllPublications() is declared in the
    // module — no manual signing config needed here.
    pluginManager.withPlugin("com.vanniktech.maven.publish") {
        afterEvaluate {
            tasks.withType<Sign>().configureEach {
                onlyIf {
                    !gradle.taskGraph.hasTask(":${project.name}:publishToMavenLocal") &&
                    !gradle.startParameter.taskNames.any { it.contains("MavenLocal") }
                }
            }
        }
        // Add a shared local staging repository so multi-runner CI can collect
        // platform-specific klibs (Linux builds JVM/linux/mingw/android metadata;
        // macOS builds apple targets) into one merged directory, then upload
        // as a single Central Portal deployment.
        extensions.configure<PublishingExtension> {
            repositories {
                maven {
                    name = "LocalStaging"
                    url = rootProject.layout.buildDirectory.dir("staging-deploy").get().asFile.toURI()
                }
            }
        }
    }
}


apply(from = "gradle/docs.gradle.kts")
