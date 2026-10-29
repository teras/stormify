plugins {
    kotlin("multiplatform")
    id("com.android.library")
    id("com.google.devtools.ksp") version "2.2.21-2.0.5"
}

group = parent?.group ?: error("Group is not defined")
version = parent?.version ?: error("Version is not defined")

val isLinuxHost = System.getProperty("os.name").startsWith("Linux")
val isMacHost = System.getProperty("os.name").startsWith("Mac")

kotlin {
    applyDefaultHierarchyTemplate()
    jvm()
    androidTarget()
    if (isLinuxHost) {
        linuxX64()
        linuxArm64()
        mingwX64()
    }
    if (isMacHost) {
        macosArm64()
        macosX64()
        iosArm64()
        iosX64()
        iosSimulatorArm64()
    }

    targets.all {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    freeCompilerArgs.add("-Xexpect-actual-classes")
                }
            }
        }
    }

    jvmToolchain(8)

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":stormify"))
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
                implementation("com.ionspin.kotlin:bignum:0.3.9")
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(project(":logger"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
            }
        }

        // Tests that need java.time / kotlin-reflect run on both JVM and Android
        // (via Robolectric). This source set sits between commonTest and the two
        // platform test sets so the same code compiles into both jvmTest and
        // androidUnitTest classpaths.
        val jvmBasedTest by creating {
            dependsOn(commonTest)
            dependencies {
                implementation(kotlin("reflect"))
                implementation("org.jetbrains.kotlinx:atomicfu:0.32.1")
                implementation("javax.persistence:javax.persistence-api:2.2")
            }
        }

        val jvmTest by getting {
            dependsOn(jvmBasedTest)
            dependencies {
                implementation("com.zaxxer:HikariCP:4.0.3")
                val testDb = System.getProperty("stormify.test.db") ?: "sqlite"
                when {
                    testDb.startsWith("mysql") -> implementation("com.mysql:mysql-connector-j:9.2.0")
                    testDb.startsWith("mariadb") -> implementation("org.mariadb.jdbc:mariadb-java-client:3.5.3")
                    testDb.startsWith("postgresql") -> implementation("org.postgresql:postgresql:42.7.5")
                    testDb == "oracle11" -> implementation("com.oracle.database.jdbc:ojdbc8:19.24.0.0")
                    testDb.startsWith("oracle") -> implementation("com.oracle.database.jdbc:ojdbc8:21.9.0.0")
                    testDb.startsWith("mssql") -> implementation("com.microsoft.sqlserver:mssql-jdbc:12.8.1.jre8")
                    else -> implementation("org.xerial:sqlite-jdbc:3.47.2.0")
                }
            }
        }

        val androidUnitTest by getting {
            dependsOn(jvmBasedTest)
            // Robolectric requires the Android `actual` for createTestDatabases, which lives
            // in androidMain. AGP's default Kotlin source set hierarchy doesn't wire
            // androidUnitTest → androidMain, so make it explicit.
            dependsOn(getByName("androidMain"))
            dependencies {
                implementation("org.robolectric:robolectric:4.14.1")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
            }
        }

        if (isLinuxHost) {
            // linuxArm64 reuses linuxX64's main source (POSIX-identical) and KSP output.
            val linuxArm64Main by getting {
                kotlin.srcDir("src/linuxX64Main/kotlin")
            }
            val linuxArm64Test by getting {
                kotlin.srcDir("src/linuxX64Test/kotlin")
            }
        }
        if (isMacHost) {
            // Apple targets share the linuxX64 POSIX test factory (same getenv/unlink calls).
            val macosArm64Test by getting {
                kotlin.srcDir("src/linuxX64Test/kotlin")
            }
            val macosX64Test by getting {
                kotlin.srcDir("src/linuxX64Test/kotlin")
            }
            val iosArm64Test by getting {
                kotlin.srcDir("src/linuxX64Test/kotlin")
            }
            val iosX64Test by getting {
                kotlin.srcDir("src/linuxX64Test/kotlin")
            }
            val iosSimulatorArm64Test by getting {
                kotlin.srcDir("src/linuxX64Test/kotlin")
            }
        }
    }
}

