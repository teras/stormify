#!/usr/bin/env bash
# Builds a GraalVM native-image executable from the JPA fat jar.
# Requires: GraalVM 21+ (with native-image in PATH).
#
# Usage:
#   sdk use java 21.0.11-graal       # or any GraalVM JDK
#   ./native-image-build.sh
#
# This produces a native binary under build/native/jpa-native-bench
# that supports the same modes as the JVM jar (prepare | bench-insert | bench-read).

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR="$SCRIPT_DIR/build/libs/jpa-jvm-bench.jar"

if [ ! -f "$JAR" ]; then
    echo "Fat jar not found: $JAR"
    echo "Run: gradle :jpa-jvm:shadowJar  (from benchmarks/)"
    exit 1
fi

if ! command -v native-image >/dev/null; then
    echo "native-image not found in PATH. Switch to a GraalVM JDK first."
    echo "Try: sdk use java 21.0.11-graal"
    exit 1
fi

OUT="$SCRIPT_DIR/build/native"
mkdir -p "$OUT"

# Hibernate uses heavy reflection; we feed metadata hints for entity classes.
# A complete Hibernate native-image build typically needs the Quarkus or Spring AOT
# pipeline; here we use --initialize-at-run-time for known problematic classes
# and -H:+UnlockExperimentalVMOptions to ease class-init order. For best results
# the user can collect a "native-image agent" trace by running the JAR under
# `-agentlib:native-image-agent=config-output-dir=...` first and then point
# native-image at that config via `-H:ConfigurationFileDirectories=...`.

native-image \
    -cp "$JAR" \
    --no-fallback \
    --initialize-at-build-time=org.slf4j,kotlin \
    --initialize-at-run-time=org.sqlite,org.postgresql,com.mysql,oracle,com.microsoft.sqlserver \
    -H:+ReportExceptionStackTraces \
    -H:Name=jpa-native-bench \
    -H:Path="$OUT" \
    -J-Xmx4g \
    bench.MainKt

echo
echo "Built: $OUT/jpa-native-bench"
