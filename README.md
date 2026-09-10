# NemBestil POS App Shell

A light Android shell for the NemBestil POS system. It provides a configuration wizard to link the device with one or more POS installations, then loads the full POS from a verified URL and uses Capacitor to bridge native features.

## Tech Stack

- **Framework:** Nuxt 4 (SPA)
- **UI:** Nuxt UI v4 + Tailwind CSS 4
- **Bridge:** Capacitor

## Development

```bash
# Install dependencies
npm install

# Run development server
npm run dev

# Run on Android (via scripts)
npm run android:tablet
npm run android:phone
npm run android:device
```

## Features & Conventions

- **Multi-tenant:** Manage and switch between multiple POS installations.
- **SPA Mode:** Always `ssr: false`.
- **API:** All requests use `CapacitorHttp` to bypass CORS.
- **Native:** Bridges printers, scanners, and other native features via Capacitor.

## Android background forwarding

The native `ForwarderService` is a `connectedDevice` foreground service. It owns the persistent POS WebSocket and the direct printer/payment-terminal connections, and it displays an ongoing low-priority notification while enabled.

Incoming takeaway and table-booking events use native Android notifications only while the app is unfocused or the screen is off. While the app is focused, the service forwards notification candidates to the hosted POS so it can show the in-app toast. Sound playback always stays native in this app version: the server includes the installation's event-specific sound choices in the existing configuration snapshot, custom MP3s are downloaded into app-private storage using versioned asset URLs, and events plus sound-editor previews play with Android's notification-audio usage. While the app activity is open, the hardware volume buttons control Android's notification stream so they adjust the same channel used by these sounds. Notification channels themselves are silent to prevent double playback. The `notificationSoundPlaybackSupported` field on `ForwarderService.getStatus()` lets hosted POS versions retain browser playback with older APKs.

Android's battery optimization can suspend network access after the screen has been locked. The hosted POS therefore checks `PowerManager.isIgnoringBatteryOptimizations()` and asks the user, through Android's system dialog, to allow unrestricted background execution. This exemption is required for reliable unattended forwarding; it is requested only from an explicit user action and can be revoked in Android's app battery settings.

The existing socket protocol 3 `devices.snapshot` may include the tablet's battery percentage and power source. The server keeps this object optional so older app versions remain compatible. Android battery broadcasts wake the device monitor, so newer apps report changes between battery power and external power immediately to the POS admin-tools overview.

The plugin advertises this feature through the optional `backgroundExecutionPermissionSupported` field on the existing `getStatus()` response. This keeps staggered deployments safe: older hosted POS versions ignore the field, while newer hosted POS versions hide the permission and retain legacy startup when an older APK omits it.

Do not add a permanent partial wake lock for the idle WebSocket. Android's network stack wakes the process when socket data arrives, while a long-held wake lock would create excessive battery usage. The service's `connectedDevice` type is not subject to Android 15's six-hour `dataSync` foreground-service limit and is still permitted from `BOOT_COMPLETED`.

## Release APK

Pushing a tag named `apk-x.y.z` triggers `.github/workflows/release-apk.yml`. The workflow:

- Validates that the tag version matches both `package.json` and `android/app/build.gradle`
- Builds a signed Android release APK
- Creates a GitHub Release and uploads the APK as a release asset

Create the tag after running the version bump script and committing the result:

```bash
npm run version:bump patch
git add package.json android/app/build.gradle
git commit -m "Bump version to 1.0.1"
git tag apk-1.0.1
git push origin main --follow-tags
```
