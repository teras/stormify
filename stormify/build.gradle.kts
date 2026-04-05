plugins {
    id("maven-publish")
    kotlin("multiplatform")
    id("com.android.library")
    id("org.jetbrains.kotlinx.atomicfu") version "0.30.0-beta"
    id("com.google.devtools.ksp") version "2.2.20-2.0.2"
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
                }
            }
        }
    }
    jvmToolchain(11)

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":logger"))
                implementation(project(":kdbc"))
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
                compileOnly("com.ionspin.kotlin:bignum:0.3.10")
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
                implementation("com.zaxxer:HikariCP:4.0.3")
                // Load JDBC driver based on target database
                val testDb = System.getProperty("stormify.test.db") ?: "sqlite"
                when {
                    testDb.startsWith("mysql") -> implementation("com.mysql:mysql-connector-j:9.2.0")
                    testDb.startsWith("mariadb") -> implementation("org.mariadb.jdbc:mariadb-java-client:3.5.3")
                    testDb.startsWith("postgresql") -> implementation("org.postgresql:postgresql:42.7.5")
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
                api("com.ionspin.kotlin:bignum:0.3.10")
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

android {
    namespace = "onl.ycode.stormify"
    compileSdk = 34
    
    defaultConfig {
        minSdk = 21
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
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
}

// Run the annproc KSP processor against the commonTest entities so that native test targets
// (which lack reflection-based entity discovery) get a generated EntityRegistrar. The generated
// object is `db.stormify.GeneratedEntities` and is registered explicitly by the test factories.
dependencies {
    add("kspCommonMainMetadata", project(":annproc"))
    add("kspLinuxX64Test", project(":annproc"))
}

// Make the KSP-generated sources visible to the common test source set so entity classes
// can see the GeneratedEntities object on native targets.
kotlin.sourceSets.named("linuxX64Test") {
    kotlin.srcDir("build/generated/ksp/linuxX64/linuxX64Test/kotlin")
}

tasks.matching { it.name == "compileTestKotlinLinuxX64" }.configureEach {
    dependsOn("kspTestKotlinLinuxX64")
}