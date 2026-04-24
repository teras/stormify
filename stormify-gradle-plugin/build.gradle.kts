import com.vanniktech.maven.publish.GradlePlugin
import com.vanniktech.maven.publish.JavadocJar

plugins {
    `java-gradle-plugin`
    kotlin("jvm")
    id("com.vanniktech.maven.publish")
}

group = parent?.group ?: error("Group is not defined")
version = parent?.version ?: error("Version is not defined")
description = "Stormify Gradle Plugin: auto-wires annproc KSP processor across KMP, JVM and Android targets"

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
    jvmToolchain(11)
}

dependencies {
    // Kotlin Gradle Plugin types (KotlinMultiplatformExtension, targets, source sets).
    // compileOnly because the consumer always brings their own kotlin plugin version.
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin-api:2.2.21")
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.21")

    // KSP gradle plugin: bundled with our plugin so users do NOT need to add
    // `id("com.google.devtools.ksp")` themselves — applying our plugin pulls KSP in.
    // Note: 2.2.21-2.0.5 is the version that works with the KMP metadata compile;
    // earlier 2.0.x versions break `compileCommonMainKotlinMetadata`'s stdlib
    // classpath when applied to multiplatform projects.
    implementation("com.google.devtools.ksp:symbol-processing-gradle-plugin:2.2.21-2.0.5")
}

tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to project.name,
            "Implementation-Version" to project.version
        )
    }
}

// Bake the plugin version into a classpath resource so StormifyPlugin can
// read it even when the JAR manifest is stripped (uberjar repackaging,
// inclusion as a project dependency, etc.).
val pluginResourcesRoot = layout.buildDirectory.dir("generated/resources/plugin-version")
val generatePluginVersionResource by tasks.registering {
    val pluginVersion = project.version.toString()
    inputs.property("version", pluginVersion)
    outputs.dir(pluginResourcesRoot)
    doLast {
        val f = pluginResourcesRoot.get()
            .file("onl/ycode/stormify/gradle/plugin-version.txt").asFile
        f.parentFile.mkdirs()
        f.writeText(pluginVersion)
    }
}

sourceSets["main"].resources.srcDir(generatePluginVersionResource)

gradlePlugin {
    plugins {
        register("stormify") {
            id = "onl.ycode.stormify"
            implementationClass = "onl.ycode.stormify.gradle.StormifyPlugin"
            displayName = "Stormify Gradle Plugin"
            description = project.description
        }
    }
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    // GradlePlugin handles publishing of both the implementation artifact and
    // the plugin marker (`onl.ycode.stormify:onl.ycode.stormify.gradle.plugin`).
    configure(GradlePlugin(javadocJar = JavadocJar.Empty(), sourcesJar = true))
    coordinates(group.toString(), "stormify-gradle-plugin", version.toString())
    pom {
        name.set("Stormify Gradle Plugin")
        description.set(project.description)
        url.set(rootProject.extra["pomUrl"] as String)
        inceptionYear.set(rootProject.extra["pomInceptionYear"] as String)
        licenses {
            license {
                name.set(rootProject.extra["pomLicenseName"] as String)
                url.set(rootProject.extra["pomLicenseUrl"] as String)
            }
        }
        developers {
            developer {
                id.set(rootProject.extra["pomDeveloperId"] as String)
                name.set(rootProject.extra["pomDeveloperName"] as String)
                email.set(rootProject.extra["pomDeveloperEmail"] as String)
            }
        }
        scm {
            url.set(rootProject.extra["pomScmUrl"] as String)
            connection.set(rootProject.extra["pomScmConnection"] as String)
            developerConnection.set(rootProject.extra["pomScmDevConnection"] as String)
        }
    }
}
