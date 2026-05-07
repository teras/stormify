import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.util.Base64
import java.util.UUID
import javax.inject.Inject
import org.gradle.process.ExecOperations

plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.serialization") version "2.2.21"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":stormify"))
    implementation("com.googlecode.lanterna:lanterna:3.1.2")
    implementation("org.apache.lucene:lucene-core:9.11.1")
    implementation("org.apache.lucene:lucene-analysis-common:9.11.1")
    // SLF4J no-op binding: silences the "No SLF4J providers were found" warning
    // emitted at startup by transitive libraries that depend on slf4j-api.
    runtimeOnly("org.slf4j:slf4j-nop:2.0.16")
    implementation("com.akuleshov7:ktoml-core:0.7.0")
    implementation("com.akuleshov7:ktoml-file:0.7.0")
    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.2.21")
    runtimeOnly("org.xerial:sqlite-jdbc:3.46.1.0")
    runtimeOnly("org.postgresql:postgresql:42.7.4")
    runtimeOnly("org.mariadb.jdbc:mariadb-java-client:3.4.1")
    runtimeOnly("com.mysql:mysql-connector-j:9.0.0")
    runtimeOnly("com.oracle.database.jdbc:ojdbc11:23.5.0.24.07")
    runtimeOnly("com.oracle.database.nls:orai18n:23.5.0.24.07")
    runtimeOnly("com.microsoft.sqlserver:mssql-jdbc:12.8.1.jre11")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

// Reseed a target database + emit Kotlin entity files using the
// MockupBuilder spec. The generator lives in src/test so it has free
// access to JUnit assertions for the smoke variant.
tasks.register<JavaExec>("seedMockup") {
    group = "schema-sync"
    description = "Generate the synthetic 1000-table / 1000-entity mockup against a target DB."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "onl.ycode.stormify.schemasync.mockup.MockupCli"
}

application {
    mainClass = "onl.ycode.stormify.schemasync.MainKt"
}

kotlin {
    jvmToolchain(17)
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

// Concatenates every JDBC driver's `META-INF/services/java.sql.Driver`
// entries into a single file so SPI discovery finds all six drivers
// when the fatJar runs. The default `DuplicatesStrategy.EXCLUDE` keeps
// only the first such file and silently drops the rest — that is why
// schema-sync historically had to call `Class.forName` for every driver.
val mergedServicesDir = layout.buildDirectory.dir("fatjar-services")
val mergeJdbcDriverServices = tasks.register("mergeJdbcDriverServices") {
    val outDir = mergedServicesDir
    val classpath = configurations.runtimeClasspath
    inputs.files(classpath)
    outputs.dir(outDir)
    doLast {
        val target = outDir.get().asFile.resolve("META-INF/services/java.sql.Driver")
        target.parentFile.mkdirs()
        val entries = LinkedHashSet<String>()
        classpath.get().filter { it.name.endsWith("jar") }.forEach { jar ->
            zipTree(jar).matching { include("META-INF/services/java.sql.Driver") }.forEach { f ->
                f.readLines().forEach { line ->
                    val t = line.trim()
                    if (t.isNotEmpty() && !t.startsWith("#")) entries += t
                }
            }
        }
        target.writeText(entries.joinToString("\n", postfix = "\n"))
    }
}

tasks.register<Jar>("fatJar") {
    archiveClassifier.set("all")
    manifest {
        attributes["Main-Class"] = "onl.ycode.stormify.schemasync.MainKt"
        attributes["Multi-Release"] = "true"
    }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath, mergeJdbcDriverServices)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith("jar") }
            .map { zipTree(it) }
    }) {
        // Strip per-driver `java.sql.Driver` entries; the merged copy below
        // replaces them.
        exclude("META-INF/services/java.sql.Driver")
    }
    from(mergedServicesDir)
    exclude(
        "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA",
        "module-info.class",
        "META-INF/versions/*/module-info.class",
        "META-INF/versions/**/module-info.class",
    )
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// ===========================================================================
// Self-contained installers
// ===========================================================================
// Pipeline:
//   fatJar → jlinkRuntime → appImage → {linuxAppImage | windowsZip | macDmg}
//
// jlink picks the smallest possible JRE for our fatJar's modules; jpackage
// produces a platform-agnostic app-image directory; per-OS post-processing
// wraps it in the native distribution format. Cross-compile is not possible
// (jpackage produces host-OS layout), so each runner produces its own OS's
// artifact. All toolchains auto-provisioned via foojay-resolver — no manual
// JDK install needed.

