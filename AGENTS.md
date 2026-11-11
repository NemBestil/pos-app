# Project Guidelines

## Project Overview

Light app shell for the POS system, responsible for making the POS available on Android devices. It provides a login screen, then loads the full POS from a URL and uses Capacitor to bridge native features.

## Coding Style

- Prioritize Nuxt 4 and Nuxt UI v4 patterns. Preserve compatibility with both POS 1.3 and Cloudflare 1.4 backends, including legacy installation domains and Android socket contracts.
- Limit defensive coding: Keep the codebase clean and maintainable.
- Future implementation: Use TODO comments for planned methods/features and remove them once implemented.

## Tech Stack

- Framework: Nuxt 4 (SPA mode)
- UI: Vue 3 + Nuxt UI v4 (Tailwind CSS 4)
- Icons: NuxtIcon (`<Icon>`) with `lucide` icon set
- Bridge: Capacitor (for Android)
- I18N: None, english only.

## Architecture & Conventions

- Frontend is SPA: The shell is built to be client-side only (always `ssr: false`).
- Composables: Shared logic in `app/composables`.
- Platform Bridging: Use Capacitor for native features (printers, scanners, etc.).
- Flow: Login Screen -> Webview/URL Load -> Capacitor Interop.
- Backend compatibility: Read `docs/backend-compatibility.md` before changing installation discovery, allowed navigation, native bridge origins or backend-specific service configuration. Published app 1.4.7 already uses nbpos.eu; the service-router selects registered POS 1.4 installations. Keep the original discovery and native service behavior. The APK's whoami URL is fixed; it does not consume `configuration.snapshot.networkAttestationUrl`. Production POS 1.4 must retain the POS 1.3/legacy-whoami key server-side so native location reports remain valid. Coordinate key changes on the POS side; the APK never receives the signing key.
- android/ is ephemeral: Place new Android source files in resources/, symlink it into android/... and apply changes to other android/-files using scripts/android-patch.sh.
- Receipt printing: Read `docs/receipt-printing.md` before changing direct print delivery, printer queues, byte transfers or cuts. `ForwarderService` owns per-printer FIFO/ACKs and `PrinterJobWriter` owns queued ESC/POS completion checks, body/feed/CUT order and Bluetooth pacing.
- Payment terminals: Read `docs/payment-terminals.md` for discovery, transport-independent TCP checks, retries and availability reporting. `PaymentTerminalReachability` owns endpoints available within five minutes of a local UDP broadcast or TCP success, without an identity-capability or credential gate; discovery candidates may carry only DeviceInformation setup requests (v1 pinned TLS, v2 signed). App 1.4.8 also accepts v0/v1 UDP envelopes and manual IPv4 probes; keep the actual terminal port and vendor-CA trust in `SamportTerminalConnection.java`, loading the public PEM from `resources/android/raw/` through the source-linking script.
- USB access: Read `docs/usb-access.md` before changing USB permissions or reconnection. Keep the attachment filter in `resources/android/xml/usb_device_filter.xml` and its manifest registration in `scripts/android-patch.sh`; Android's remembered handler grants access per device, not through an app-wide runtime permission. Never infer USB access from stored consent or add automatic permission-prompt loops.

## App versions and updates

Read `docs/app-versions.md` for installation version requirements, startup install permission, launcher handoff, and release publishing. Updates must use the selected installation's exact target from `NemBestil/pos-app-releases`; no independent latest-release checker.

## Fetching & API

- Use `CapacitorHttp` (from `@capacitor/core`) for all API requests.
- Usage: `import { CapacitorHttp } from '@capacitor/core'; const res = await CapacitorHttp.get({ url: '...' });`.
- Benefit: No CORS constraints when running on native platforms.
- Typing: Leverage Nuxt's auto-import typing for API responses when possible; don't hand-write types when inferable.

## UI/UX Guidelines

- Favor composition over custom widgets: Use Nuxt UI v4 defaults.
- Touch-Friendly: Optimize sizing and hit targets for touch/tablet use.
- Tailwind 4: Use Tailwind utilities only; no `<style>` blocks.
- Semantic Colors: Use `text-success`, `bg-primary`, etc.
- Responsive: Ensure the shell layout works across mobile and tablet devices.
- `USelect`/`USelectMenu`: `:model-value` is the value itself.

## Project Structure

- `app/` – Client files (components, composables, pages)
- `public/` – Static assets (icons, manifest)
- `nuxt.config.ts` – Nuxt configuration
- `package.json` – Dependencies and scripts
