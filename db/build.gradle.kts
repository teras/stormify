plugins {
    id("maven-publish")
    kotlin("multiplatform")
    id("org.jetbrains.kotlinx.atomicfu") version "0.30.0-beta"
}

group = parent?.group ?: IllegalStateException("Group is not defined")
version = parent?.version ?: IllegalStateException("Version is not defined")
description = "Stormify Database Library"

kotlin {
    jvm()
    linuxX64 {
//        binaries {
//            executable {
//                entryPoint = "onl.ycode.demo.main"
//            }
//        }
    }
    jvmToolchain(11)

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":logger"))
                implementation(project(":kdbc"))
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        val jvmMain by getting {
            dependencies {
                compileOnly("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
                compileOnly("com.ionspin.kotlin:bignum:0.3.10")
            }
        }

        val jvmTest by getting {
            dependencies {
                // HikariCP for connection pooling
                implementation("com.zaxxer:HikariCP:5.0.1")
                // SQLite JDBC driver
                implementation("org.xerial:sqlite-jdbc:3.42.0.0")
                // Optional: MySQL/MariaDB JDBC driver
                // implementation("com.mysql:mysql-connector-j:8.0.33")
                // Optional: PostgreSQL JDBC driver
                // implementation("org.postgresql:postgresql:42.6.0")
            }
        }

        val nativeMain by creating {
            dependsOn(commonMain)
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
                implementation("com.ionspin.kotlin:bignum:0.3.10")
            }
        }

        val linuxX64Main by getting {
            dependsOn(nativeMain)
        }

        val linuxX64Test by getting {
            dependencies {
                // SQLite native driver
                implementation(project(":kdbc-sqlite"))
                // Uncomment when ready to test other databases:
                // implementation(project(":kdbc-postgres"))
                // implementation(project(":kdbc-mariadb"))
            }
        }
    }
}

publishing {
    repositories {
        mavenLocal()
    }
}