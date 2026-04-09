#!/bin/bash

# Exit on any failure
set -e

echo "🚀 Building assets (Nuxt generate)..."
npm run generate

echo "📲 Syncing with Capacitor..."
npx cap sync

# Function to get target ID from npx cap run android --list
get_target() {
    local type=$1
    if [ "$type" == "device" ]; then
        # Get real devices first (excluding emulators)
        adb devices | grep -v "List" | grep "device$" | grep -v "emulator-" | head -n 1 | awk '{print $1}'
    else
        # 1. Search for AVD names matching the type (look for "(emulator)" in name)
        local avd=$(npx cap run android --list | grep -i "$type" | grep "(emulator)" | head -n 1 | awk '{print $NF}')
        if [ -n "$avd" ]; then
            echo "$avd"
        else
            # 2. Fallback to any target matching the keyword (might be a running serial)
            npx cap run android --list | grep -i "$type" | head -n 1 | awk '{print $NF}'
        fi
    fi
}

MODE=$1
TARGET=""

if [ -n "$MODE" ]; then
    echo "🔍 Looking for target: $MODE..."
    TARGET=$(get_target "$MODE")
    if [ -z "$TARGET" ]; then
        echo "❌ Error: Could not find a target matching: $MODE"
        exit 1
    fi
else
    # Default auto-detection priority: 1. Real Device, 2. Tablet Emulator, 3. Phone Emulator
    echo "🔍 Auto-detecting target..."
    TARGET=$(get_target "device")
    if [ -n "$TARGET" ]; then
        echo "✅ Auto-detected real device: $TARGET"
    else
        TARGET=$(get_target "Tablet")
        if [ -n "$TARGET" ]; then
            echo "✅ Auto-detected tablet emulator: $TARGET"
        else
            TARGET=$(get_target "Phone")
            if [ -n "$TARGET" ]; then
                echo "✅ Auto-detected phone emulator: $TARGET"
            fi
        fi
    fi
fi

if [ -n "$TARGET" ]; then
    echo "🚀 Running on target: $TARGET..."
    npx cap run android --target "$TARGET"
else
    echo "⚠️ No specific target found. Opening selection menu..."
    npx cap run android
fi