val installersDir = layout.buildDirectory.dir("installers")
val jlinkRuntimeDir = layout.buildDirectory.dir("jlink-runtime")
val appImageDir = layout.buildDirectory.dir("app-image")

val javaToolchains = extensions.getByType<JavaToolchainService>()
val packagingLauncher = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(17))
}

abstract class JlinkRuntimeTask : DefaultTask() {
    @get:InputFile abstract val fatJar: RegularFileProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @get:Internal abstract val launcher: Property<JavaLauncher>
    @get:Inject abstract val execOps: ExecOperations

    @TaskAction
    fun action() {
        val home = launcher.get().metadata.installationPath.asFile.absolutePath
        val ext = if (System.getProperty("os.name").lowercase().contains("windows")) ".exe" else ""
        val out = outputDir.get().asFile
        out.deleteRecursively()
        val jarFile = fatJar.get().asFile

        val baos = ByteArrayOutputStream()
        execOps.exec {
            commandLine(
                "$home/bin/jdeps$ext",
                "--print-module-deps",
                "--ignore-missing-deps",
                "--multi-release", "17",
                jarFile.absolutePath,
            )
            standardOutput = baos
        }
        val modules = baos.toString(Charsets.UTF_8).trim()
        require(modules.isNotEmpty()) { "jdeps returned no modules for $jarFile" }

        execOps.exec {
            commandLine(
                "$home/bin/jlink$ext",
                "--module-path", "$home/jmods",
                "--add-modules", modules,
                "--strip-debug",
                "--no-header-files",
                "--no-man-pages",
                "--compress=2",
                "--output", out.absolutePath,
            )
        }
    }
}

val jlinkRuntime = tasks.register<JlinkRuntimeTask>("jlinkRuntime") {
    group = "distribution"
    description = "Build a stripped JRE sized for the schema-sync fatJar's modules."
    val fatJarTask = tasks.named<Jar>("fatJar")
    fatJar.set(fatJarTask.flatMap { it.archiveFile })
    outputDir.set(jlinkRuntimeDir)
    launcher.set(packagingLauncher)
}

abstract class AppImageTask : DefaultTask() {
    @get:InputFile abstract val mainJar: RegularFileProperty
    @get:InputDirectory abstract val runtimeImage: DirectoryProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @get:Input abstract val appName: Property<String>
    @get:Input abstract val appVersion: Property<String>
    @get:Input abstract val mainClass: Property<String>
    @get:Internal abstract val launcher: Property<JavaLauncher>
    @get:Inject abstract val execOps: ExecOperations

    @TaskAction
    fun action() {
        val home = launcher.get().metadata.installationPath.asFile.absolutePath
        val osName = System.getProperty("os.name").lowercase()
        val ext = if (osName.contains("windows")) ".exe" else ""
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()

        // jpackage --input sweeps every jar in the dir, so stage just the one we want.
        val staged = temporaryDir.resolve("input").apply { deleteRecursively(); mkdirs() }
        val jarName = mainJar.get().asFile.name
        mainJar.get().asFile.copyTo(staged.resolve(jarName), overwrite = true)

        execOps.exec {
            commandLine(
                "$home/bin/jpackage$ext",
                "--type", "app-image",
                "--name", appName.get(),
                "--app-version", appVersion.get(),
                "--input", staged.absolutePath,
                "--main-jar", jarName,
                "--main-class", mainClass.get(),
                "--dest", out.absolutePath,
                "--runtime-image", runtimeImage.get().asFile.absolutePath,
                "--description", "Stormify Schema Sync",
                "--vendor", "ycode.onl",
                "--copyright", "Copyright 2024-2026 Panayotis Katsaloulis",
            )
        }
    }
}

