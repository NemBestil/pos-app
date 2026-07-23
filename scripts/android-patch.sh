#!/bin/bash

# Symlinks native plugin sources from resources/android into the Capacitor-managed
# android/ tree, then rewrites MainActivity.java to register each plugin.
# Safe to re-run after `npx cap sync` (which regenerates the android/ tree).

set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

PACKAGE_PATH="com/nembestil/pos3/app"
ANDROID_JAVA_DIR="$ROOT/android/app/src/main/java/$PACKAGE_PATH"
RESOURCES_DIR="$ROOT/resources/android"
ANDROID_RES_XML_DIR="$ROOT/android/app/src/main/res/xml"

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
        WebViewSentrySupport.install(bridge);
        BridgeReinjector.install(bridge);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            AndroidFullscreenPlugin.applyCurrentState(this);
        }
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

# WebViewSentrySupport records WebView navigation/console/error breadcrumbs through
# the Android SDK. The app module imports io.sentry directly, so keep the direct
# dependency explicit even though @sentry/capacitor also bundles it.
if [ -f "$APP_BUILD_GRADLE" ] && ! grep -q "io.sentry:sentry-android" "$APP_BUILD_GRADLE"; then
    sed -i '' $'/implementation "androidx.webkit:webkit:\\$androidxWebkitVersion"/a\\\n    implementation "io.sentry:sentry-android:8.35.0"\n' "$APP_BUILD_GRADLE"
    echo "🩹 Added Sentry Android dependency to android/app/build.gradle"
fi

# PaymentTerminalDiscoveryPlugin acquires a Wi-Fi multicast lock so the Wi-Fi
# stack doesn't filter out the Worldline broadcast frames. That needs an extra
# permission. Idempotent: only inserted if missing.
ANDROID_MANIFEST="$ROOT/android/app/src/main/AndroidManifest.xml"
if [ -f "$ANDROID_MANIFEST" ] && ! grep -q "CHANGE_WIFI_MULTICAST_STATE" "$ANDROID_MANIFEST"; then
    sed -i '' $'/<uses-permission android:name="android.permission.INTERNET" \\/>/a\\\n    <uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" \\/>\n' "$ANDROID_MANIFEST"
    echo "🩹 Added CHANGE_WIFI_MULTICAST_STATE permission to AndroidManifest.xml"
fi

# ForwarderService maintains connections to local printers and payment terminals,
# so it uses foregroundServiceType=connectedDevice. POST_NOTIFICATIONS is optional
# for notification-drawer visibility and is not a prerequisite for starting the
# service. The restart receiver restores a configured service after reboot/update.
# Permissions and components are patched idempotently via Python because
# multi-line XML insertions are brittle in BSD sed.
if [ -f "$ANDROID_MANIFEST" ]; then
    python3 - "$ANDROID_MANIFEST" <<'PY'
import sys, pathlib

path = pathlib.Path(sys.argv[1])
text = path.read_text()
changed = False

permissions = [
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE",
    "android.permission.RECEIVE_BOOT_COMPLETED",
    "android.permission.POST_NOTIFICATIONS",
]
anchor = '<uses-permission android:name="android.permission.INTERNET" />'
if anchor in text:
    for perm in permissions:
        line = f'<uses-permission android:name="{perm}" />'
        if line not in text:
            text = text.replace(anchor, anchor + "\n    " + line, 1)
            changed = True
            print(f"🩹 Added {perm} permission to AndroidManifest.xml")

legacy_data_sync_permission = (
    '    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />\n'
)
if legacy_data_sync_permission in text:
    text = text.replace(legacy_data_sync_permission, "")
    changed = True
    print("🩹 Removed FOREGROUND_SERVICE_DATA_SYNC permission from AndroidManifest.xml")

