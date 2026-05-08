import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.util.Base64
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
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
    // 3.1.2 lacks a Windows-console terminal — its DefaultTerminalFactory
    // does Class.forName("com.googlecode.lanterna.terminal.WindowsTerminal")
    // which always misses, leaving stty.exe (Cygwin) or javaw as the only
    // options. 3.2.0-alpha1 added a JNA-backed terminal but broke other APIs
    // we use. We backport the 3.2 win32 sources under the package path 3.1.2
    // looks up so a plain `java.exe` console run renders the TUI natively.
    implementation("net.java.dev.jna:jna:5.14.0")
    implementation("net.java.dev.jna:jna-platform:5.14.0")
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
    jvmToolchain(11)
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
//   fatJar → jlinkRuntime → appImage → {linuxAppImage | windowsZip | macTar}
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
// jpackage was added in JDK 14 — JDK 11 cannot package, so the packaging
// tool runs from a JDK 17 toolchain regardless of the schema-sync bytecode
// target. The shipped runtime image, however, is built with JDK 11 jlink
// so end users get a smaller, JDK 11-class runtime.
val jdk17PackagingLauncher = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(17))
}
val jdk11RuntimeLauncher = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(11))
}

// jdeps in JDK 11 NPEs on multi-release fatJars (known bug); we run jdeps
// from the JDK 17 toolchain instead. Module names are stable across
// versions so the resulting list is valid input for JDK 11 jlink, as long
// as the deps don't reference modules introduced after JDK 11 (none of
// schema-sync's deps do).
abstract class JlinkRuntimeTask : DefaultTask() {
    @get:InputFile abstract val fatJar: RegularFileProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @get:Internal abstract val jlinkLauncher: Property<JavaLauncher>
    @get:Internal abstract val jdepsLauncher: Property<JavaLauncher>
    @get:Inject abstract val execOps: ExecOperations