val appImageTask = tasks.register<AppImageTask>("appImage") {
    group = "distribution"
    description = "Build a platform-agnostic jpackage app-image directory."
    dependsOn(jlinkRuntime)
    val fatJarTask = tasks.named<Jar>("fatJar")
    mainJar.set(fatJarTask.flatMap { it.archiveFile })
    runtimeImage.set(jlinkRuntimeDir)
    outputDir.set(appImageDir)
    appName.set("schema-sync")
    appVersion.set(rootProject.version.toString().substringBefore("-SNAPSHOT"))
    mainClass.set("onl.ycode.stormify.schemasync.MainKt")
    launcher.set(packagingLauncher)
}

// --- Linux: AppImage ------------------------------------------------------
// Wraps the app-image directory with appimagetool (squashfs+zstd), producing
// a single executable file usable on any glibc 2.31+ Linux distro.
abstract class LinuxAppImageTask : DefaultTask() {
    @get:InputDirectory abstract val appImage: DirectoryProperty
    @get:OutputFile abstract val outputFile: RegularFileProperty
    @get:Input abstract val appName: Property<String>
    @get:Input abstract val appVersion: Property<String>
    @get:Inject abstract val execOps: ExecOperations

    @TaskAction
    fun action() {
        val srcRoot = appImage.get().asFile.resolve(appName.get())
        require(srcRoot.isDirectory) { "expected jpackage app-image at $srcRoot" }

        val appDir = temporaryDir.resolve("AppDir").apply { deleteRecursively(); mkdirs() }
        // Use cp -a to preserve executable bits and symlinks; Kotlin's
        // File.copyRecursively drops POSIX permissions.
        execOps.exec {
            commandLine("cp", "-a", srcRoot.absolutePath, appDir.resolve("usr").absolutePath)
        }

        // AppRun launcher
        val appRun = appDir.resolve("AppRun")
        appRun.writeText(
            """
            |#!/bin/sh
            |HERE="${'$'}(dirname "${'$'}(readlink -f "${'$'}0")")"
            |exec "${'$'}HERE/usr/bin/${appName.get()}" "${'$'}@"
            |
            """.trimMargin()
        )
        appRun.setExecutable(true)

        // Minimal .desktop entry (required by appimagetool)
        appDir.resolve("${appName.get()}.desktop").writeText(
            """
            |[Desktop Entry]
            |Type=Application
            |Name=Schema Sync
            |Exec=${appName.get()}
            |Icon=${appName.get()}
            |Categories=Development;
            |Terminal=true
            |
            """.trimMargin()
        )
        // Placeholder PNG icon (1x1 transparent) — appimagetool requires an icon.
        val iconPng = appDir.resolve("${appName.get()}.png")
        iconPng.writeBytes(
            // 1×1 transparent PNG (smallest valid file)
            byteArrayOf(
                0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
                0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
                0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4.toByte(),
                0x89.toByte(), 0x00, 0x00, 0x00, 0x0D, 0x49, 0x44, 0x41,
                0x54, 0x78, 0x9C.toByte(), 0x63, 0x00, 0x01, 0x00, 0x00,
                0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, 0xB4.toByte(),
                0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44,
                0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
            )
        )

        val appimagetool = downloadAppImageTool()
        val out = outputFile.get().asFile
        out.parentFile.mkdirs()
        out.delete()

        execOps.exec {
            environment("ARCH", "x86_64")
            commandLine(appimagetool.absolutePath, appDir.absolutePath, out.absolutePath)
        }
    }