# Bluetooth printing (BluetoothPrinterPlugin + ForwarderService Bluetooth loop).
# BLUETOOTH_CONNECT is the runtime permission used from Android 12; the legacy
# BLUETOOTH/BLUETOOTH_ADMIN permissions only matter on API <= 30.
bluetooth_lines = [
    '<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />',
    '<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />',
    '<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />',
]
if anchor in text:
    for line in bluetooth_lines:
        if line not in text:
            text = text.replace(anchor, anchor + "\n    " + line, 1)
            changed = True
            print("🩹 Added Bluetooth permission to AndroidManifest.xml")

service_xml = (
    '        <service\n'
    '            android:name=".ForwarderService"\n'
    '            android:exported="false"\n'
    '            android:foregroundServiceType="connectedDevice" />\n'
)
if 'android:foregroundServiceType="dataSync"' in text:
    text = text.replace(
        'android:foregroundServiceType="dataSync"',
        'android:foregroundServiceType="connectedDevice"',
    )
    changed = True
    print("🩹 Changed ForwarderService type to connectedDevice in AndroidManifest.xml")
elif "ForwarderService" not in text and "</application>" in text:
    text = text.replace("</application>", service_xml + "    </application>", 1)
    changed = True
    print("🩹 Registered ForwarderService in AndroidManifest.xml")

receiver_xml = (
    '        <receiver\n'
    '            android:name=".ForwarderRestartReceiver"\n'
    '            android:enabled="true"\n'
    '            android:exported="false">\n'
    '            <intent-filter>\n'
    '                <action android:name="android.intent.action.BOOT_COMPLETED" />\n'
    '                <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />\n'
    '            </intent-filter>\n'
    '        </receiver>\n'
)
if "ForwarderRestartReceiver" not in text and "</application>" in text:
    text = text.replace("</application>", receiver_xml + "    </application>", 1)
    changed = True
    print("🩹 Registered ForwarderRestartReceiver in AndroidManifest.xml")

if changed:
    path.write_text(text)
PY
fi

# Symlink XML resources from resources/android/xml into the res/xml directory.
for src in "$RESOURCES_DIR/xml"/*.xml; do
    [ -f "$src" ] || continue
    base="$(basename "$src")"
    target="$ANDROID_RES_XML_DIR/$base"
    rel="$(python3 -c 'import os,sys; print(os.path.relpath(sys.argv[1], sys.argv[2]))' "$src" "$ANDROID_RES_XML_DIR")"
    ln -sfn "$rel" "$target"
    echo "🔗 Linked: android/.../res/xml/$base -> $rel"
done

# Symlink density-bucketed drawable resources (e.g. the notification small icon)
# from resources/android/drawable/drawable-<density> into the matching res/<density>
# directories. android/ is regenerated by `npx cap sync`, so these buckets must be
# (re)created and (re)linked here. Only *.png is globbed, so .DS_Store is ignored.
ANDROID_RES_DIR="$ROOT/android/app/src/main/res"
for densdir in "$RESOURCES_DIR/drawable"/drawable-*; do
    [ -d "$densdir" ] || continue
    densname="$(basename "$densdir")"
    target_dir="$ANDROID_RES_DIR/$densname"
    mkdir -p "$target_dir"
    for src in "$densdir"/*.png; do
        [ -f "$src" ] || continue
        base="$(basename "$src")"
        target="$target_dir/$base"
        rel="$(python3 -c 'import os,sys; print(os.path.relpath(sys.argv[1], sys.argv[2]))' "$src" "$target_dir")"
        ln -sfn "$rel" "$target"
        echo "🔗 Linked: android/.../res/$densname/$base -> $rel"
    done
done

# Allow cleartext HTTP for native and CapacitorHttp LAN-device connections.
# Idempotent: only inserted if the attribute is missing.
if [ -f "$ANDROID_MANIFEST" ] && ! grep -q "networkSecurityConfig" "$ANDROID_MANIFEST"; then
    sed -i '' 's|<application|<application android:networkSecurityConfig="@xml/network_security_config"|' "$ANDROID_MANIFEST"
    echo "🩹 Added networkSecurityConfig to AndroidManifest.xml"
fi
