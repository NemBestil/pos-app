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

### Required GitHub Secrets

Set these repository secrets in GitHub Actions:

- `ANDROID_RELEASE_KEYSTORE_BASE64`: Base64-encoded release keystore file contents
- `ANDROID_RELEASE_STORE_PASSWORD`: Keystore password
- `ANDROID_RELEASE_KEY_ALIAS`: Alias of the signing key inside the keystore
- `ANDROID_RELEASE_KEY_PASSWORD`: Password for that signing key

Optional:

- `GOOGLE_SERVICES_JSON_BASE64`: Base64-encoded `google-services.json` if release builds need Firebase config

`GITHUB_TOKEN` is provided automatically by GitHub Actions. No manual setup is needed for the release upload itself.

Add the secrets in GitHub under `Settings` -> `Secrets and variables` -> `Actions` -> `New repository secret`.

### How To Extract The Secret Values

Base64-encode the keystore as a single line on macOS:

```bash
base64 -i /path/to/release.keystore | tr -d '\n'
```

Copy the output into `ANDROID_RELEASE_KEYSTORE_BASE64`.

Find the key alias in the keystore:

```bash
keytool -list -v -keystore /path/to/release.keystore
```

Use the `Alias name:` value for `ANDROID_RELEASE_KEY_ALIAS`.

The keystore password and key password cannot be extracted from the keystore file. You must already know them. If they are lost, you need the original credential record or you need to create and distribute a new signing key according to your Android release process.

If Firebase is used, encode `google-services.json` the same way:

```bash
base64 -i android/app/google-services.json | tr -d '\n'
```

If you do not already have that file, download it from Firebase Console under `Project settings` -> `Your apps` -> your Android app -> `google-services.json`.
