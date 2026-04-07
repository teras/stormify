# Dockerfile for building Kotlin Native projects with Stormify
# Uses Ubuntu 20.04 LTS (glibc 2.31) - widely supported, long-term stable
#
# Strategy: Dynamic linking to database client libraries (same as Go/Rust)
# - Build with glibc 2.31 so binaries run on all modern Linux (2020+)
# - Allows Oracle support (no static libraries available)
# - Requires users to have database client libraries installed at runtime
# - Compatible with any system with glibc >= 2.31 (Ubuntu 20.04+, Debian 11+, RHEL 9+)
#
# This is the same approach used by:
# - Go's godror (Oracle driver)
# - Rust's diesel-oci (uses manylinux2014)
# - Python's cx_Oracle
#
# Ubuntu 20.04 LTS (glibc 2.31) provides the best balance of compatibility and support.

FROM ubuntu:20.04

# Prevent interactive prompts
ENV DEBIAN_FRONTEND=noninteractive

# Install build tools and database development libraries
# -dev packages include headers and .so files needed for compilation.
#
# Note: kdbc uses dlopen/dlsym to load database client libraries at runtime,
# so the .so files here are only needed for Kotlin/Native cinterop smoke
# tests. At runtime on the end-user machine, only the client libraries they
# actually use need to be present.
#
# Headers required for compilation of every driver:
#   libsqlite3-dev        -> sqlite3.h
#   libpq-dev             -> libpq-fe.h (lives under /usr/include/postgresql/)
#   libmariadb-dev        -> mysql/mysql.h
#   libmariadb-dev-compat -> legacy MySQL header compat
#   freetds-dev           -> ctpublic.h (MS SQL Server via FreeTDS ct-lib)
#   ODPI-C (installed below, not in apt) -> dpi.h (Oracle)
#
# pkg-config is used by kdbc/src/c/Makefile for portable include path
# discovery across distros.
RUN apt-get update && apt-get install -y \
    openjdk-11-jdk \
    wget \
    unzip \
    git \
    pkg-config \
    build-essential \
    libsqlite3-dev \
    libpq-dev \
    libmariadb-dev \
    libmariadb-dev-compat \
    freetds-dev \
    && rm -rf /var/lib/apt/lists/*

# Install ODPI-C header (Oracle Database Programming Interface for C).
# ODPI-C is not packaged in Ubuntu apt, so we fetch the single header from
# the upstream release tag. At runtime, dlopen will locate libodpic.so on
# the end-user system; the .so is not needed at build time.
# Upstream: https://github.com/oracle/odpi (Apache 2.0 license)
ARG ODPI_C_VERSION=v5.6.4
RUN git clone --depth 1 --branch ${ODPI_C_VERSION} https://github.com/oracle/odpi.git /tmp/odpi \
    && cp /tmp/odpi/include/dpi.h /usr/include/dpi.h \
    && rm -rf /tmp/odpi

# Create a non-root user for building (prevents permission issues)
# Use same UID/GID as typical Linux user (1000) for volume mounts
RUN groupadd -g 1000 builder && \
    useradd -m -u 1000 -g 1000 -s /bin/bash builder

# Switch to non-root user
USER builder

# Set JAVA_HOME
ENV JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
ENV PATH=$JAVA_HOME/bin:$PATH

# Install Gradle as root
USER root
RUN wget https://services.gradle.org/distributions/gradle-8.5-bin.zip -P /tmp \
    && unzip -d /opt/gradle /tmp/gradle-8.5-bin.zip \
    && rm /tmp/gradle-8.5-bin.zip \
    && chown -R builder:builder /opt/gradle

ENV GRADLE_HOME=/opt/gradle/gradle-8.5
ENV PATH=$GRADLE_HOME/bin:$PATH

# Switch back to builder user
USER builder

# Pre-download Kotlin Native dependencies as builder user
# This creates a temporary project that triggers K/N dependency download
# and caches them in /home/builder/.konan
RUN mkdir -p /home/builder/kn-init && cd /home/builder/kn-init && \
    echo 'plugins { kotlin("multiplatform") version "2.2.20" }' > build.gradle.kts && \
    echo 'repositories { mavenCentral() }' >> build.gradle.kts && \
    echo 'kotlin { linuxX64("native") }' >> build.gradle.kts && \
    echo 'rootProject.name = "kn-init"' > settings.gradle.kts && \
    mkdir -p src/nativeMain/kotlin && \
    echo 'fun main() = println("init")' > src/nativeMain/kotlin/Main.kt && \
    gradle build --no-daemon || true && \
    rm -rf /home/builder/kn-init

# Create working directory (mounted volume will go here)
WORKDIR /workspace

# Default command
CMD ["/bin/bash"]
