plugins {
    kotlin("multiplatform")
    id("com.google.devtools.ksp")
}

repositories {
    mavenLocal()
    mavenCentral()
}

kotlin {
    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach {
        it.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    jvmToolchain(11)

    sourceSets {
        commonMain.dependencies {
            implementation("onl.ycode:stormify:2.0.0")
        }
        // iosArm64 reuses iosSimulatorArm64 sources and KSP output
        val iosArm64Main by getting {
            kotlin.srcDir("build/generated/ksp/iosSimulatorArm64/iosSimulatorArm64Main/kotlin")
        }
    }
}

dependencies {
    add("kspIosSimulatorArm64", "onl.ycode:annproc:2.0.0")
}

tasks.matching { it.name == "compileKotlinIosArm64" }.configureEach {
    dependsOn("kspKotlinIosSimulatorArm64")
}

tasks.named("clean") {
    doLast {
        delete("build/kspCaches")
    }
}
