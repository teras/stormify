plugins {
    id("maven-publish")
    kotlin("multiplatform")
    id("com.android.library")
    id("org.jetbrains.kotlinx.atomicfu") version "0.30.0-beta"
    id("com.google.devtools.ksp") version "2.2.20-2.0.2"
    id("org.jetbrains.dokka")
}

group = parent?.group ?: IllegalStateException("Group is not defined")
version = parent?.version ?: IllegalStateException("Version is not defined")
description = "Stormify Database Library"

kotlin {
    applyDefaultHierarchyTemplate()
    jvm()
    androidTarget {
        publishLibraryVariants("release", "debug")
    }
    linuxX64()
    
    // Apple targets - build enabled on macOS only
    if (org.gradle.internal.os.OperatingSystem.current().isMacOsX) {
        // iOS
        iosArm64()
        iosX64()
        iosSimulatorArm64()
        
        // macOS
        macosArm64()
        macosX64()
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

        val jvmTest by getting {
            dependencies {
                implementation(kotlin("reflect"))
                // Tests exercise the BigDecimal/BigInteger and kotlinx-datetime paths
                // which are compileOnly in jvmBasedMain — re-declare as runtime deps here.
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
                implementation("com.ionspin.kotlin:bignum:0.3.9")
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

        val linuxX64Main by getting

        val linuxX64Test by getting {
            dependencies {
                implementation(project(":kdbc"))
            }
        }
        
        // Apple targets - build enabled on macOS only
        if (org.gradle.internal.os.OperatingSystem.current().isMacOsX) {
            val appleMain by creating {
                dependsOn(nativeMain)
                dependencies {
                    implementation(project(":kdbc"))
                }
            }
            
            val iosMain by creating {
                dependsOn(appleMain)
            }
            
            val iosArm64Main by getting {
                dependsOn(iosMain)
            }
            
            val iosX64Main by getting {
                dependsOn(iosMain)
            }
            
            val iosSimulatorArm64Main by getting {
                dependsOn(iosMain)
            }
            
            val macosMain by creating {
                dependsOn(appleMain)
            }
            
            val macosArm64Main by getting {
                dependsOn(macosMain)
            }
            
            val macosX64Main by getting {
                dependsOn(macosMain)
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
}

publishing {
    repositories {
        mavenLocal()
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
dependencies {
    add("kspJvmTest", project(":annproc"))
    add("kspLinuxX64Test", project(":annproc"))
}

// Make the KSP-generated sources visible to the test source sets.
kotlin.sourceSets.named("jvmTest") {
    kotlin.srcDir("build/generated/ksp/jvm/jvmTest/kotlin")
}
kotlin.sourceSets.named("linuxX64Test") {
    kotlin.srcDir("build/generated/ksp/linuxX64/linuxX64Test/kotlin")
}

tasks.matching { it.name == "compileTestKotlinJvm" }.configureEach {
    dependsOn("kspTestKotlinJvm")
}
tasks.matching { it.name == "compileTestKotlinLinuxX64" }.configureEach {
    dependsOn("kspTestKotlinLinuxX64")
}