    private fun downloadAppImageTool(): File {
        val cache = project.layout.buildDirectory.dir("appimagetool").get().asFile
        cache.mkdirs()
        val tool = cache.resolve("appimagetool-x86_64.AppImage")
        if (!tool.exists()) {
            val url = URI.create(
                "https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-x86_64.AppImage"
            ).toURL()
            url.openStream().use { input ->
                tool.outputStream().use { output -> input.copyTo(output) }
            }
            tool.setExecutable(true)
        }
        return tool
    }
}

tasks.register<LinuxAppImageTask>("linuxAppImage") {
    group = "distribution"
    description = "Build a Linux AppImage from the jpackage app-image."
    onlyIf { System.getProperty("os.name").lowercase().contains("linux") }
    dependsOn(appImageTask)
    appImage.set(appImageDir)
    appName.set("schema-sync")
    appVersion.set(rootProject.version.toString().substringBefore("-SNAPSHOT"))
    val ver = rootProject.version.toString().substringBefore("-SNAPSHOT")
    outputFile.set(installersDir.map { it.file("schema-sync-$ver-x86_64.AppImage") })
}

// --- Windows: unsigned ZIP ------------------------------------------------
// We don't have a Windows code-signing certificate; users run the .exe
// launcher directly from the unzipped folder. SmartScreen will warn on
// first run but allow execution.
tasks.register<Zip>("windowsZip") {
    group = "distribution"
    description = "Build a Windows ZIP from the jpackage app-image."
    onlyIf { System.getProperty("os.name").lowercase().contains("windows") }
    dependsOn(appImageTask)
    val ver = rootProject.version.toString().substringBefore("-SNAPSHOT")
    archiveFileName.set("schema-sync-$ver-windows-x64.zip")
    destinationDirectory.set(installersDir)
    from(appImageDir)
}

// --- macOS: signed + notarized DMG ----------------------------------------
// jpackage produces .app bundle; we codesign with Developer ID + entitlements,
// wrap in DMG, codesign DMG, submit to notarytool, staple. All signing is
// optional — if MACOS_CERTIFICATE / APPLE_NOTARY_JSON env vars are absent,
// produces unsigned DMG (good for local dev, rejected by Gatekeeper).
abstract class MacDmgTask : DefaultTask() {
    @get:InputDirectory abstract val appImage: DirectoryProperty
    @get:OutputFile abstract val outputFile: RegularFileProperty
    @get:Input abstract val appName: Property<String>
    @get:Input abstract val appVersion: Property<String>
    @get:Inject abstract val execOps: ExecOperations

    @TaskAction
    fun action() {
        val appBundle = appImage.get().asFile.resolve("${appName.get()}.app")
        require(appBundle.isDirectory) { "expected jpackage .app at $appBundle" }

        val cert = System.getenv("MACOS_CERTIFICATE")
        val certPwd = System.getenv("MACOS_CERTIFICATE_PWD")
        val notaryJson = System.getenv("APPLE_NOTARY_JSON")
        val signingEnabled = !cert.isNullOrBlank() && !certPwd.isNullOrBlank()
        val identity = if (signingEnabled) setupKeychain(cert!!, certPwd!!) else null

        if (identity != null) {
            logger.lifecycle("signing app bundle with identity $identity")
            execOps.exec {
                commandLine(
                    "codesign", "--force", "--deep",
                    "--sign", identity,
                    "--timestamp",
                    "--options", "runtime",
                    appBundle.absolutePath,
                )
            }
            execOps.exec {
                commandLine("codesign", "--verify", "--deep", "--strict", "--verbose=2", appBundle.absolutePath)
            }
        } else {
            logger.lifecycle("MACOS_CERTIFICATE not set — producing unsigned DMG")
        }

        val out = outputFile.get().asFile
        out.parentFile.mkdirs()
        out.delete()

        execOps.exec {
            commandLine(
                "hdiutil", "create",
                "-volname", appName.get(),
                "-srcfolder", appBundle.absolutePath,
                "-ov", "-format", "UDZO",
                out.absolutePath,
            )
        }

        if (identity != null) {
            execOps.exec {
                commandLine("codesign", "--force", "--sign", identity, "--timestamp", out.absolutePath)
            }
        }

        if (!notaryJson.isNullOrBlank()) {
            notarize(out, notaryJson)
        } else {
            logger.lifecycle("APPLE_NOTARY_JSON not set — skipping notarization")
        }
    }

