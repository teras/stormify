plugins {
    kotlin("jvm") apply false
    id("com.vanniktech.maven.publish") apply false
    id("org.jetbrains.dokka") apply false
}

allprojects {
    repositories {
        mavenCentral()
        mavenLocal()
    }
}

group = "onl.ycode.stormify"
version = "1.0.0"

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

subprojects {

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }

    // Configure signing for all subprojects that have the maven-publish plugin
    pluginManager.withPlugin("com.vanniktech.maven.publish") {
        apply(plugin = "signing")
        extensions.configure<SigningExtension> {
            useGpgCmd()
        }
    }
}

tasks.register("updateVersion") {
    group = "build"
    description = "Update the version in the project documentation"
    doLast {
        val mavenPattern = Regex("<version>[0-9.]+</version>")
        val gradlePattern = Regex(":[0-9.]+'")
        val gradleKtsPattern = Regex(":[0-9.]+\"")

        // Retrieve the new version from the command-line parameter
        val markdownFiles = fileTree("docs") {
            include("**/*.md")
        } + file("README.md") // Include README.md explicitly

        val newVersion = project.version.toString()
        markdownFiles.forEach { file ->
            val updatedText = file.readText()
                .replace(mavenPattern, "<version>$newVersion</version>")
                .replace(gradlePattern, ":$newVersion'")
                .replace(gradleKtsPattern, ":$newVersion\"")
            file.writeText(updatedText)
        }
    }
}

tasks.register("clean") {
    group = "build"
    description = "Clean the project"
    doLast {
        delete("docs/build")
    }
}

tasks.register("createDocs") {
    group = "documentation"
    description = "Generate the documentation site using MkDocs"
    dependsOn(
        subprojects.flatMap { subproject ->
            listOfNotNull(
                subproject.tasks.findByName("javadoc"),
                subproject.tasks.findByName("dokkaHtml")
            )
        }
    )

    doLast {
        exec {
            commandLine("mkdocs", "build")
            workingDir = file("docs")
        }
        file("db/build/docs/javadoc").copyRecursively(file("docs/build/docs/javadoc"))
        file("kotlin/build/dokka/html").copyRecursively(file("docs/build/docs/kotlin"))
        file("docs/static").copyRecursively(file("docs/build"))
        exec {
            commandLine("rsync", "-ravz", "-e", "ssh -p 1971", "--delete", "docs/build/", "teras@yot.is:~/web/stormify.org/")
        }
    }
}