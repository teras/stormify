#!/bin/bash
# Fast and safe Kotlin/Native build script using Docker + Ubuntu 20.04
#
# Benefits:
# - Builds with glibc 2.31 (runs on all Linux from 2020+)
# - No permission issues (uses non-root builder user)
# - Cached dependencies (Gradle cache persisted)
# - Fast incremental builds
#
# Usage:
#   ./build-native.sh              # Build and run tests (default)
#   ./build-native.sh test         # Build and run tests
#   ./build-native.sh build        # Build only (compile and link)
#   ./build-native.sh clean        # Clean build artifacts
#   ./build-native.sh shell        # Open shell in build container

set -e

# Configuration
IMAGE_NAME="stormify-native-builder"
GRADLE_CACHE_DIR="${HOME}/.gradle-native-cache"

# Parse command (default: test)
COMMAND="${1:-test}"

# Ensure Gradle cache directory exists with correct permissions
if [ ! -d "$GRADLE_CACHE_DIR" ]; then
    echo "Creating Gradle cache directory: $GRADLE_CACHE_DIR"
    mkdir -p "$GRADLE_CACHE_DIR"
fi

# Check if Docker image exists, build if not
if ! docker image inspect $IMAGE_NAME >/dev/null 2>&1; then
    echo "============================================"
    echo "Building Docker image (first time only)..."
    echo "============================================"
    docker build -f docker/Dockerfile -t $IMAGE_NAME .
    echo ""
fi

# Docker run common options
DOCKER_RUN="docker run --rm \
    -v $(pwd):/workspace \
    -v $GRADLE_CACHE_DIR:/home/builder/.gradle \
    -w /workspace \
    $IMAGE_NAME"

case "$COMMAND" in
    test)
        echo "============================================"
        echo "Building and running native tests..."
        echo "============================================"
        $DOCKER_RUN gradle :db:linuxX64Test --console=plain
        echo ""
        echo "✅ Tests complete!"
        ;;

    build)
        echo "============================================"
        echo "Building native test binary..."
        echo "============================================"
        $DOCKER_RUN gradle :db:linkDebugTestLinuxX64 --console=plain
        echo ""
        echo "✅ Build complete!"
        echo "Binary: db/build/bin/linuxX64/debugTest/test.kexe"
        ;;

    clean)
        echo "Cleaning build artifacts..."
        $DOCKER_RUN gradle clean
        echo "✅ Clean complete!"
        ;;

    shell)
        echo "Opening shell in build container..."
        docker run --rm -it \
            -v $(pwd):/workspace \
            -v $GRADLE_CACHE_DIR:/home/builder/.gradle \
            -w /workspace \
            $IMAGE_NAME \
            /bin/bash
        exit 0
        ;;

    rebuild-image)
        echo "============================================"
        echo "Rebuilding Docker image..."
        echo "============================================"
        docker build --no-cache -f docker/Dockerfile -t $IMAGE_NAME .
        echo "✅ Image rebuilt!"
        ;;

    *)
        echo "Usage: $0 [COMMAND]"
        echo ""
        echo "Commands:"
        echo "  test          Build and run native tests (default)"
        echo "  build         Build native binary only"
        echo "  clean         Clean build artifacts"
        echo "  shell         Open shell in build container"
        echo "  rebuild-image Rebuild Docker image from scratch"
        echo ""
        echo "Features:"
        echo "  ✓ Fast incremental builds (Gradle cache persisted)"
        echo "  ✓ No permission issues (non-root builder)"
        echo "  ✓ Compatible binaries (glibc 2.31+)"
        exit 1
        ;;
esac

echo ""
echo "Runtime requirements:"
echo "  - glibc >= 2.31 (Ubuntu 20.04+, Debian 11+, RHEL 9+)"
echo "  - Database client libraries (libsqlite3, libpq, libmariadb, Oracle IC)"
