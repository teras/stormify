// Native build helper tasks for distribution builds
// These tasks use Docker with Ubuntu 20.04 to ensure binary compatibility

val buildNativeImage by tasks.registering(Exec::class) {
    group = "native build"
    description = "Build Docker image for native compilation (Ubuntu 20.04, glibc 2.31)"

    commandLine("docker", "build", "-f", "docker/Dockerfile", "-t", "stormify-native-builder", ".")
}

val buildNativeDistribution by tasks.registering(Exec::class) {
    group = "native build"
    description = "Build native binaries for distribution (Ubuntu 20.04 environment)"

    dependsOn(buildNativeImage)

    commandLine("./docker/build-native.sh", "build")

    doLast {
        println("\n✅ Native distribution binaries built successfully!")
        println("Location: stormify/build/bin/linuxX64/")
        println("Compatible with: glibc 2.31+ (Ubuntu 20.04+, Debian 11+, RHEL 9+)")
    }
}

val testNative by tasks.registering(Exec::class) {
    group = "native build"
    description = "Run native tests in Docker container"

    dependsOn(buildNativeImage)

    commandLine("./docker/build-native.sh", "test")
}

val cleanNative by tasks.registering(Exec::class) {
    group = "native build"
    description = "Clean native build artifacts"

    commandLine("./docker/build-native.sh", "clean")
}

val nativeShell by tasks.registering(Exec::class) {
    group = "native build"
    description = "Open interactive shell in native build container"

    commandLine("./docker/build-native.sh", "shell")
    standardInput = System.`in`
}
