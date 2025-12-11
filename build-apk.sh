#!/bin/bash
# Build APK for OrbiStream
# Uses Java 17 for compatibility (Java 25 not supported by Gradle)

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Use Java 17 for Gradle compatibility
if /usr/libexec/java_home -v 17 >/dev/null 2>&1; then
    export JAVA_HOME=$(/usr/libexec/java_home -v 17)
    echo "Using Java 17: $JAVA_HOME"
else
    echo "WARNING: Java 17 not found. Build may fail with Java 25."
fi

echo "========================================="
echo "  Building OrbiStream APK"
echo "========================================="

./gradlew assembleDebug

echo ""
echo "========================================="
echo "  Build Complete!"
echo "========================================="
echo "APK location: app/build/outputs/apk/debug/app-debug.apk"
echo ""
echo "To install on connected device:"
echo "  adb install -r app/build/outputs/apk/debug/app-debug.apk"