android {
    namespace = "onl.ycode.stormify.conformance"
    compileSdk = 34
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.javaLauncher.set(javaToolchains.launcherFor {
                languageVersion.set(JavaLanguageVersion.of(11))
            })
            it.filter { includeTestsMatching("test.Android*") }
        }
    }
}

// Single annproc run via kspCommonMainMetadata: entities live in commonMain, the
// generated Paths/GeneratedEntities go into the metadata source set and are visible
// from every target's main compilation. JVM-only annotations (@JvmField, @JvmName)
// are @OptionalExpectation in kotlin.jvm — silently ignored on non-JVM targets.
dependencies {
    add("kspCommonMainMetadata", project(":annproc"))
}

kotlin.sourceSets.named("commonMain") {
    kotlin.srcDir("build/generated/ksp/metadata/commonMain/kotlin")
}

// Every Kotlin compile task in this module (main, test, Android variants) needs
// the metadata-generated entity classes (Tables, GeneratedEntities) on its
// classpath. Match anything that compiles Kotlin so test compilations pick up
// the KSP outputs as well — without this, compileTestKotlin* would race the
// generator and fail with "Unresolved reference 'Tables'".
tasks.matching {
    it.name.startsWith("compile") && it.name.contains("Kotlin")
}.configureEach {
    if (name != "kspCommonMainKotlinMetadata") dependsOn("kspCommonMainKotlinMetadata")
}

// KSP's up-to-date tracking can report this task UP-TO-DATE while its output
// directory is empty (observed after the Android KSP tasks run in a previous
// build), which then fails every Kotlin compile with "Unresolved reference
// 'Tables'". The task is fast (~1s) — always re-execute it so the generated
// entities are guaranteed to be present when a compile task needs them.
tasks.matching { it.name == "kspCommonMainKotlinMetadata" }.configureEach {
    outputs.upToDateWhen { false }
}

// SLF4J jars that the logger backend detection test loads into isolated classloaders,
// one combination at a time. They are not on any test classpath: a binding there
// would switch the logging of every other test to SLF4J.
// One configuration per SLF4J version, so conflict resolution keeps both.
val slf4jProbe = listOf("1.7.36", "2.0.17").map { version ->
    configurations.create("slf4jProbe${version.replace(".", "")}") { isTransitive = false }.also { conf ->
        for (artifact in listOf("slf4j-api", "slf4j-simple", "slf4j-nop"))
            dependencies.add(conf.name, "org.slf4j:$artifact:$version")
    }
}

tasks.withType<Test> {
    val probeJars = files(slf4jProbe)
    inputs.files(probeJars)
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-Dstormify.test.slf4jJars=" + probeJars.files.joinToString(File.pathSeparator))
    })
    // ActiveTxRegistryLeakTest reads Thread.threadLocals, closed to reflection from Java 9.
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        if (javaLauncher.get().metadata.languageVersion.asInt() >= 9)
            listOf("--add-opens", "java.base/java.lang=ALL-UNNAMED")
        else emptyList()
    })
    val testDb = System.getProperty("stormify.test.db") ?: "sqlite"
    systemProperty("stormify.test.db", testDb)
    systemProperty("stormify.test.config", System.getProperty("stormify.test.config") ?: "")
    systemProperty("stormify.probe", System.getProperty("stormify.probe") ?: "")
    systemProperty("oracle.jdbc.timezoneAsRegion", "false")
}

// Copy mingwX64 runtime DLLs next to the test binary (kdbc native libs).
kotlin.targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
    val platformDir = when (name) {
        "mingwX64" -> "mingw"
        "linuxArm64" -> "arm64"
        else -> return@configureEach
    }
    val libsDir = project(":kdbc").file("src/c/libs/$platformDir")
    val targetName = name
    val copyTask = tasks.register<Copy>("copy${targetName.replaceFirstChar { it.uppercase() }}TestLibs") {
        from(libsDir)
        into(layout.buildDirectory.dir("bin/$targetName/debugTest"))
    }
    tasks.matching {
        it.name == "${targetName}Test" ||
        it.name == "linkDebugTest${targetName.replaceFirstChar { it.uppercase() }}"
    }.configureEach {
        dependsOn(copyTask)
    }
}
