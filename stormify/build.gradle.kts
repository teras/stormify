import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform

plugins {
    kotlin("multiplatform")
    id("com.android.library")
    id("org.jetbrains.kotlinx.atomicfu") version "0.30.0-beta"
    id("com.google.devtools.ksp") version "2.2.20-2.0.2"
    id("org.jetbrains.dokka")
    id("com.vanniktech.maven.publish")
}

group = parent?.group ?: IllegalStateException("Group is not defined")
version = parent?.version ?: IllegalStateException("Version is not defined")
description = "Stormify Database Library"

kotlin {
    applyDefaultHierarchyTemplate()
    jvm()
    androidTarget {
        publishLibraryVariants("release")
    }
    // Host-gated target declarations. Kotlin/Native itself cross-compiles
    // between hosts via bundled LLVM, but the kdbc C library needs host
    // system cross-compilers (mingw-w64, gcc-aarch64-linux-gnu). Rather than
    // install those on macOS runners, we split: Linux hosts declare
    // linux/mingw targets; macOS hosts declare apple targets. The two sets
    // are union-merged into a single root KMP module at publish time.
    val isMac = System.getProperty("os.name").startsWith("Mac")
    if (isMac) {
        macosArm64()
        macosX64()
        iosSimulatorArm64()
        iosArm64()
        iosX64()
    } else {
        linuxX64()
        linuxArm64()
        mingwX64()
    }
    
    targets.all {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    freeCompilerArgs.add("-Xannotation-default-target=param-property")
                    freeCompilerArgs.add("-Xexpect-actual-classes")
                }
            }
        }
    }
    // Target Java 8 bytecode for the published JVM artifact so consumers on older
    // JDKs can still load the library. All runtime deps (bignum 0.3.9, kotlinx-datetime
    // 0.7.1, kotlinx-coroutines 1.10.2, HikariCP 4.0.3) ship Java 8 bytecode.
    // Tests run on the toolchain JDK (11) which can load Java 8 class files fine.
    jvm {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
                }
            }
        }
    }
    jvmToolchain(8)

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":logger"))
                api(project(":kdbc"))
                // kotlinx.coroutines is used only by the optional suspend API in the
                // `onl.ycode.stormify.coroutines` subpackage. Marked compileOnly so
                // that consumers who only use the blocking API never pull it as a
                // transitive dependency. Consumers who use the suspend API must add
                // kotlinx.coroutines-core themselves to their build.
                compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                // Suspend API tests use real coroutines at runtime (unlike main code which
                // only compile-references them via compileOnly).
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
            }
        }

        // Common JVM-based source set for both Desktop JVM and Android
        val jvmBasedMain by creating {
            dependsOn(commonMain)
            dependencies {
                compileOnly("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
                compileOnly("com.ionspin.kotlin:bignum:0.3.9")
                // kotlin-reflect powers the reflection-based entity discovery path
                // (tryReflection). Consumers using only annproc can exclude it.
                implementation(kotlin("reflect"))
            }
        }

        val jvmMain by getting {
            dependsOn(jvmBasedMain)
        }

        val androidMain by getting {
            dependsOn(jvmBasedMain)
        }

        // Common JVM-based test source set for both Desktop JVM and Android
        val jvmBasedTest by creating {
            dependsOn(commonTest)
            dependsOn(jvmBasedMain)
            dependencies {
                implementation(kotlin("reflect"))
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
                implementation("com.ionspin.kotlin:bignum:0.3.9")
            }
        }

        val jvmTest by getting {
            dependsOn(jvmBasedTest)
            // Default Kotlin hierarchy does NOT wire jvmTest → jvmMain; this makes
            // any expect/actual whose JVM `actual` lives in jvmMain invisible during
            // test compile. Explicit dependency fixes it.
            dependsOn(jvmMain)
            dependencies {
                implementation("com.zaxxer:HikariCP:4.0.3")
                // Load JDBC driver based on target database
                val testDb = System.getProperty("stormify.test.db") ?: "sqlite"
                when {
                    testDb.startsWith("mysql") -> implementation("com.mysql:mysql-connector-j:9.2.0")
                    testDb.startsWith("mariadb") -> implementation("org.mariadb.jdbc:mariadb-java-client:3.5.3")
                    testDb.startsWith("postgresql") -> implementation("org.postgresql:postgresql:42.7.5")
                    testDb == "oracle11" -> implementation("com.oracle.database.jdbc:ojdbc8:19.24.0.0")
                    testDb.startsWith("oracle") -> implementation("com.oracle.database.jdbc:ojdbc8:21.9.0.0")
                    testDb.startsWith("mssql") -> implementation("com.microsoft.sqlserver:mssql-jdbc:12.8.1.jre8")
                    testDb == "spring-jdbc" -> {
                        implementation("org.xerial:sqlite-jdbc:3.47.2.0")
                        implementation("org.springframework:spring-jdbc:5.3.39")
                    }
                    else -> implementation("org.xerial:sqlite-jdbc:3.47.2.0")
                }
            }
        }

        val androidUnitTest by getting {
            dependsOn(jvmBasedTest)
            // AGP's default Kotlin source set hierarchy does NOT make androidUnitTest
            // see androidMain, so any expect/actual whose Android `actual` lives in
            // androidMain is invisible during unit-test compile. Explicit dependency
            // fixes it.
            dependsOn(androidMain)
            dependencies {
                implementation("org.robolectric:robolectric:4.14.1")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
            }
        }

        val nativeMain by getting {
            dependencies {
                api("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
                api("com.ionspin.kotlin:bignum:0.3.9")
                // Kotlin/Native does not support compileOnly dependencies — the klib
                // compilation pipeline requires every referenced symbol to be present.
                // commonMain keeps `compileOnly` so JVM/Android consumers who only use
                // the blocking API don't pull coroutines; native consumers get it via api.
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            }
        }

        // Linux-host source sets — see note in kotlin{} about conditional target registration.
        if (System.getProperty("os.name").startsWith("Linux")) {
            val linuxX64Main by getting

            val linuxX64Test by getting {
                dependencies {
                    implementation(project(":kdbc"))
                }
            }

            val mingwX64Test by getting {
                dependencies {
                    implementation(project(":kdbc"))
                }
            }

            val linuxArm64Test by getting {
                // Share test sources with linuxX64Test — identical POSIX APIs and paths
                kotlin.srcDir("src/linuxX64Test/kotlin")
                dependencies {
                    implementation(project(":kdbc"))
                }
            }
        }

        // Apple targets - build enabled on macOS only
        if (System.getProperty("os.name").startsWith("Mac")) {
            val appleMain by getting {
                dependencies {
                    implementation(project(":kdbc"))
                }
            }

            // Apple test source sets share the same POSIX test sources as linuxX64.
            // Only non-deprecated targets get test infrastructure.
            val macosArm64Test by getting {
                kotlin.srcDir("src/linuxX64Test/kotlin")
                dependencies { implementation(project(":kdbc")) }
            }
            val iosSimulatorArm64Test by getting {
                kotlin.srcDir("src/linuxX64Test/kotlin")
                dependencies { implementation(project(":kdbc")) }
            }
            val iosArm64Test by getting {
                kotlin.srcDir("src/linuxX64Test/kotlin")
                dependencies { implementation(project(":kdbc")) }
            }
        }
    }
}

