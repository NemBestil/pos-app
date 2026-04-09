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
