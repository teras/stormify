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
                languageVersion.set(JavaLanguageVersion.of(17))
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

tasks.matching {
    it.name.startsWith("compileKotlin") || it.name.startsWith("compile") && it.name.endsWith("KotlinAndroid")
}.configureEach {
    if (name != "kspCommonMainKotlinMetadata") dependsOn("kspCommonMainKotlinMetadata")
}

tasks.withType<Test> {
    val testDb = System.getProperty("stormify.test.db") ?: "sqlite"
    systemProperty("stormify.test.db", testDb)
    systemProperty("stormify.test.config", System.getProperty("stormify.test.config") ?: "")
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
