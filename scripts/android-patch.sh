#!/bin/bash

# Symlinks native plugin sources from resources/android into the Capacitor-managed
# android/ tree, then rewrites MainActivity.java to register each plugin.
# Safe to re-run after `npx cap sync` (which regenerates the android/ tree).

set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

PACKAGE_PATH="com/nembestil/pos3/app"
ANDROID_JAVA_DIR="$ROOT/android/app/src/main/java/$PACKAGE_PATH"
RESOURCES_DIR="$ROOT/resources/android"

if [ ! -d "$ANDROID_JAVA_DIR" ]; then
    echo "❌ Android source dir not found: $ANDROID_JAVA_DIR"
    echo "   Run \`npx cap sync\` first."
    exit 1
fi

if [ ! -d "$RESOURCES_DIR" ]; then
    echo "❌ Resources dir not found: $RESOURCES_DIR"
    exit 1
fi

# Remove any stale symlinks that point into resources/android but no longer exist
for link in "$ANDROID_JAVA_DIR"/*; do
    [ -L "$link" ] || continue
    target="$(readlink "$link")"
    case "$target" in
        *resources/android/*)
            if [ ! -e "$link" ]; then
                echo "🧹 Removing stale link: $(basename "$link")"
                rm "$link"
            fi
            ;;
    esac
done

# Symlink every .java file in resources/android into the package directory.
# Use relative symlinks so the tree remains valid if the project is moved.
PLUGINS=()
for src in "$RESOURCES_DIR"/*.java; do
    [ -f "$src" ] || continue
    base="$(basename "$src")"
    target="$ANDROID_JAVA_DIR/$base"
    rel="$(python3 -c 'import os,sys; print(os.path.relpath(sys.argv[1], sys.argv[2]))' "$src" "$ANDROID_JAVA_DIR")"
    ln -sfn "$rel" "$target"
    echo "🔗 Linked: android/.../$PACKAGE_PATH/$base -> $rel"

    if grep -q "@CapacitorPlugin" "$src"; then
        PLUGINS+=("${base%.java}")
    fi
done

# Build the registerPlugin(...) lines for MainActivity
REG_LINES=""
for p in "${PLUGINS[@]}"; do
    REG_LINES+="        registerPlugin($p.class);"$'\n'
done

MAIN_ACTIVITY="$ANDROID_JAVA_DIR/MainActivity.java"

cat > "$MAIN_ACTIVITY" <<EOF
package com.nembestil.pos3.app;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
${REG_LINES}        super.onCreate(savedInstanceState);
        BridgeReinjector.install(bridge);
    }
}
EOF

echo "🩹 Patched MainActivity.java (${#PLUGINS[@]} plugin(s) registered)"

# BridgeReinjector uses androidx.webkit (WebViewCompat / WebViewFeature). Capacitor
# declares it as `implementation`, so it isn't exposed to the app module — we must
# add it explicitly. Idempotent: only inserted if missing.
APP_BUILD_GRADLE="$ROOT/android/app/build.gradle"
if [ -f "$APP_BUILD_GRADLE" ] && ! grep -q "androidx.webkit:webkit" "$APP_BUILD_GRADLE"; then
    # Insert after the capacitor-android project dependency line (a stable anchor
    # that cap sync re-emits). Use a literal newline in the sed `a\` block.
    sed -i '' $'/implementation project(\':capacitor-android\')/a\\\n    implementation "androidx.webkit:webkit:$androidxWebkitVersion"\n' "$APP_BUILD_GRADLE"
    echo "🩹 Added androidx.webkit dependency to android/app/build.gradle"
fi
