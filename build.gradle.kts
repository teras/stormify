allprojects {
    repositories {
        mavenCentral()
        mavenLocal()
    }
}


group = "onl.ycode.stormify"
version = "1.0.0"

subprojects {

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }

    afterEvaluate {
        // Check if the project should be publishable
        if (project.hasProperty("publishable") && project.property("publishable") == "true") {
            apply(plugin = "maven-publish")

            // Ensure 'publishing' extension is correctly accessed
            extensions.configure<PublishingExtension>("publishing") {
                publications {
                    create<MavenPublication>("mavenJava") {
                        from(components["java"])
                        groupId = project.group.toString()
                        artifactId = project.name
                        version = project.version.toString()
                    }
                }

                repositories {
                    mavenLocal()
                    // Add other repositories as needed, e.g., Maven Central or a private repository
                }
            }
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