    @TaskAction
    fun action() {
        val jlinkHome = jlinkLauncher.get().metadata.installationPath.asFile.absolutePath
        val jdepsHome = jdepsLauncher.get().metadata.installationPath.asFile.absolutePath
        val ext = if (System.getProperty("os.name").lowercase().contains("windows")) ".exe" else ""
        val out = outputDir.get().asFile
        out.deleteRecursively()
        val jarFile = fatJar.get().asFile

        val baos = ByteArrayOutputStream()
        execOps.exec {
            commandLine(
                "$jdepsHome/bin/jdeps$ext",
                "--print-module-deps",
                "--ignore-missing-deps",
                "--multi-release", "11",
                jarFile.absolutePath,
            )
            standardOutput = baos
        }
        val modules = baos.toString(Charsets.UTF_8).trim()
        require(modules.isNotEmpty()) { "jdeps returned no modules for $jarFile" }

        execOps.exec {
            commandLine(
                "$jlinkHome/bin/jlink$ext",
                "--module-path", "$jlinkHome/jmods",
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
    description = "Build a stripped JDK 11 runtime image sized for the schema-sync fatJar's modules."
    val fatJarTask = tasks.named<Jar>("fatJar")
    fatJar.set(fatJarTask.flatMap { it.archiveFile })
    outputDir.set(jlinkRuntimeDir)
    jlinkLauncher.set(jdk11RuntimeLauncher)
    jdepsLauncher.set(jdk17PackagingLauncher)
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

        // schema-sync is a TUI (Lanterna) app, so the Windows launcher must
        // be a console binary rather than the default jpackage windowed
        // launcher — without --win-console it detaches from the parent
        // terminal and the user sees no output.
        val perOs = if (osName.contains("windows")) listOf("--win-console") else emptyList()

        execOps.exec {
            commandLine(
                listOf(
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
                ) + perOs
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
    launcher.set(jdk17PackagingLauncher)
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

// --- macOS: signed + notarized tar.gz with CLI wrapper -------------------
// schema-sync is a CLI/TUI tool; a .app bundle and a .dmg installer are
// awkward for that use case. Instead we produce a tarball containing the
// signed/notarized .app (so codesign + Gatekeeper still work) plus a
// sibling `schema-sync` shell wrapper that invokes the bundle's launcher.
// User flow: `tar xzf schema-sync-...-macos.tar.gz && ./schema-sync ...`.
//
// Notarization submission still uses a ZIP (Apple Notary requires .zip /
// .pkg / .dmg as transport); the ticket is stapled to the .app bundle so
// the final tarball can be moved or copied without losing the ticket.
abstract class MacTarTask : DefaultTask() {
    @get:InputDirectory abstract val appImage: DirectoryProperty
    @get:OutputFile abstract val outputFile: RegularFileProperty
    @get:Input abstract val appName: Property<String>
    @get:InputFile abstract val entitlementsFile: RegularFileProperty
    @get:Inject abstract val execOps: ExecOperations

    @TaskAction
    fun action() {
        val appBundle = appImage.get().asFile.resolve("${appName.get()}.app")
        require(appBundle.isDirectory) { "expected jpackage .app at $appBundle" }

        val cert = System.getenv("APPLE_CERTIFICATE_P12")
        val certPwd = System.getenv("APPLE_CERTIFICATE_PASSWORD")
        val notaryJson = System.getenv("APPLE_NOTARY_KEY_JSON")
        val signingEnabled = !cert.isNullOrBlank() && !certPwd.isNullOrBlank()
        val identity = if (signingEnabled) setupKeychain(cert!!, certPwd!!) else null

        if (identity != null) {
            signBundle(appBundle, identity, entitlementsFile.get().asFile)
        } else {
            logger.lifecycle("APPLE_CERTIFICATE_P12 not set — producing unsigned tarball")
        }

        if (!notaryJson.isNullOrBlank() && identity != null) {
            val notaryZip = temporaryDir.resolve("for-notary.zip").also { it.delete() }
            execOps.exec {
                workingDir = appBundle.parentFile
                // ditto preserves resource forks and xattrs that the
                // notary service expects; plain `zip` would strip them.
                commandLine("ditto", "-c", "-k", "--keepParent", appBundle.name, notaryZip.absolutePath)
            }
            notarizeAndStaple(notaryZip, appBundle, notaryJson)
        } else {
            logger.lifecycle("APPLE_NOTARY_KEY_JSON not set — skipping notarization")
        }

        val out = outputFile.get().asFile
        out.parentFile.mkdirs()
        out.delete()

        // Stage the .app + a wrapper script that invokes its launcher.
        val stage = temporaryDir.resolve("tarball-stage").apply {
            deleteRecursively(); mkdirs()
        }
        execOps.exec { commandLine("cp", "-a", appBundle.absolutePath, stage.absolutePath) }
        val name = appName.get()
        val wrapper = stage.resolve(name)
        wrapper.writeText(
            """
            |#!/bin/bash
            |DIR="${'$'}( cd "${'$'}( dirname "${'$'}{BASH_SOURCE[0]}" )" && pwd )"
            |exec "${'$'}DIR/$name.app/Contents/MacOS/$name" "${'$'}@"
            |
            """.trimMargin()
        )
        wrapper.setExecutable(true)

        execOps.exec {
            workingDir = stage
            commandLine("tar", "-czf", out.absolutePath, ".")
        }
    }

    // Inner-out signing: sign every nested Mach-O first, then the bundle
    // last. Apple Notary Service rejects packages whose inner binaries are
    // unsigned or were signed without the hardened runtime. We detect
    // Mach-O by reading the first four magic bytes rather than filename
    // suffix — jpackage's bundled JRE ships extension-less Mach-O like
    // Contents/Home/lib/jspawnhelper that an extension filter misses.
    private fun signBundle(appBundle: File, identity: String, entitlements: File) {
        val innerBinaries = appBundle.walkTopDown()
            .filter { it.isFile && isMachO(it) }
            .toList()
        logger.lifecycle("signing ${innerBinaries.size} inner Mach-O binaries")
        for (bin in innerBinaries) {
            signFile(bin, identity, entitlements)
        }

        signNativeLibsInsideJars(appBundle, identity, entitlements)

        logger.lifecycle("signing .app bundle")
        signFile(appBundle, identity, entitlements)
        execOps.exec {
            commandLine("codesign", "--verify", "--deep", "--strict", appBundle.absolutePath)
        }
    }

    private fun signFile(target: File, identity: String, entitlements: File) {
        execOps.exec {
            commandLine(
                "codesign", "--force",
                "--sign", identity,
                "--timestamp",
                "--options", "runtime",
                "--entitlements", entitlements.absolutePath,
                target.absolutePath,
            )
        }
    }

    // Apple Notary recursively inspects JARs for embedded Mach-O binaries
    // (extracted at runtime by JNI loaders, e.g. sqlite-jdbc) and rejects
    // packages whose inner-jar libs are unsigned. We stream the JAR
    // through ZipInputStream → ZipOutputStream, replacing each Mach-O
    // entry's bytes with its signed version. Pure Java rather than shelling
    // out to unzip/zip — far faster on JARs with thousands of class
    // entries (kotlin-compiler-embeddable is ~70 MB on its own).
    private fun signNativeLibsInsideJars(appBundle: File, identity: String, entitlements: File) {
        val jars = appBundle.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".jar") }
            .toList()
        for (jar in jars) {
            val hasCandidate = ZipFile(jar).use { zf ->
                zf.entries().asSequence().any { e ->
                    !e.isDirectory && (
                        e.name.endsWith(".dylib") ||
                            e.name.endsWith(".jnilib") ||
                            e.name.endsWith(".so")
                        )
                }
            }
            if (!hasCandidate) continue

            logger.lifecycle("re-signing native libs inside ${jar.relativeTo(appBundle)}")
            signJarInPlace(jar, identity, entitlements)
        }
    }

    private fun signJarInPlace(jar: File, identity: String, entitlements: File) {
        val tmpJar = File("${jar.absolutePath}.signing.tmp")
        val tmpLib = File.createTempFile("jar-native-", "")
        try {
            ZipFile(jar).use { zin ->
                ZipOutputStream(tmpJar.outputStream().buffered()).use { zout ->
                    for (entry in zin.entries()) {
                        val name = entry.name
                        val isCandidate = !entry.isDirectory && (
                            name.endsWith(".dylib") || name.endsWith(".jnilib") || name.endsWith(".so")
                            )
                        zout.putNextEntry(ZipEntry(name).apply { time = entry.time })
                        if (isCandidate) {
                            tmpLib.outputStream().use { out ->
                                zin.getInputStream(entry).use { it.copyTo(out) }
                            }
                            if (isMachO(tmpLib)) {
                                signFile(tmpLib, identity, entitlements)
                            }
                            tmpLib.inputStream().use { it.copyTo(zout) }
                        } else if (!entry.isDirectory) {
                            zin.getInputStream(entry).use { it.copyTo(zout) }
                        }
                        zout.closeEntry()
                    }
                }
            }
            jar.delete()
            check(tmpJar.renameTo(jar)) { "could not replace $jar with signed version" }
        } finally {
            tmpLib.delete()
            if (tmpJar.exists()) tmpJar.delete()
        }
    }

    private fun isMachO(file: File): Boolean {
        if (file.length() < 4) return false
        val magic = file.inputStream().use { input ->
            val b = ByteArray(4)
            if (input.read(b) != 4) return false
            ((b[0].toInt() and 0xFF) shl 24) or
                ((b[1].toInt() and 0xFF) shl 16) or
                ((b[2].toInt() and 0xFF) shl 8) or
                (b[3].toInt() and 0xFF)
        }
        // 32-/64-bit Mach-O in both byte orders, plus universal "fat" archive.
        return magic == 0xFEEDFACE.toInt() || magic == 0xFEEDFACF.toInt() ||
            magic == 0xCEFAEDFE.toInt() || magic == 0xCFFAEDFE.toInt() ||
            magic == 0xCAFEBABE.toInt()
    }

    private fun setupKeychain(certB64: String, pwd: String): String {
        val keychainPwd = UUID.randomUUID().toString()
        val keychain = "schema-sync-build.keychain"
        val pfx = temporaryDir.resolve("cert.p12")
        pfx.writeBytes(Base64.getDecoder().decode(certB64))

        execOps.exec { commandLine("security", "create-keychain", "-p", keychainPwd, keychain) }
        execOps.exec { commandLine("security", "default-keychain", "-s", keychain) }
        execOps.exec { commandLine("security", "unlock-keychain", "-p", keychainPwd, keychain) }
        // No `set-keychain-settings`: the freshly-created keychain has no
        // auto-lock timeout by default, which is what we want for an
        // ephemeral CI keychain that lives only for the build's duration.
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
        val findIdentityOut = baos.toString(Charsets.UTF_8)
        // Pick the first Developer ID Application identity. The CI keychain
        // is freshly created and only ever holds the one cert we imported,
        // so there is nothing to disambiguate; this matches the pattern
        // used by Jubler / swoop / mapgrow workflows in the same account.
        val match = findIdentityOut.lines().firstOrNull { it.contains("Developer ID Application") }
        if (match == null) {
            logger.error("security find-identity output:\n$findIdentityOut")
            error(
                "no Developer ID Application identity in keychain — DMG signing requires" +
                " a 'Developer ID Application' cert (not 'Apple Distribution' / 'Apple Development')."
            )
        }
        return Regex("([A-F0-9]{40})").find(match)?.value
            ?: error("could not parse identity hash from: $match")
    }

    private fun notarizeAndStaple(submitArtifact: File, stapleTarget: File, notaryJson: String) {
        val parsed = Regex("\"(issuer_id|key_id|private_key)\"\\s*:\\s*\"([^\"]+)\"")
            .findAll(notaryJson).associate { it.groupValues[1] to it.groupValues[2] }
        val issuer = parsed["issuer_id"] ?: error("notary JSON missing issuer_id")
        val keyId = parsed["key_id"] ?: error("notary JSON missing key_id")
        val privateKey = parsed["private_key"] ?: error("notary JSON missing private_key")

        val keyFile = temporaryDir.resolve("AuthKey_$keyId.p8")
        keyFile.writeText("-----BEGIN PRIVATE KEY-----\n$privateKey\n-----END PRIVATE KEY-----\n")

        // notarytool exits 0 even when the package is rejected — only the
        // status field tells us whether stapling will succeed. Use JSON
        // output so the parse is stable across Xcode releases.
        val submitOut = ByteArrayOutputStream()
        execOps.exec {
            commandLine(
                "xcrun", "notarytool", "submit", submitArtifact.absolutePath,
                "--key", keyFile.absolutePath,
                "--key-id", keyId,
                "--issuer", issuer,
                "--wait",
                "--output-format", "json",
            )
            standardOutput = submitOut
        }
        val output = submitOut.toString(Charsets.UTF_8)
        logger.lifecycle(output)

        // The two fields we need are simple strings at the top level; a
        // strict-JSON regex avoids pulling kotlinx.serialization in.
        val idRegex = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"")
        val statusRegex = Regex("\"status\"\\s*:\\s*\"([^\"]+)\"")
        val submissionId = idRegex.find(output)?.groupValues?.get(1)
        val status = statusRegex.find(output)?.groupValues?.get(1)

        if (status != "Accepted") {
            if (submissionId != null) {
                logger.error("Notary status=$status; fetching log for submission $submissionId")
                execOps.exec {
                    commandLine(
                        "xcrun", "notarytool", "log", submissionId,
                        "--key", keyFile.absolutePath,
                        "--key-id", keyId,
                        "--issuer", issuer,
                    )
                    isIgnoreExitValue = true
                }
            }
            keyFile.delete()
            error("Notary did not accept the package (status=$status). See log above for issues.")
        }

        execOps.exec { commandLine("xcrun", "stapler", "staple", stapleTarget.absolutePath) }
        keyFile.delete()
    }
}

tasks.register<MacTarTask>("macTar") {
    group = "distribution"
    description = "Build a macOS .tar.gz with a CLI wrapper around the signed + notarized .app."
    onlyIf { System.getProperty("os.name").lowercase().contains("mac") }
    dependsOn(appImageTask)
    appImage.set(appImageDir)
    appName.set("schema-sync")
    entitlementsFile.set(layout.projectDirectory.file("build-support/macos-entitlements.plist"))
    val ver = rootProject.version.toString().substringBefore("-SNAPSHOT")
    outputFile.set(installersDir.map { it.file("schema-sync-$ver-macos.tar.gz") })
}

// Convenience: builds whichever installer matches the current host OS.
tasks.register("installer") {
    group = "distribution"
    description = "Build the native installer for the current host OS."
    val osName = System.getProperty("os.name").lowercase()
    when {
        osName.contains("linux") -> dependsOn("linuxAppImage")
        osName.contains("windows") -> dependsOn("windowsZip")
        osName.contains("mac") -> dependsOn("macTar")
        else -> doFirst { error("Unsupported OS: $osName") }
    }
}