    private fun setupKeychain(certB64: String, pwd: String): String {
        val keychainPwd = UUID.randomUUID().toString()
        val keychain = "schema-sync-build.keychain"
        val pfx = temporaryDir.resolve("cert.p12")
        pfx.writeBytes(Base64.getDecoder().decode(certB64))

        execOps.exec { commandLine("security", "create-keychain", "-p", keychainPwd, keychain) }
        execOps.exec { commandLine("security", "default-keychain", "-s", keychain) }
        execOps.exec { commandLine("security", "unlock-keychain", "-p", keychainPwd, keychain) }
        execOps.exec { commandLine("security", "set-keychain-settings", "-t", "3600", "-u", keychain) }
        execOps.exec {
            commandLine("security", "import", pfx.absolutePath, "-k", keychain, "-P", pwd, "-T", "/usr/bin/codesign")
        }
        execOps.exec {
            commandLine("security", "set-key-partition-list", "-S", "apple-tool:,apple:,codesign:", "-s", "-k", keychainPwd, keychain)
        }

        val baos = ByteArrayOutputStream()
        execOps.exec {
            commandLine("security", "find-identity", "-v", "-p", "codesigning", keychain)
            standardOutput = baos
        }
        val identityLine = baos.toString(Charsets.UTF_8).lines().firstOrNull { it.contains("Developer ID Application") }
            ?: error("no Developer ID Application identity found in keychain")
        return Regex("([A-F0-9]{40})").find(identityLine)?.value
            ?: error("could not parse identity hash from: $identityLine")
    }

    private fun notarize(dmg: File, notaryJson: String) {
        val parsed = Regex("\"(issuer_id|key_id|private_key)\"\\s*:\\s*\"([^\"]+)\"")
            .findAll(notaryJson).associate { it.groupValues[1] to it.groupValues[2] }
        val issuer = parsed["issuer_id"] ?: error("notary JSON missing issuer_id")
        val keyId = parsed["key_id"] ?: error("notary JSON missing key_id")
        val privateKey = parsed["private_key"] ?: error("notary JSON missing private_key")

        val keyFile = temporaryDir.resolve("AuthKey_$keyId.p8")
        keyFile.writeText("-----BEGIN PRIVATE KEY-----\n$privateKey\n-----END PRIVATE KEY-----\n")

        execOps.exec {
            commandLine(
                "xcrun", "notarytool", "submit", dmg.absolutePath,
                "--key", keyFile.absolutePath,
                "--key-id", keyId,
                "--issuer", issuer,
                "--wait",
            )
        }
        execOps.exec { commandLine("xcrun", "stapler", "staple", dmg.absolutePath) }
        keyFile.delete()
    }
}

tasks.register<MacDmgTask>("macDmg") {
    group = "distribution"
    description = "Build a macOS DMG (optionally signed + notarized) from the jpackage app-image."
    onlyIf { System.getProperty("os.name").lowercase().contains("mac") }
    dependsOn(appImageTask)
    appImage.set(appImageDir)
    appName.set("schema-sync")
    appVersion.set(rootProject.version.toString().substringBefore("-SNAPSHOT"))
    val ver = rootProject.version.toString().substringBefore("-SNAPSHOT")
    outputFile.set(installersDir.map { it.file("schema-sync-$ver-macos.dmg") })
}

// Convenience: builds whichever installer matches the current host OS.
tasks.register("installer") {
    group = "distribution"
    description = "Build the native installer for the current host OS."
    val osName = System.getProperty("os.name").lowercase()
    when {
        osName.contains("linux") -> dependsOn("linuxAppImage")
        osName.contains("windows") -> dependsOn("windowsZip")
        osName.contains("mac") -> dependsOn("macDmg")
        else -> doFirst { error("Unsupported OS: $osName") }
    }
}
