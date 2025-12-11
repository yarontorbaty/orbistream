#!/bin/bash
# Install OrbiStream APK to connected device/emulator

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APK_PATH="$SCRIPT_DIR/app/build/outputs/apk/debug/app-debug.apk"
ADB="/opt/homebrew/share/android-commandlinetools/platform-tools/adb"

# Check if adb exists
if [ ! -f "$ADB" ]; then
    # Try system adb
    if command -v adb &> /dev/null; then
        ADB="adb"
    else
        echo "ERROR: adb not found"
        echo "Expected at: /opt/homebrew/share/android-commandlinetools/platform-tools/adb"
        exit 1
    fi
fi

# Check if APK exists
if [ ! -f "$APK_PATH" ]; then
    echo "ERROR: APK not found at $APK_PATH"
    echo "Run ./build-apk.sh first"
    exit 1
fi

echo "========================================="
echo "  Installing OrbiStream"
echo "========================================="

# Get list of online devices
DEVICES=$("$ADB" devices | grep -v "List\|offline\|^$" | awk '{print $1}')
DEVICE_COUNT=$(echo "$DEVICES" | grep -c . || true)

if [ "$DEVICE_COUNT" -eq 0 ]; then
    echo "ERROR: No devices connected"
    echo "Connect a device or start an emulator"
    exit 1
elif [ "$DEVICE_COUNT" -eq 1 ]; then
    DEVICE="$DEVICES"
    echo "Device: $DEVICE"
else
    echo "Multiple devices found:"
    echo "$DEVICES" | nl
    echo ""
    read -p "Enter device number (1-$DEVICE_COUNT): " CHOICE
    DEVICE=$(echo "$DEVICES" | sed -n "${CHOICE}p")
    if [ -z "$DEVICE" ]; then
        echo "Invalid selection"
        exit 1
    fi
fi

echo "Installing to: $DEVICE"
"$ADB" -s "$DEVICE" install -r "$APK_PATH"

echo ""
echo "========================================="
echo "  Installation Complete!"
echo "========================================="
echo ""
echo "To launch the app:"
echo "  $ADB -s $DEVICE shell am start -n com.orbistream/.ui.MainActivity"

