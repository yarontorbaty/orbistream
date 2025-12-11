#!/bin/bash
# Build script for OrbiStream native libraries
# This script rebuilds the GStreamer-based native streaming code

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Use Java 17 for compatibility (Java 25 not supported by Gradle)
if /usr/libexec/java_home -v 17 >/dev/null 2>&1; then
    export JAVA_HOME=$(/usr/libexec/java_home -v 17)
    echo "Using Java 17: $JAVA_HOME"
fi

# Configuration
NDK_PATH="/opt/homebrew/share/android-commandlinetools/ndk/26.1.10909125"
JNI_DIR="app/src/main/jni"
APP_ABI="arm64-v8a"
GSTREAMER_DIR="$SCRIPT_DIR/gstreamer-android"

echo "========================================="
echo "  OrbiStream Native Build"
echo "========================================="
echo "Project: $SCRIPT_DIR"
echo "GStreamer: $GSTREAMER_DIR"
echo "NDK: $NDK_PATH"
echo "Target ABI: $APP_ABI"
echo "========================================="

# Check NDK exists
if [ ! -f "$NDK_PATH/ndk-build" ]; then
    echo "ERROR: NDK not found at $NDK_PATH"
    echo "Please update NDK_PATH in this script"
    exit 1
fi

# Check GStreamer SDK exists
if [ ! -d "$GSTREAMER_DIR" ]; then
    echo "ERROR: GStreamer SDK not found at $GSTREAMER_DIR"
    exit 1
fi

# Clean option
if [ "$1" = "clean" ]; then
    echo "Cleaning..."
    rm -rf app/src/main/libs
    rm -rf app/src/main/jni/libs
    rm -rf app/src/main/jni/obj
    rm -rf app/src/main/jni/gst-android-build
    echo "Clean complete"
    exit 0
fi

# Build
echo ""
echo "Building native libraries..."
"$NDK_PATH/ndk-build" \
    -C "$JNI_DIR" \
    NDK_PROJECT_PATH=. \
    APP_BUILD_SCRIPT=Android.mk \
    NDK_APPLICATION_MK=Application.mk \
    APP_ABI="$APP_ABI" \
    -j8

# Copy libs to where Gradle expects them (src/main/libs)
echo ""
echo "Copying libraries to Gradle source directory..."
mkdir -p "app/src/main/libs/$APP_ABI"
cp -f "app/src/main/jni/libs/$APP_ABI/"*.so "app/src/main/libs/$APP_ABI/"

echo ""
echo "========================================="
echo "  Build Complete!"
echo "========================================="

# Show built libraries
if [ -d "app/src/main/libs/$APP_ABI" ]; then
    echo "Built libraries:"
    ls -la "app/src/main/libs/$APP_ABI/"*.so 2>/dev/null || echo "  (no .so files found)"
fi

echo ""
echo "Next: Rebuild the APK in Android Studio or run: ./build-apk.sh"
