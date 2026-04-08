allprojects {
    repositories {
        google()
        mavenCentral()
        mavenLocal()
    }
}

group = "onl.ycode"
version = "2.0.0"

plugins {
    (kotlin("multiplatform") version "2.2.20").apply(false)
    (kotlin("jvm") version "2.2.20").apply(false)
    (id("com.android.library") version "8.7.3").apply(false)
    id("org.jetbrains.dokka") version "2.2.0" apply false
}

// Apply native build tasks for Docker-based distribution builds
apply(from = "native-build.gradle.kts")

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
}

tasks.register("createDocs") {
    group = "documentation"
    description = "Generate documentation site locally (Dokka + Doxygen + MkDocs) into docs/build/"
    dependsOn(
        ":stormify:dokkaGenerateHtml",
        ":kdbc:dokkaGenerateHtml",
        ":logger:dokkaGenerateHtml"
    )
    doLast {
        // 1. Doxygen: KDBC C API reference
        ProcessBuilder("doxygen", "Doxyfile")
            .directory(file("kdbc/src/c"))
            .inheritIO().start().waitFor()
        // 2. MkDocs: main documentation site
        ProcessBuilder("mkdocs", "build")
            .directory(file("docs"))
            .inheritIO().start().waitFor()
        // 3. Doxygen again (mkdocs clean wipes the output dir)
        ProcessBuilder("doxygen", "Doxyfile")
            .directory(file("kdbc/src/c"))
            .inheritIO().start().waitFor()
        // 4. Dokka: copy API docs
        file("stormify/build/dokka/html").copyRecursively(file("docs/build/docs/api-stormify"), overwrite = true)
        file("kdbc/build/dokka/html").copyRecursively(file("docs/build/docs/api-kdbc-kotlin"), overwrite = true)
        // 5. Inject back-link bar into API reference pages
        val inject = file("docs/inject-backlink.sh").absolutePath
        ProcessBuilder("sh", inject, "docs/build/docs/api-stormify").inheritIO().start().waitFor()
        ProcessBuilder("sh", inject, "docs/build/docs/api-kdbc-kotlin").inheritIO().start().waitFor()
        ProcessBuilder("sh", inject, "docs/build/docs/kdbc-c").inheritIO().start().waitFor()
        // 6. Copy static assets
        file("docs/static").copyRecursively(file("docs/build"), overwrite = true)
    }
}

tasks.register("publishDocs") {
    group = "documentation"
    description = "Deploy documentation site to stormify.org (run createDocs first)"
    dependsOn("createDocs")
    doLast {
        ProcessBuilder(
            "rsync", "-ravz", "-e", "ssh -p 1971", "--delete",
            "docs/build/", "teras@yot.is:~/web/stormify.org/"
        ).inheritIO().start().waitFor()
    }
}