// Align Java compile tasks with the Kotlin JVM 1.8 target.
tasks.withType<JavaCompile>().configureEach {
    sourceCompatibility = "1.8"
    targetCompatibility = "1.8"
}

android {
    namespace = "onl.ycode.stormify"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Robolectric requires Java 11+; the library targets Java 8 via
            // jvmToolchain(8), so override only the test JVM.
            it.javaLauncher.set(javaToolchains.launcherFor {
                languageVersion.set(JavaLanguageVersion.of(17))
            })
            // Run only the Robolectric wrappers (test.Android*) — the common
            // tests are exercised through them; running them directly without
            // Robolectric would hit Android's stub classes.
            it.filter { includeTestsMatching("test.Android*") }
        }
    }
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    // Empty javadoc jar — Dokka would require compiling ALL target klibs (including
    // linuxArm64) even when publishing only apple targets, which fails on macOS
    // runners where aarch64-linux-gnu-gcc is unavailable. KDoc lives in the
    // sources jar anyway; Maven Central only requires that *some* javadoc jar exists.
    configure(KotlinMultiplatform(javadocJar = JavadocJar.Empty()))
    coordinates(group.toString(), "stormify", version.toString())
    pom {
        name.set("Stormify")
        description.set(project.description)
        url.set(rootProject.extra["pomUrl"] as String)
        inceptionYear.set(rootProject.extra["pomInceptionYear"] as String)
        licenses { license { name.set(rootProject.extra["pomLicenseName"] as String); url.set(rootProject.extra["pomLicenseUrl"] as String) } }
        developers { developer { id.set(rootProject.extra["pomDeveloperId"] as String); name.set(rootProject.extra["pomDeveloperName"] as String); email.set(rootProject.extra["pomDeveloperEmail"] as String) } }
        scm { url.set(rootProject.extra["pomScmUrl"] as String); connection.set(rootProject.extra["pomScmConnection"] as String); developerConnection.set(rootProject.extra["pomScmDevConnection"] as String) }
    }
}

