plugins {
    id("maven-publish")
    kotlin("multiplatform")
}

group = parent?.group ?: IllegalStateException("Group is not defined")
version = parent?.version ?: IllegalStateException("Version is not defined")
description = "SQLite Driver for Kotlin Database Connectivity"

kotlin {
    applyDefaultHierarchyTemplate()
    
    linuxX64 {
        compilations.getByName("main") {
            cinterops {
                val sqlite3 by creating {
                    defFile(project.file("src/nativeInterop/cinterop/sqlite3.def"))
                }
            }
        }
    }
    
    // Apple targets - build enabled on macOS only
    if (org.gradle.internal.os.OperatingSystem.current().isMacOsX) {
        // iOS targets
        iosArm64 {
            compilations.getByName("main") {
                cinterops {
                    val sqlite3 by creating {
                        defFile(project.file("src/nativeInterop/cinterop/sqlite3.def"))
                    }
                }
            }
        }
        
        iosX64 {
            compilations.getByName("main") {
                cinterops {
                    val sqlite3 by creating {
                        defFile(project.file("src/nativeInterop/cinterop/sqlite3.def"))
                    }
                }
            }
        }
        
        iosSimulatorArm64 {
            compilations.getByName("main") {
                cinterops {
                    val sqlite3 by creating {
                        defFile(project.file("src/nativeInterop/cinterop/sqlite3.def"))
                    }
                }
            }
        }
        
        // macOS targets
        macosArm64 {
            compilations.getByName("main") {
                cinterops {
                    val sqlite3 by creating {
                        defFile(project.file("src/nativeInterop/cinterop/sqlite3.def"))
                    }
                }
            }
        }
        
        macosX64 {
            compilations.getByName("main") {
                cinterops {
                    val sqlite3 by creating {
                        defFile(project.file("src/nativeInterop/cinterop/sqlite3.def"))
                    }
                }
            }
        }
    }
    
    jvmToolchain(11)

    sourceSets {
        commonMain.dependencies {
            implementation(project(":kdbc"))
        }

        nativeMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
            implementation("com.ionspin.kotlin:bignum:0.3.10")
        }
    }
}

publishing {
    repositories {
        mavenLocal()
    }
}