tasks.withType<Test> {
    val testDb = System.getProperty("stormify.test.db") ?: "sqlite"
    systemProperty("stormify.test.db", testDb)
    systemProperty("stormify.test.config", System.getProperty("stormify.test.config") ?: "")
    // Oracle 11g timezone tables may not know the host's timezone region
    systemProperty("oracle.jdbc.timezoneAsRegion", "false")
}

// Per-target annproc registration (not kspCommonMainMetadata): the generated Paths
// emit @JvmField / @get:JvmName which are @OptionalExpectation in kotlin.jvm and
// cannot be referenced from non-JVM source sets.
val isMacHost = System.getProperty("os.name").startsWith("Mac")
val isLinuxHost = System.getProperty("os.name").startsWith("Linux")

dependencies {
    add("kspJvmTest", project(":annproc"))
    add("kspAndroidTestDebug", project(":annproc"))
    if (isLinuxHost) {
        add("kspLinuxX64Test", project(":annproc"))
        add("kspMingwX64Test", project(":annproc"))
        // linuxArm64Test reuses linuxX64 KSP output — no separate KSP run needed
    }
    if (isMacHost) {
        add("kspMacosArm64Test", project(":annproc"))
        add("kspIosSimulatorArm64Test", project(":annproc"))
        add("kspIosArm64Test", project(":annproc"))
    }
}

// Make the KSP-generated sources visible to the test source sets.
kotlin.sourceSets.named("jvmTest") {
    kotlin.srcDir("build/generated/ksp/jvm/jvmTest/kotlin")
}
if (isLinuxHost) {
    kotlin.sourceSets.named("linuxX64Test") {
        kotlin.srcDir("build/generated/ksp/linuxX64/linuxX64Test/kotlin")
    }
    kotlin.sourceSets.named("mingwX64Test") {
        kotlin.srcDir("build/generated/ksp/mingwX64/mingwX64Test/kotlin")
    }
    // linuxArm64Test reuses linuxX64's KSP output — same entities, same generated code
    kotlin.sourceSets.named("linuxArm64Test") {
        kotlin.srcDir("build/generated/ksp/linuxX64/linuxX64Test/kotlin")
    }
}
if (isMacHost) {
    for (target in listOf("MacosArm64", "IosSimulatorArm64", "IosArm64")) {
        val lower = target.replaceFirstChar { it.lowercase() }
        kotlin.sourceSets.named("${lower}Test") {
            kotlin.srcDir("build/generated/ksp/$lower/${lower}Test/kotlin")
        }
        tasks.matching { it.name == "compileTestKotlin$target" }.configureEach {
            dependsOn("kspTestKotlin$target")
        }
    }
}
kotlin.sourceSets.named("androidUnitTest") {
    kotlin.srcDir("build/generated/ksp/android/androidDebugUnitTest/kotlin")
}

tasks.matching { it.name == "compileTestKotlinJvm" }.configureEach {
    dependsOn("kspTestKotlinJvm")
}
if (isLinuxHost) {
    tasks.matching { it.name == "compileTestKotlinLinuxX64" }.configureEach {
        dependsOn("kspTestKotlinLinuxX64")
    }
    tasks.matching { it.name == "compileTestKotlinMingwX64" }.configureEach {
        dependsOn("kspTestKotlinMingwX64")
    }
    tasks.matching { it.name == "compileTestKotlinLinuxArm64" }.configureEach {
        dependsOn("kspTestKotlinLinuxX64")
    }
}

// Copy platform-specific runtime libraries (DLLs, .so files) next to test
// binaries so that dlopen / LoadLibrary finds them when running tests.
// The source directory is kdbc/src/c/libs/<platform> where <platform> maps
// to the Kotlin/Native target name (e.g. mingwX64 → mingw, linuxArm64 → arm64).
// If the directory is empty or missing, the copy is a no-op.
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
    tasks.matching { it.name == "${targetName}Test" || it.name == "linkDebugTest${targetName.replaceFirstChar { it.uppercase() }}" }.configureEach {
        dependsOn(copyTask)
    }
}
tasks.matching { it.name == "compileDebugUnitTestKotlinAndroid" }.configureEach {
    dependsOn("kspDebugUnitTestKotlinAndroid")
}